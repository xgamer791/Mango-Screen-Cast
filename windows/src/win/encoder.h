// H.264 / HEVC encoding with Media Foundation encoder MFTs: hardware ones
// (asynchronous, fed Direct3D 11 NV12 textures through a DXGI device manager)
// first, Microsoft's software H.264 encoder (synchronous, NV12 in memory)
// when there's no hardware one. Low-latency mode, constant bitrate, long GOP,
// keyframes on request.
#pragma once

#include <d3d11.h>
#include <mfidl.h>
#include <wrl/client.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "core/annexb.h"
#include "core/stream_math.h"

namespace mango {

// Media Foundation is loaded on demand (Windows N editions don't have it
// without the Media Feature Pack); false and why when it can't be used.
bool startMediaFoundation(std::string* why);

struct EncoderCandidate {
    Codec codec = Codec::H264;
    bool hardware = false;
    std::string name;
    bool hasAdapter = false;
    LUID adapter{};
    Microsoft::WRL::ComPtr<IMFActivate> activate;
};

// The encoders installed for a codec: hardware ones (best first), or software ones.
std::vector<EncoderCandidate> findEncoders(Codec codec, bool hardware);

class VideoEncoder {
public:
    struct Config {
        Codec codec = Codec::H264;
        Size size;
        int fps = 60;
        double bitrate = 16e6;
    };
    // One encoded picture, as the encoder made it (Annex B).
    using Output = std::function<void(const uint8_t* data, size_t size, int64_t time100ns, bool cleanPoint)>;

    VideoEncoder();
    ~VideoEncoder();
    VideoEncoder(const VideoEncoder&) = delete;
    VideoEncoder& operator=(const VideoEncoder&) = delete;

    // `device` is offered to hardware encoders (it must be multithread
    // protected); they then take NV12 textures made on it.
    bool open(const EncoderCandidate& candidate, const Config& config, ID3D11Device* device, Output output,
              std::string* error);
    void close();
    bool isOpen() const;

    const std::string& name() const;
    bool hardware() const;
    bool takesTextures() const;
    const Config& config() const;

    // Whether it can take a picture now (a hardware encoder asks for them).
    bool ready() const;
    bool encodeTexture(ID3D11Texture2D* nv12, int64_t time100ns, bool keyframe, std::string* error);
    bool encodeMemory(const uint8_t* nv12, int64_t time100ns, bool keyframe, std::string* error);

    void setBitrate(double bitsPerSecond);
    // Set when an asynchronous encoder reported an error.
    bool failed(std::string* why) const;
    // The parameter sets from the encoder's output type, if it gave them.
    std::vector<uint8_t> sequenceHeader() const;

    struct Impl;  // (public only so the encoder's event callback can reach it)

private:
    std::shared_ptr<Impl> impl_;
};

}  // namespace mango
