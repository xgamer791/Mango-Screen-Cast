// The PC's sound: WASAPI loopback of the default playback device, turned
// into 48 kHz stereo 16-bit PCM in 10 ms pieces. While capturing it plays
// silence on that device, so the loopback keeps running (and the receiver
// gets a steady stream) when nothing else is playing. It follows the default
// device when it changes, and simply waits when there's none.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>

namespace mango {

class LoopbackAudio {
public:
    // timeUs: when the first sample was captured (QPC clock).
    using Sink = std::function<void(int64_t timeUs, const int16_t* samples, size_t frames)>;

    static constexpr int kRate = 48000;
    static constexpr size_t kChunkFrames = 480;  // 10 ms

    // The default playback device's name, or false when there's none.
    static bool defaultDevice(std::string* name);

    ~LoopbackAudio();
    void start(Sink sink);
    void stop();

private:
    void run();
    bool captureOnce();  // until the device goes away, changes, or stop()

    Sink sink_;
    std::thread thread_;
    std::atomic<bool> stop_{false};
};

}  // namespace mango
