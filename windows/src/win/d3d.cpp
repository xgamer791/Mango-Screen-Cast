#include "d3d.h"

#include <d3d11_4.h>
#include <dxgi1_2.h>

#include "util.h"

namespace mango {

bool sameLuid(const LUID& a, const LUID& b) { return a.LowPart == b.LowPart && a.HighPart == b.HighPart; }

static ComPtr<IDXGIAdapter1> findAdapter(const LUID& luid) {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return nullptr;
    for (UINT i = 0;; i++) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(i, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 desc{};
        if (SUCCEEDED(adapter->GetDesc1(&desc)) && sameLuid(desc.AdapterLuid, luid)) return adapter;
    }
    return nullptr;
}

bool createD3DDevice(const LUID* luid, bool warp, D3DDevice& out, std::string* error) {
    out = D3DDevice();
    ComPtr<IDXGIAdapter1> adapter;
    if (luid && !warp) adapter = findAdapter(*luid);
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1,
                                        D3D_FEATURE_LEVEL_10_0};
    D3D_DRIVER_TYPE type = warp ? D3D_DRIVER_TYPE_WARP : adapter ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE;
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    HRESULT hr = E_FAIL;
    for (int attempt = 0; attempt < 2; attempt++) {
        hr = D3D11CreateDevice(adapter.Get(), type, nullptr, flags, levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
                               &out.device, nullptr, &out.context);
        if (hr == E_INVALIDARG) {  // no 11.1 runtime: without it
            hr = D3D11CreateDevice(adapter.Get(), type, nullptr, flags, levels + 1, ARRAYSIZE(levels) - 1,
                                   D3D11_SDK_VERSION, &out.device, nullptr, &out.context);
        }
        if (SUCCEEDED(hr)) break;
        flags &= ~D3D11_CREATE_DEVICE_VIDEO_SUPPORT;  // some drivers refuse video support
    }
    if (FAILED(hr)) {
        if (error) *error = "Direct3D 11 isn't available: " + hresultText(hr);
        return false;
    }
    // Capture, conversion and the encoder use the device from several threads.
    ComPtr<ID3D11Multithread> mt;
    if (SUCCEEDED(out.device.As(&mt))) mt->SetMultithreadProtected(TRUE);

    ComPtr<IDXGIDevice> dxgi;
    ComPtr<IDXGIAdapter> used;
    if (SUCCEEDED(out.device.As(&dxgi)) && SUCCEEDED(dxgi->GetAdapter(&used))) {
        DXGI_ADAPTER_DESC desc{};
        used->GetDesc(&desc);
        out.adapterName = narrow(desc.Description);
        out.luid = desc.AdapterLuid;
        // Microsoft Basic Render Driver (vendor 0x1414, device 0x8c) draws on the CPU.
        out.software = warp || (desc.VendorId == 0x1414 && desc.DeviceId == 0x8c);
    }
    return true;
}

}  // namespace mango
