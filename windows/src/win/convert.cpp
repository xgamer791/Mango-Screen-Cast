#include "convert.h"

#include <d3d11_4.h>
#include <d3dcompiler.h>

#include <algorithm>
#include <cstring>

#include "log.h"
#include "util.h"

namespace mango {

using Microsoft::WRL::ComPtr;

namespace {

// Ring of NV12 textures: a hardware encoder may still be reading the last
// few while the next one is drawn.
constexpr int kRing = 5;

const char kShaders[] = R"(
cbuffer Params : register(b0)
{
    float2 uvScale;  // texture coordinates of the screen's bottom-right corner
    float2 tap;      // offset of the extra taps when shrinking (0: one tap)
    float2 uvMin;    // keep taps inside the screen part of the texture
    float2 uvMax;
    float4 rowY;     // weights of R, G, B, and the offset
    float4 rowU;
    float4 rowV;
};
Texture2D<float4> screen : register(t0);
SamplerState linearClamp : register(s0);

struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };

VSOut vs(uint id : SV_VertexID)
{
    // One triangle that covers the whole target.
    VSOut o;
    float2 t = float2((id << 1) & 2, id & 2);
    o.pos = float4(t.x * 2 - 1, 1 - t.y * 2, 0, 1);
    o.uv = t;
    return o;
}

float3 at(float2 p)
{
    return screen.SampleLevel(linearClamp, clamp(p, uvMin, uvMax), 0).rgb;
}

float3 fetch(float2 uv)
{
    float2 p = uv * uvScale;
    if (tap.x > 0 || tap.y > 0)
        return 0.25 * (at(p + float2(-tap.x, -tap.y)) + at(p + float2(tap.x, -tap.y)) +
                       at(p + float2(-tap.x, tap.y)) + at(p + float2(tap.x, tap.y)));
    return at(p);
}

float psY(VSOut i) : SV_Target
{
    return dot(fetch(i.uv), rowY.rgb) + rowY.a;
}

float2 psUV(VSOut i) : SV_Target
{
    float3 c = fetch(i.uv);
    return float2(dot(c, rowU.rgb) + rowU.a, dot(c, rowV.rgb) + rowV.a);
}
)";

struct alignas(16) Params {
    float uvScale[2];
    float tap[2];
    float uvMin[2];
    float uvMax[2];
    float rowY[4];
    float rowU[4];
    float rowV[4];
};

// BT.709, limited range (16-235 luma, 16-240 chroma), from full-range RGB.
constexpr float kY = 219.0f / 255.0f, kC = 224.0f / 255.0f;
const float kRowY[4] = {0.2126f * kY, 0.7152f * kY, 0.0722f * kY, 16.0f / 255.0f};
const float kRowU[4] = {-0.114572f * kC, -0.385428f * kC, 0.5f * kC, 128.0f / 255.0f};
const float kRowV[4] = {0.5f * kC, -0.454153f * kC, -0.045847f * kC, 128.0f / 255.0f};

bool compile(const char* entry, const char* target, ComPtr<ID3DBlob>& out, std::string* error) {
    ComPtr<ID3DBlob> errors;
    HRESULT hr = D3DCompile(kShaders, sizeof(kShaders) - 1, "mango_nv12", nullptr, nullptr, entry, target,
                            D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &out, &errors);
    if (FAILED(hr)) {
        if (error) {
            *error = std::string("Couldn't compile the colour conversion shader ") + entry + ": " + hresultText(hr);
            if (errors) *error += " " + std::string(static_cast<const char*>(errors->GetBufferPointer()));
        }
        return false;
    }
    return true;
}

// Holds the device's lock (it's multithread protected) so another thread's
// use of the immediate context can't come between our calls.
class DeviceLock {
public:
    explicit DeviceLock(ID3D11Device* device) {
        if (SUCCEEDED(device->QueryInterface(IID_PPV_ARGS(&mt_)))) mt_->Enter();
    }
    ~DeviceLock() {
        if (mt_) mt_->Leave();
    }

private:
    ComPtr<ID3D11Multithread> mt_;
};

}  // namespace

bool Nv12Converter::canRenderNv12(ID3D11Device* device) {
    UINT support = 0;
    if (FAILED(device->CheckFormatSupport(DXGI_FORMAT_NV12, &support))) return false;
    return (support & D3D11_FORMAT_SUPPORT_TEXTURE2D) && (support & D3D11_FORMAT_SUPPORT_RENDER_TARGET);
}

bool Nv12Converter::init(ID3D11Device* device, std::string* error) {
    device_ = device;
    device_->GetImmediateContext(&context_);
    ComPtr<ID3DBlob> vs, psY, psUV;
    if (!compile("vs", "vs_4_0", vs, error) || !compile("psY", "ps_4_0", psY, error) ||
        !compile("psUV", "ps_4_0", psUV, error))
        return false;
    HRESULT hr = device_->CreateVertexShader(vs->GetBufferPointer(), vs->GetBufferSize(), nullptr, &vs_);
    if (SUCCEEDED(hr)) hr = device_->CreatePixelShader(psY->GetBufferPointer(), psY->GetBufferSize(), nullptr, &psY_);
    if (SUCCEEDED(hr))
        hr = device_->CreatePixelShader(psUV->GetBufferPointer(), psUV->GetBufferSize(), nullptr, &psUV_);
    if (SUCCEEDED(hr)) {
        D3D11_SAMPLER_DESC sd{};
        sd.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
        sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        sd.MaxLOD = D3D11_FLOAT32_MAX;
        sd.ComparisonFunc = D3D11_COMPARISON_NEVER;
        hr = device_->CreateSamplerState(&sd, &sampler_);
    }
    if (SUCCEEDED(hr)) {
        D3D11_RASTERIZER_DESC rd{};
        rd.FillMode = D3D11_FILL_SOLID;
        rd.CullMode = D3D11_CULL_NONE;
        rd.DepthClipEnable = TRUE;
        hr = device_->CreateRasterizerState(&rd, &raster_);
    }
    if (SUCCEEDED(hr)) {
        D3D11_BUFFER_DESC bd{};
        bd.ByteWidth = sizeof(Params);
        bd.Usage = D3D11_USAGE_DYNAMIC;
        bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        hr = device_->CreateBuffer(&bd, nullptr, &params_);
    }
    if (FAILED(hr)) {
        if (error) *error = "Couldn't set up the colour conversion: " + hresultText(hr);
        return false;
    }
    return true;
}

bool Nv12Converter::configure(Size size, Output output, std::string* error) {
    if (size == size_ && output == output_ && !ring_.empty()) return true;
    ring_.clear();
    stagingY_.Reset();
    stagingUV_.Reset();
    current_ = -1;
    size_ = size;
    output_ = output;
    const UINT w = static_cast<UINT>(size.width), h = static_cast<UINT>(size.height);
    HRESULT hr = S_OK;
    int count = output == Output::Textures ? kRing : 1;
    for (int i = 0; i < count && SUCCEEDED(hr); i++) {
        Target t;
        D3D11_TEXTURE2D_DESC td{};
        td.MipLevels = 1;
        td.ArraySize = 1;
        td.SampleDesc.Count = 1;
        td.Usage = D3D11_USAGE_DEFAULT;
        td.BindFlags = D3D11_BIND_RENDER_TARGET;
        if (output == Output::Textures) {
            td.Width = w;
            td.Height = h;
            td.Format = DXGI_FORMAT_NV12;
            // Some encoders read their input with shaders; let them when the driver allows.
            UINT support = 0;
            if (SUCCEEDED(device_->CheckFormatSupport(DXGI_FORMAT_NV12, &support)) &&
                (support & D3D11_FORMAT_SUPPORT_SHADER_SAMPLE))
                td.BindFlags |= D3D11_BIND_SHADER_RESOURCE;
            hr = device_->CreateTexture2D(&td, nullptr, &t.texture);
            D3D11_RENDER_TARGET_VIEW_DESC rv{};
            rv.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
            rv.Format = DXGI_FORMAT_R8_UNORM;  // the luma plane
            if (SUCCEEDED(hr)) hr = device_->CreateRenderTargetView(t.texture.Get(), &rv, &t.y);
            rv.Format = DXGI_FORMAT_R8G8_UNORM;  // the chroma plane
            if (SUCCEEDED(hr)) hr = device_->CreateRenderTargetView(t.texture.Get(), &rv, &t.uv);
        } else {
            td.Width = w;
            td.Height = h;
            td.Format = DXGI_FORMAT_R8_UNORM;
            hr = device_->CreateTexture2D(&td, nullptr, &t.texture);
            if (SUCCEEDED(hr)) hr = device_->CreateRenderTargetView(t.texture.Get(), nullptr, &t.y);
            td.Width = w / 2;
            td.Height = h / 2;
            td.Format = DXGI_FORMAT_R8G8_UNORM;
            if (SUCCEEDED(hr)) hr = device_->CreateTexture2D(&td, nullptr, &t.chroma);
            if (SUCCEEDED(hr)) hr = device_->CreateRenderTargetView(t.chroma.Get(), nullptr, &t.uv);
        }
        if (SUCCEEDED(hr)) ring_.push_back(t);
    }
    if (SUCCEEDED(hr) && output == Output::Memory) {
        D3D11_TEXTURE2D_DESC sd{};
        sd.MipLevels = 1;
        sd.ArraySize = 1;
        sd.SampleDesc.Count = 1;
        sd.Usage = D3D11_USAGE_STAGING;
        sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        sd.Width = w;
        sd.Height = h;
        sd.Format = DXGI_FORMAT_R8_UNORM;
        hr = device_->CreateTexture2D(&sd, nullptr, &stagingY_);
        sd.Width = w / 2;
        sd.Height = h / 2;
        sd.Format = DXGI_FORMAT_R8G8_UNORM;
        if (SUCCEEDED(hr)) hr = device_->CreateTexture2D(&sd, nullptr, &stagingUV_);
    }
    if (FAILED(hr)) {
        ring_.clear();
        size_ = Size();
        if (error)
            *error = "Couldn't make " + std::to_string(w) + " x " + std::to_string(h) +
                     (output == Output::Textures ? " NV12 textures: " : " picture planes: ") + hresultText(hr);
        return false;
    }
    return true;
}

bool Nv12Converter::draw(ID3D11ShaderResourceView* srv, ID3D11RenderTargetView* rtv, ID3D11PixelShader* ps, int w,
                         int h, const float tap[2]) {
    D3D11_MAPPED_SUBRESOURCE m{};
    if (FAILED(context_->Map(params_.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) return false;
    Params p{};
    std::memcpy(p.uvScale, uvScale_, sizeof p.uvScale);
    std::memcpy(p.tap, tap, sizeof p.tap);
    std::memcpy(p.uvMin, uvMin_, sizeof p.uvMin);
    std::memcpy(p.uvMax, uvMax_, sizeof p.uvMax);
    std::memcpy(p.rowY, kRowY, sizeof p.rowY);
    std::memcpy(p.rowU, kRowU, sizeof p.rowU);
    std::memcpy(p.rowV, kRowV, sizeof p.rowV);
    std::memcpy(m.pData, &p, sizeof p);
    context_->Unmap(params_.Get(), 0);

    D3D11_VIEWPORT vp{0, 0, static_cast<float>(w), static_cast<float>(h), 0, 1};
    context_->RSSetViewports(1, &vp);
    context_->OMSetRenderTargets(1, &rtv, nullptr);
    context_->PSSetShader(ps, nullptr, 0);
    context_->PSSetShaderResources(0, 1, &srv);
    context_->Draw(3, 0);
    return true;
}

bool Nv12Converter::convert(ID3D11Texture2D* source, Size content, std::string* error) {
    if (ring_.empty()) {
        if (error) *error = "the converter isn't configured";
        return false;
    }
    D3D11_TEXTURE2D_DESC sd{};
    source->GetDesc(&sd);
    DeviceLock lock(device_.Get());
    ComPtr<ID3D11ShaderResourceView> srv;
    HRESULT hr = device_->CreateShaderResourceView(source, nullptr, &srv);
    if (FAILED(hr)) {
        if (error) *error = "Couldn't read the captured screen: " + hresultText(hr);
        return false;
    }
    const float tw = static_cast<float>(sd.Width), th = static_cast<float>(sd.Height);
    uvScale_[0] = content.width / tw;
    uvScale_[1] = content.height / th;
    uvMin_[0] = 0.5f / tw;
    uvMin_[1] = 0.5f / th;
    uvMax_[0] = (content.width - 0.5f) / tw;
    uvMax_[1] = (content.height - 0.5f) / th;
    // Source pixels per output pixel, and per chroma sample (twice that).
    const float fx = static_cast<float>(content.width) / size_.width;
    const float fy = static_cast<float>(content.height) / size_.height;
    const float tapY[2] = {fx > 1.5f || fy > 1.5f ? fx / 4 / tw : 0.0f, fx > 1.5f || fy > 1.5f ? fy / 4 / th : 0.0f};
    const float tapUV[2] = {2 * fx / 4 / tw, 2 * fy / 4 / th};

    current_ = (current_ + 1) % static_cast<int>(ring_.size());
    Target& t = ring_[static_cast<size_t>(current_)];
    context_->IASetInputLayout(nullptr);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->VSSetShader(vs_.Get(), nullptr, 0);
    context_->RSSetState(raster_.Get());
    context_->OMSetBlendState(nullptr, nullptr, 0xffffffff);
    context_->OMSetDepthStencilState(nullptr, 0);
    ID3D11SamplerState* samplers[] = {sampler_.Get()};
    context_->PSSetSamplers(0, 1, samplers);
    ID3D11Buffer* buffers[] = {params_.Get()};
    context_->PSSetConstantBuffers(0, 1, buffers);
    bool ok = draw(srv.Get(), t.y.Get(), psY_.Get(), size_.width, size_.height, tapY) &&
              draw(srv.Get(), t.uv.Get(), psUV_.Get(), size_.width / 2, size_.height / 2, tapUV);
    ID3D11ShaderResourceView* noSrv[] = {nullptr};
    context_->PSSetShaderResources(0, 1, noSrv);
    context_->OMSetRenderTargets(0, nullptr, nullptr);
    context_->Flush();
    if (!ok && error) *error = "Couldn't draw the converted picture";
    return ok;
}

ID3D11Texture2D* Nv12Converter::current() const {
    return current_ >= 0 ? ring_[static_cast<size_t>(current_)].texture.Get() : nullptr;
}

bool Nv12Converter::read(std::vector<uint8_t>& nv12, std::string* error) {
    if (output_ != Output::Memory || current_ < 0) {
        if (error) *error = "nothing to read";
        return false;
    }
    const size_t w = static_cast<size_t>(size_.width), h = static_cast<size_t>(size_.height);
    nv12.resize(w * h * 3 / 2);
    DeviceLock lock(device_.Get());
    Target& t = ring_[static_cast<size_t>(current_)];
    context_->CopyResource(stagingY_.Get(), t.texture.Get());
    context_->CopyResource(stagingUV_.Get(), t.chroma.Get());
    D3D11_MAPPED_SUBRESOURCE m{};
    HRESULT hr = context_->Map(stagingY_.Get(), 0, D3D11_MAP_READ, 0, &m);
    if (SUCCEEDED(hr)) {
        for (size_t y = 0; y < h; y++)
            std::memcpy(nv12.data() + y * w, static_cast<const uint8_t*>(m.pData) + y * m.RowPitch, w);
        context_->Unmap(stagingY_.Get(), 0);
        hr = context_->Map(stagingUV_.Get(), 0, D3D11_MAP_READ, 0, &m);
    }
    if (SUCCEEDED(hr)) {
        uint8_t* uv = nv12.data() + w * h;
        for (size_t y = 0; y < h / 2; y++)
            std::memcpy(uv + y * w, static_cast<const uint8_t*>(m.pData) + y * m.RowPitch, w);  // w/2 pairs
        context_->Unmap(stagingUV_.Get(), 0);
    }
    if (FAILED(hr)) {
        if (error) *error = "Couldn't read the converted picture back: " + hresultText(hr);
        return false;
    }
    return true;
}

}  // namespace mango
