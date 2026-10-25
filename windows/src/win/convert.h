// Turns a captured BGRA screen into NV12 (BT.709, limited range) at the size
// we send, on the GPU with two small pixel shaders: one draws the luma plane,
// one the half-size chroma plane, and both scale (with extra taps when
// shrinking a lot). It runs on any Direct3D 11 device, WARP included.
//
// Two ways out:
//  - NV12 textures (a small ring of them), for hardware encoders that take
//    Direct3D surfaces;
//  - separate luma and chroma textures read back into memory as one NV12
//    picture, for encoders that take pictures in memory.
#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include <cstdint>
#include <string>
#include <vector>

#include "core/stream_math.h"

namespace mango {

class Nv12Converter {
public:
    enum class Output { Textures, Memory };

    static bool canRenderNv12(ID3D11Device* device);

    bool init(ID3D11Device* device, std::string* error);
    // (Re)makes the output pictures for this size and kind of output.
    bool configure(Size size, Output output, std::string* error);
    Size size() const { return size_; }
    Output output() const { return output_; }

    // Draws `content` (the top-left part of `source` holding the screen).
    // With Output::Textures it goes to the next texture of the ring, which
    // `current()` then returns; with Output::Memory call `read()` next.
    bool convert(ID3D11Texture2D* source, Size content, std::string* error);
    ID3D11Texture2D* current() const;
    // NV12 in memory: width x height luma, then width x height/2 chroma.
    bool read(std::vector<uint8_t>& nv12, std::string* error);

private:
    bool draw(ID3D11ShaderResourceView* srv, ID3D11RenderTargetView* rtv, ID3D11PixelShader* ps, int w, int h,
              const float tap[2]);

    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> vs_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> psY_, psUV_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11RasterizerState> raster_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> params_;

    struct Target {
        Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;  // NV12, or the luma plane
        Microsoft::WRL::ComPtr<ID3D11Texture2D> chroma;   // Memory: the chroma plane
        Microsoft::WRL::ComPtr<ID3D11RenderTargetView> y, uv;
    };
    std::vector<Target> ring_;
    int current_ = -1;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> stagingY_, stagingUV_;
    Size size_;
    Output output_ = Output::Memory;
    float uvScale_[2] = {1, 1}, uvMin_[2] = {0, 0}, uvMax_[2] = {1, 1};
};

}  // namespace mango
