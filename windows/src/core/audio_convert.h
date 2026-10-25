// Turns whatever the sound device mixes in (float or integer samples, any
// rate, mono to 7.1) into what Mango Cast sends: 48 kHz stereo signed 16-bit.
// Portable C++17.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace mango {

enum class SampleFormat { Float32, Int16, Int24, Int32 };

struct InputAudioFormat {
    SampleFormat sample = SampleFormat::Float32;
    int channels = 2;
    int rate = 48000;
    int blockAlign = 8;         // bytes per frame (all channels)
    uint32_t channelMask = 0;   // WAVEFORMATEXTENSIBLE speaker bits; 0 = default order
};

class AudioConverter {
public:
    static constexpr int kOutRate = 48000;

    void reset(const InputAudioFormat& in);

    // Converts `frames` frames and appends the result (interleaved L, R) to
    // `out`. Pass data == nullptr for that many frames of silence.
    void process(const uint8_t* data, size_t frames, std::vector<int16_t>& out);

    const InputAudioFormat& format() const { return in_; }

private:
    float sample(const uint8_t* p) const;
    void resample(std::vector<int16_t>& out);

    InputAudioFormat in_;
    std::vector<float> gainL_, gainR_;  // per input channel
    // Stereo frames not yet resampled; index 0 is the frame before `pos_`.
    std::vector<float> pending_;
    double pos_ = 1.0;  // position of the next output frame in `pending_`
    double step_ = 1.0;
};

// Gains (to left, to right) of each input channel when mixing down to stereo.
void downmixGains(int channels, uint32_t mask, std::vector<float>& left, std::vector<float>& right);

}  // namespace mango
