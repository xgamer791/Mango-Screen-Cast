#include "stream_math.h"

#include <algorithm>
#include <cmath>

namespace mango {

Size fitInside(Size source, Size max) {
    if (source.width <= 0 || source.height <= 0) return {2, 2};
    double scale = 1.0;
    if (max.width > 0) scale = std::min(scale, static_cast<double>(max.width) / source.width);
    if (max.height > 0) scale = std::min(scale, static_cast<double>(max.height) / source.height);
    // A hair of tolerance, so 1366 x 768 into 683 x 384 isn't one pixel short.
    int w = static_cast<int>(std::floor(source.width * scale + 1e-6));
    int h = static_cast<int>(std::floor(source.height * scale + 1e-6));
    if (max.width > 0) w = std::min(w, max.width);
    if (max.height > 0) h = std::min(h, max.height);
    w &= ~1;
    h &= ~1;
    return {std::max(w, 2), std::max(h, 2)};
}

double defaultBitrate(Size size, double fps) {
    double rate = static_cast<double>(size.width) * size.height * std::max(fps, 1.0) / (1920.0 * 1080.0 * 60.0);
    double mbps = 16.0 * std::pow(std::max(rate, 1e-3), 0.7);
    mbps = std::clamp(mbps, 2.0, 60.0);
    return std::round(mbps * 2.0) / 2.0 * 1e6;
}

void CongestionControl::reset(double targetBitrate, double fps, double nowMs) {
    target_ = bitrate_ = targetBitrate;
    fps_ = fps > 0 ? fps : 60;
    lastCongestionMs_ = lastRaiseMs_ = nowMs;
    dropping_ = needKey_ = changed_ = false;
    keyAskedMs_ = -1;
    congestions_ = 0;
}

bool CongestionControl::congested(size_t queuedBytes, double oldestQueuedMs) const {
    double frameMs = 1000.0 / fps_;
    return static_cast<double>(queuedBytes) > 3.0 * frameBytes() && oldestQueuedMs > std::max(3.0 * frameMs, 40.0);
}

void CongestionControl::lower(double nowMs) {
    congestions_++;
    lastCongestionMs_ = lastRaiseMs_ = nowMs;
    double floor = std::max(1e6, target_ * 0.15);
    double next = std::max(floor, bitrate_ * 0.7);
    if (next < bitrate_) {
        bitrate_ = next;
        changed_ = true;
    }
}

bool CongestionControl::beforeEncode(size_t queuedBytes, double oldestQueuedMs, double nowMs, bool* keyframe) {
    *keyframe = false;
    if (dropping_) {
        // Wait until about one frame is left, then start again from a keyframe.
        if (static_cast<double>(queuedBytes) > frameBytes()) return false;
        dropping_ = false;
        needKey_ = true;
    } else if (congested(queuedBytes, oldestQueuedMs)) {
        dropping_ = true;
        lower(nowMs);
        return false;
    } else if (bitrate_ < target_ && nowMs - lastCongestionMs_ > 4000 && nowMs - lastRaiseMs_ > 4000) {
        bitrate_ = std::min(target_, bitrate_ * 1.15);
        lastRaiseMs_ = nowMs;
        changed_ = true;
    }
    if (needKey_ && (keyAskedMs_ < 0 || nowMs - keyAskedMs_ > 1000)) {
        // Ask once; ask again only if it hasn't come out after a while.
        *keyframe = true;
        keyAskedMs_ = nowMs;
    }
    return true;
}

bool CongestionControl::shouldSend(bool key, size_t queuedBytes, double oldestQueuedMs, double nowMs) {
    if (dropping_) {
        return false;
    }
    if (needKey_) {
        // Pictures still coming out of the encoder from before the drop
        // can't be decoded; wait for the keyframe.
        if (!key) return false;
        needKey_ = false;
        keyAskedMs_ = -1;
        return true;
    }
    if (!key && congested(queuedBytes, oldestQueuedMs)) {
        dropping_ = true;
        lower(nowMs);
        return false;
    }
    return true;
}

bool CongestionControl::takeBitrateChange() {
    bool c = changed_;
    changed_ = false;
    return c;
}

}  // namespace mango
