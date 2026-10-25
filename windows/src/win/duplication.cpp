#include "duplication.h"

#include <d3d11_4.h>
#include <d3dcompiler.h>
#include <dxgi1_5.h>

#include <algorithm>
#include <cstring>

#include "log.h"
#include "util.h"

namespace mango {

using Microsoft::WRL::ComPtr;

namespace {

const char kPointerShaders[] = R"(
cbuffer Place : register(b0) { float4 rect; };  // left, top, right, bottom, in clip space
Texture2D<float4> pointer : register(t0);
SamplerState pointSampler : register(s0);
struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VSOut vs(uint id : SV_VertexID)
{
    float2 t = float2(id & 1, id >> 1);
    VSOut o;
    o.pos = float4(lerp(rect.x, rect.z, t.x), lerp(rect.y, rect.w, t.y), 0, 1);
    o.uv = t;
    return o;
}
float4 ps(VSOut i) : SV_Target { return pointer.Sample(pointSampler, i.uv); }
)";

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

DesktopDuplication::~DesktopDuplication() { stop(); }

bool DesktopDuplication::start(ID3D11Device* device, HMONITOR monitor, std::string* error) {
    stop();
    device_ = device;
    device_->GetImmediateContext(&context_);
    closed_ = false;
    failures_ = 0;

    // The output showing that monitor, on our device's adapter.
    ComPtr<IDXGIDevice> dxgi;
    ComPtr<IDXGIAdapter> adapter;
    HRESULT hr = device_.As(&dxgi);
    if (SUCCEEDED(hr)) hr = dxgi->GetAdapter(&adapter);
    for (UINT i = 0; SUCCEEDED(hr); i++) {
        ComPtr<IDXGIOutput> out;
        if (adapter->EnumOutputs(i, &out) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_OUTPUT_DESC od{};
        if (SUCCEEDED(out->GetDesc(&od)) && od.Monitor == monitor) {
            out.As(&output_);
            break;
        }
    }
    if (!output_) {
        if (error)
            *error = "Desktop Duplication needs the screen to be on the graphics adapter that encodes; "
                     "this one isn't (try --capture wgc)";
        return false;
    }

    ComPtr<ID3DBlob> vsBlob, psBlob, errors;
    hr = D3DCompile(kPointerShaders, sizeof(kPointerShaders) - 1, "mango_pointer", nullptr, nullptr, "vs", "vs_4_0",
                    D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &vsBlob, &errors);
    if (SUCCEEDED(hr))
        hr = D3DCompile(kPointerShaders, sizeof(kPointerShaders) - 1, "mango_pointer", nullptr, nullptr, "ps",
                        "ps_4_0", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &psBlob, &errors);
    if (SUCCEEDED(hr)) hr = device_->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, &vs_);
    if (SUCCEEDED(hr)) hr = device_->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(), nullptr, &ps_);
    if (SUCCEEDED(hr)) {
        D3D11_BUFFER_DESC bd{};
        bd.ByteWidth = 16;
        bd.Usage = D3D11_USAGE_DYNAMIC;
        bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        hr = device_->CreateBuffer(&bd, nullptr, &rect_);
    }
    if (SUCCEEDED(hr)) {
        D3D11_BLEND_DESC bl{};
        auto& rt = bl.RenderTarget[0];
        rt.BlendEnable = TRUE;
        rt.SrcBlend = D3D11_BLEND_SRC_ALPHA;
        rt.DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
        rt.BlendOp = D3D11_BLEND_OP_ADD;
        rt.SrcBlendAlpha = D3D11_BLEND_ONE;
        rt.DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
        rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
        rt.RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
        hr = device_->CreateBlendState(&bl, &blend_);
    }
    if (SUCCEEDED(hr)) {
        D3D11_SAMPLER_DESC sd{};
        sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
        sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
        sd.MaxLOD = D3D11_FLOAT32_MAX;
        hr = device_->CreateSamplerState(&sd, &sampler_);
    }
    if (SUCCEEDED(hr)) {
        D3D11_RASTERIZER_DESC rd{};
        rd.FillMode = D3D11_FILL_SOLID;
        rd.CullMode = D3D11_CULL_NONE;
        rd.DepthClipEnable = TRUE;
        hr = device_->CreateRasterizerState(&rd, &raster_);
    }
    if (FAILED(hr)) {
        if (error) {
            *error = "Couldn't set up drawing the mouse pointer: " + hresultText(hr);
            if (errors) *error += " " + std::string(static_cast<const char*>(errors->GetBufferPointer()));
        }
        return false;
    }
    if (!duplicate(error)) return false;
    DXGI_OUTDUPL_DESC dd{};
    dup_->GetDesc(&dd);
    LOG("Capturing with Desktop Duplication, %u x %u%s", dd.ModeDesc.Width, dd.ModeDesc.Height,
        dd.Rotation > DXGI_MODE_ROTATION_IDENTITY ? " (rotated screens are sent unrotated)" : "");
    return true;
}

bool DesktopDuplication::duplicate(std::string* error) {
    dup_.Reset();
    frameHeld_ = false;
    HRESULT hr = E_FAIL;
    ComPtr<IDXGIOutput5> output5;
    if (SUCCEEDED(output_.As(&output5))) {
        // Asks for 8-bit BGRA, which also works on HDR screens.
        const DXGI_FORMAT formats[] = {DXGI_FORMAT_B8G8R8A8_UNORM};
        hr = output5->DuplicateOutput1(device_.Get(), 0, 1, formats, &dup_);
    }
    if (FAILED(hr)) hr = output_->DuplicateOutput(device_.Get(), &dup_);
    if (FAILED(hr)) {
        if (error) {
            *error = hr == DXGI_ERROR_NOT_CURRENTLY_AVAILABLE
                         ? "Too many programs are already duplicating this screen"
                         : "Desktop Duplication isn't available: " + hresultText(hr);
        }
        return false;
    }
    return true;
}

void DesktopDuplication::stop() {
    if (dup_ && frameHeld_) dup_->ReleaseFrame();
    frameHeld_ = false;
    dup_.Reset();
    output_.Reset();
    base_.Reset();
    composed_.Reset();
    composedRtv_.Reset();
    pointerSrv_.Reset();
    haveBase_ = false;
}

void DesktopDuplication::updatePointer(const DXGI_OUTDUPL_FRAME_INFO& info) {
    if (info.LastMouseUpdateTime.QuadPart != 0) {
        bool visible = info.PointerPosition.Visible != FALSE;
        POINT pos = info.PointerPosition.Position;
        if (visible != pointerVisible_ || pos.x != pointerPos_.x || pos.y != pointerPos_.y) pointerChanged_ = true;
        pointerVisible_ = visible;
        pointerPos_ = pos;
    }
    if (info.PointerShapeBufferSize == 0) return;
    shapeBuffer_.resize(info.PointerShapeBufferSize);
    UINT needed = 0;
    if (FAILED(dup_->GetFramePointerShape(info.PointerShapeBufferSize, shapeBuffer_.data(), &needed, &shapeInfo_)))
        return;
    pointerChanged_ = true;

    // Every kind of pointer as BGRA with straight alpha.
    const UINT w = shapeInfo_.Width;
    const UINT h = shapeInfo_.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME ? shapeInfo_.Height / 2
                                                                                 : shapeInfo_.Height;
    if (w == 0 || h == 0) return;
    std::vector<uint32_t> bgra(static_cast<size_t>(w) * h, 0);
    for (UINT y = 0; y < h; y++) {
        for (UINT x = 0; x < w; x++) {
            uint32_t& px = bgra[static_cast<size_t>(y) * w + x];
            if (shapeInfo_.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME) {
                const uint8_t mask = static_cast<uint8_t>(0x80 >> (x % 8));
                bool andBit = (shapeBuffer_[y * shapeInfo_.Pitch + x / 8] & mask) != 0;
                bool xorBit = (shapeBuffer_[(y + h) * shapeInfo_.Pitch + x / 8] & mask) != 0;
                if (!andBit) px = xorBit ? 0xFFFFFFFFu : 0xFF000000u;  // white or black
                else px = xorBit ? 0xFF000000u : 0;  // screen inverted (drawn black), or see-through
            } else {
                uint32_t src;
                std::memcpy(&src, &shapeBuffer_[y * shapeInfo_.Pitch + x * 4], 4);
                if (shapeInfo_.Type == DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MASKED_COLOR) {
                    // Alpha 0: the colour; 0xFF: XOR with the screen (drawn when it isn't black).
                    bool xorPixel = (src >> 24) != 0;
                    uint32_t rgb = src & 0x00FFFFFFu;
                    px = xorPixel ? (rgb ? 0xFF000000u | rgb : 0) : 0xFF000000u | rgb;
                } else {
                    px = src;
                }
            }
        }
    }
    D3D11_TEXTURE2D_DESC td{};
    td.Width = w;
    td.Height = h;
    td.MipLevels = 1;
    td.ArraySize = 1;
    td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage = D3D11_USAGE_IMMUTABLE;
    td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA init{bgra.data(), w * 4, 0};
    ComPtr<ID3D11Texture2D> tex;
    pointerSrv_.Reset();
    if (SUCCEEDED(device_->CreateTexture2D(&td, &init, &tex))) device_->CreateShaderResourceView(tex.Get(), nullptr, &pointerSrv_);
    pointerW_ = static_cast<int>(w);
    pointerH_ = static_cast<int>(h);
}

bool DesktopDuplication::compose() {
    DeviceLock lock(device_.Get());
    context_->CopyResource(composed_.Get(), base_.Get());
    if (!pointerVisible_ || !pointerSrv_) return true;
    D3D11_MAPPED_SUBRESOURCE m{};
    if (FAILED(context_->Map(rect_.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &m))) return true;
    const float W = static_cast<float>(size_.width), H = static_cast<float>(size_.height);
    const float rect[4] = {2.0f * pointerPos_.x / W - 1.0f, 1.0f - 2.0f * pointerPos_.y / H,
                           2.0f * (pointerPos_.x + pointerW_) / W - 1.0f, 1.0f - 2.0f * (pointerPos_.y + pointerH_) / H};
    std::memcpy(m.pData, rect, sizeof rect);
    context_->Unmap(rect_.Get(), 0);
    D3D11_VIEWPORT vp{0, 0, W, H, 0, 1};
    context_->RSSetViewports(1, &vp);
    context_->RSSetState(raster_.Get());
    ID3D11RenderTargetView* rtv = composedRtv_.Get();
    context_->OMSetRenderTargets(1, &rtv, nullptr);
    const float factor[4] = {0, 0, 0, 0};
    context_->OMSetBlendState(blend_.Get(), factor, 0xffffffff);
    context_->OMSetDepthStencilState(nullptr, 0);
    context_->IASetInputLayout(nullptr);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);
    context_->VSSetShader(vs_.Get(), nullptr, 0);
    ID3D11Buffer* buffers[] = {rect_.Get()};
    context_->VSSetConstantBuffers(0, 1, buffers);
    context_->PSSetShader(ps_.Get(), nullptr, 0);
    ID3D11ShaderResourceView* srv = pointerSrv_.Get();
    context_->PSSetShaderResources(0, 1, &srv);
    ID3D11SamplerState* samplers[] = {sampler_.Get()};
    context_->PSSetSamplers(0, 1, samplers);
    context_->Draw(4, 0);
    ID3D11ShaderResourceView* noSrv[] = {nullptr};
    context_->PSSetShaderResources(0, 1, noSrv);
    context_->OMSetRenderTargets(0, nullptr, nullptr);
    context_->OMSetBlendState(nullptr, nullptr, 0xffffffff);
    return true;
}

bool DesktopDuplication::latest(ScreenCapture::Frame& out) {
    if (!output_) return false;
    if (!dup_) {
        // Lost (a mode change, the secure desktop): try again now and then.
        if (!duplicate(nullptr)) {
            if (++failures_ > 600) closed_ = true;  // ~10 s at 60 fps
            return false;
        }
        failures_ = 0;
        haveBase_ = false;
    }
    if (frameHeld_) {
        dup_->ReleaseFrame();
        frameHeld_ = false;
    }
    DXGI_OUTDUPL_FRAME_INFO info{};
    ComPtr<IDXGIResource> resource;
    HRESULT hr = dup_->AcquireNextFrame(0, &info, &resource);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return false;
    if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL) {
        LOG("Desktop Duplication lost the screen (%s); starting it again", hresultText(hr).c_str());
        dup_.Reset();
        return false;
    }
    if (FAILED(hr)) {
        LOG("Desktop Duplication: %s", hresultText(hr).c_str());
        dup_.Reset();
        return false;
    }
    frameHeld_ = true;
    pointerChanged_ = false;
    updatePointer(info);
    const bool newImage = info.LastPresentTime.QuadPart != 0 || !haveBase_;
    if (newImage) {
        ComPtr<ID3D11Texture2D> desktop;
        if (FAILED(resource.As(&desktop))) return false;
        D3D11_TEXTURE2D_DESC dd{};
        desktop->GetDesc(&dd);
        Size size{static_cast<int>(dd.Width), static_cast<int>(dd.Height)};
        if (size != size_ || !base_) {
            LOG("Screen size %d x %d", size.width, size.height);
            size_ = size;
            D3D11_TEXTURE2D_DESC td{};
            td.Width = dd.Width;
            td.Height = dd.Height;
            td.MipLevels = 1;
            td.ArraySize = 1;
            td.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
            td.SampleDesc.Count = 1;
            td.Usage = D3D11_USAGE_DEFAULT;
            td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
            base_.Reset();
            composed_.Reset();
            composedRtv_.Reset();
            HRESULT h2 = device_->CreateTexture2D(&td, nullptr, &base_);
            td.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
            if (SUCCEEDED(h2)) h2 = device_->CreateTexture2D(&td, nullptr, &composed_);
            if (SUCCEEDED(h2)) h2 = device_->CreateRenderTargetView(composed_.Get(), nullptr, &composedRtv_);
            if (FAILED(h2)) {
                LOG("Desktop Duplication: no copy of the screen: %s", hresultText(h2).c_str());
                return false;
            }
        }
        {
            DeviceLock lock(device_.Get());
            context_->CopyResource(base_.Get(), desktop.Get());
        }
        haveBase_ = true;
    } else if (!pointerChanged_) {
        return false;  // nothing new
    }
    compose();
    out.texture = composed_;
    out.content = size_;
    out.timeUs = info.LastPresentTime.QuadPart ? qpcToUs(info.LastPresentTime.QuadPart) : qpcNowUs();
    return true;
}

}  // namespace mango
