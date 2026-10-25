// Screen capture with Windows.Graphics.Capture: one monitor, mouse pointer
// included, frames as BGRA textures on our D3D11 device.
#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include <cstdint>
#include <memory>
#include <string>

#include "core/stream_math.h"

namespace mango {

class ScreenCapture {
public:
    ScreenCapture();
    ~ScreenCapture();
    ScreenCapture(const ScreenCapture&) = delete;
    ScreenCapture& operator=(const ScreenCapture&) = delete;

    static bool supported(std::string* why);

    bool start(ID3D11Device* device, HMONITOR monitor, std::string* error);
    void stop();

    struct Frame {
        Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
        Size content;     // the part of the texture that holds the screen
        int64_t timeUs = 0;  // when it was captured (QPC clock)
    };
    // The newest frame since the last call, if any. Hold it only until the
    // next call (the capture reuses a few textures).
    bool latest(Frame& frame);

    // The screen went away (unplugged, or the session locked it out).
    bool closed() const;
    Size size() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace mango
