// Picture size, bitrate and congestion decisions. Portable C++17.
#pragma once

#include <cstddef>
#include <cstdint>

namespace mango {

struct Size {
    int width = 0;
    int height = 0;
    bool operator==(const Size& o) const { return width == o.width && height == o.height; }
    bool operator!=(const Size& o) const { return !(*this == o); }
};

// The largest size with the source's shape that fits in max (never larger
// than the source), both sides rounded down to even numbers, at least 2.
Size fitInside(Size source, Size max);

// A sensible constant bitrate, in bits per second, for a picture size and
// frame rate: about 16 Mbit/s for 1080p60, 25 for 1440p60, 40 for 4K60.
double defaultBitrate(Size size, double fps);

// Decides, picture by picture, what to send when the network can't keep up:
// when the unsent data is more than about 3 frames' worth (and has waited
// that long), it drops pictures until the backlog clears, then asks for a
// keyframe to start again from, and lowers the bitrate; after a calm spell it
// raises the bitrate back step by step.
class CongestionControl {
public:
    void reset(double targetBitrate, double fps, double nowMs);

    // Before encoding a picture: false to skip it (we're waiting for the
    // backlog to clear). When it returns true and *keyframe is set, encode
    // that picture as a keyframe.
    bool beforeEncode(size_t queuedBytes, double oldestQueuedMs, double nowMs, bool* keyframe);

    // After encoding: whether to send this picture.
    bool shouldSend(bool key, size_t queuedBytes, double oldestQueuedMs, double nowMs);

    double bitrate() const { return bitrate_; }
    double targetBitrate() const { return target_; }
    // Set when the bitrate changed since the last call.
    bool takeBitrateChange();
    bool dropping() const { return dropping_; }
    int congestions() const { return congestions_; }

private:
    bool congested(size_t queuedBytes, double oldestQueuedMs) const;
    double frameBytes() const { return bitrate_ / 8.0 / fps_; }
    void lower(double nowMs);

    double target_ = 0, bitrate_ = 0, fps_ = 60;
    double lastCongestionMs_ = 0, lastRaiseMs_ = 0, keyAskedMs_ = -1;
    bool dropping_ = false;
    bool needKey_ = false;
    bool changed_ = false;
    int congestions_ = 0;
};

}  // namespace mango
