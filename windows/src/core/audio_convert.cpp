#include "audio_convert.h"

#include <cmath>
#include <cstring>

namespace mango {

namespace {

// Speaker bits, as in ksmedia.h.
constexpr uint32_t kFrontLeft = 0x1, kFrontRight = 0x2, kFrontCenter = 0x4, kLowFrequency = 0x8,
                   kBackLeft = 0x10, kBackRight = 0x20, kFrontLeftOfCenter = 0x40, kFrontRightOfCenter = 0x80,
                   kBackCenter = 0x100, kSideLeft = 0x200, kSideRight = 0x400, kTopCenter = 0x800,
                   kTopFrontLeft = 0x1000, kTopFrontCenter = 0x2000, kTopFrontRight = 0x4000,
                   kTopBackLeft = 0x8000, kTopBackCenter = 0x10000, kTopBackRight = 0x20000;

constexpr float kHalfPower = 0.70710678f;

void gainsFor(uint32_t speaker, float& l, float& r) {
    switch (speaker) {
        case kFrontLeft: l = 1; r = 0; break;
        case kFrontRight: l = 0; r = 1; break;
        case kFrontCenter: l = r = kHalfPower; break;
        case kLowFrequency: l = r = 0; break;
        case kBackLeft: case kSideLeft: case kTopFrontLeft: case kTopBackLeft: l = kHalfPower; r = 0; break;
        case kBackRight: case kSideRight: case kTopFrontRight: case kTopBackRight: l = 0; r = kHalfPower; break;
        case kFrontLeftOfCenter: l = 0.9f; r = 0.3f; break;
        case kFrontRightOfCenter: l = 0.3f; r = 0.9f; break;
        case kBackCenter: case kTopCenter: case kTopFrontCenter: case kTopBackCenter: l = r = 0.5f; break;
        default: l = r = 0.5f; break;
    }
}

}  // namespace

void downmixGains(int channels, uint32_t mask, std::vector<float>& left, std::vector<float>& right) {
    left.assign(static_cast<size_t>(channels), 0.0f);
    right.assign(static_cast<size_t>(channels), 0.0f);
    if (channels == 1) {
        left[0] = right[0] = 1.0f;
        return;
    }
    if (mask == 0) {
        // The usual order: FL FR FC LFE BL BR SL SR.
        static const uint32_t kDefault[8] = {kFrontLeft, kFrontRight, kFrontCenter, kLowFrequency,
                                             kBackLeft,  kBackRight,  kSideLeft,    kSideRight};
        for (int c = 0; c < channels; c++)
            gainsFor(c < 8 ? kDefault[c] : 0, left[static_cast<size_t>(c)], right[static_cast<size_t>(c)]);
        return;
    }
    // Channels come in the order of the bits set in the mask; any beyond the
    // mask's bits are unpositioned.
    int c = 0;
    for (uint32_t bit = 1; bit != 0 && c < channels; bit <<= 1) {
        if (mask & bit) {
            gainsFor(bit, left[static_cast<size_t>(c)], right[static_cast<size_t>(c)]);
            c++;
        }
    }
    for (; c < channels; c++) gainsFor(0, left[static_cast<size_t>(c)], right[static_cast<size_t>(c)]);
}

void AudioConverter::reset(const InputAudioFormat& in) {
    in_ = in;
    if (in_.channels < 1) in_.channels = 1;
    if (in_.rate < 1) in_.rate = kOutRate;
    downmixGains(in_.channels, in_.channelMask, gainL_, gainR_);
    pending_.assign(2, 0.0f);  // one silent frame of history
    pos_ = 1.0;
    step_ = static_cast<double>(in_.rate) / kOutRate;
}

float AudioConverter::sample(const uint8_t* p) const {
    switch (in_.sample) {
        case SampleFormat::Float32: {
            float f;
            std::memcpy(&f, p, 4);
            return std::isfinite(f) ? f : 0.0f;
        }
        case SampleFormat::Int16: {
            int16_t v;
            std::memcpy(&v, p, 2);
            return v / 32768.0f;
        }
        case SampleFormat::Int24: {
            int32_t v = static_cast<int32_t>(static_cast<uint32_t>(p[0]) << 8 | static_cast<uint32_t>(p[1]) << 16 |
                                             static_cast<uint32_t>(p[2]) << 24);
            return static_cast<float>(v / 2147483648.0);
        }
        case SampleFormat::Int32: {
            int32_t v;
            std::memcpy(&v, p, 4);
            return static_cast<float>(v / 2147483648.0);
        }
    }
    return 0.0f;
}

static int16_t toS16(float f) {
    float v = f * 32767.0f;
    if (v > 32767.0f) v = 32767.0f;
    if (v < -32768.0f) v = -32768.0f;
    return static_cast<int16_t>(std::lround(v));
}

void AudioConverter::process(const uint8_t* data, size_t frames, std::vector<int16_t>& out) {
    if (frames == 0) return;
    const int bytes = in_.sample == SampleFormat::Int16 ? 2 : in_.sample == SampleFormat::Int24 ? 3 : 4;
    // Container size per sample (24-bit samples may sit in 32-bit slots).
    const int slot = in_.blockAlign / in_.channels >= bytes ? in_.blockAlign / in_.channels : bytes;
    const bool passThrough = in_.rate == kOutRate;
    if (passThrough) out.reserve(out.size() + frames * 2);
    else pending_.reserve(pending_.size() + frames * 2);
    for (size_t f = 0; f < frames; f++) {
        float l = 0, r = 0;
        if (data) {
            const uint8_t* frame = data + f * static_cast<size_t>(in_.blockAlign);
            for (int c = 0; c < in_.channels; c++) {
                // A 24-bit sample in a 32-bit slot is in the slot's top bytes.
                float s = sample(frame + c * slot + (slot - bytes));
                l += s * gainL_[static_cast<size_t>(c)];
                r += s * gainR_[static_cast<size_t>(c)];
            }
        }
        if (passThrough) {
            out.push_back(toS16(l));
            out.push_back(toS16(r));
        } else {
            pending_.push_back(l);
            pending_.push_back(r);
        }
    }
    if (!passThrough) resample(out);
}

void AudioConverter::resample(std::vector<int16_t>& out) {
    // Cubic (Catmull-Rom) interpolation between the frames around each output
    // position; needs one frame before it and two after.
    const size_t n = pending_.size() / 2;
    auto at = [&](size_t i, int ch) { return pending_[i * 2 + static_cast<size_t>(ch)]; };
    while (true) {
        size_t i = static_cast<size_t>(pos_);
        if (i + 2 >= n || i < 1) break;
        float t = static_cast<float>(pos_ - static_cast<double>(i));
        for (int ch = 0; ch < 2; ch++) {
            float p0 = at(i - 1, ch), p1 = at(i, ch), p2 = at(i + 1, ch), p3 = at(i + 2, ch);
            float v = p1 + 0.5f * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
            out.push_back(toS16(v));
        }
        pos_ += step_;
    }
    // Keep the frame before the next position and everything after it.
    size_t keepFrom = static_cast<size_t>(pos_);
    keepFrom = keepFrom >= 1 ? keepFrom - 1 : 0;
    if (keepFrom > n) keepFrom = n;
    if (keepFrom > 0) {
        pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(keepFrom * 2));
        pos_ -= static_cast<double>(keepFrom);
    }
}

}  // namespace mango
