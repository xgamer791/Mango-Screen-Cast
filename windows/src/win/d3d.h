// The Direct3D 11 device the picture is captured, converted and (with a
// hardware encoder) encoded on.
#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include <string>

namespace mango {

using Microsoft::WRL::ComPtr;

struct D3DDevice {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    std::string adapterName;
    LUID luid{};
    bool software = false;  // WARP or the Basic Render Driver
};

// On the adapter with this LUID (nullptr: the default one), or on WARP.
bool createD3DDevice(const LUID* adapter, bool warp, D3DDevice& out, std::string* error);

bool sameLuid(const LUID& a, const LUID& b);

}  // namespace mango
