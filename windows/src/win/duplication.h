// Screen capture with DXGI Desktop Duplication: the fallback when
// Windows.Graphics.Capture isn't there, and a choice (--capture duplication)
// for Windows 10, where Windows.Graphics.Capture draws a yellow frame round
// the screen being captured. The mouse pointer is drawn in by us.
#pragma once

#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <string>
#include <vector>

#include "capture.h"

namespace mango {

class DesktopDuplication {
public:
    DesktopDuplication() = default;
    ~DesktopDuplication();
    DesktopDuplication(const DesktopDuplication&) = delete;
    DesktopDuplication& operator=(const DesktopDuplication&) = delete;

    bool start(ID3D11Device* device, HMONITOR monitor, std::string* error);
    void stop();
    // As ScreenCapture::latest: the newest picture (pointer drawn in) since the last call.
    bool latest(ScreenCapture::Frame& frame);
    bool closed() const { return closed_; }

private:
    bool duplicate(std::string* error);
    void updatePointer(const DXGI_OUTDUPL_FRAME_INFO& info);
    bool compose();

    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<IDXGIOutput1> output_;
    Microsoft::WRL::ComPtr<IDXGIOutputDuplication> dup_;
    bool frameHeld_ = false;
    bool closed_ = false;
    int failures_ = 0;
    Size size_;

    Microsoft::WRL::ComPtr<ID3D11Texture2D> base_;      // the desktop as last presented
    Microsoft::WRL::ComPtr<ID3D11Texture2D> composed_;  // ... with the pointer drawn on it
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> composedRtv_;
    bool haveBase_ = false;

    // The pointer.
    std::vector<uint8_t> shapeBuffer_;
    DXGI_OUTDUPL_POINTER_SHAPE_INFO shapeInfo_{};
    bool pointerVisible_ = false;
    POINT pointerPos_{};
    bool pointerChanged_ = false;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> pointerSrv_;
    int pointerW_ = 0, pointerH_ = 0;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> vs_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> ps_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> rect_;
    Microsoft::WRL::ComPtr<ID3D11BlendState> blend_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11RasterizerState> raster_;
};

}  // namespace mango
