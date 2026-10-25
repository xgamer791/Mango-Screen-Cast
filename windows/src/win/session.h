// One streaming session: connect, HELLO, WELCOME (or REFUSE), then the screen
// and sound until stopped, the time is up, or the receiver goes.
#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <vector>

#include "core/annexb.h"

namespace mango {

struct SessionOptions {
    std::string target;      // host[:port], or a receiver's name or id
    std::string targetName;  // what to call it until WELCOME says (optional)
    int monitor = 0;         // 0: the main screen; else its number (1, 2, ...)
    std::string codec;       // "" for the best one, "h264" or "h265"
    bool audio = true;
    int fps = 60;
    double bitrateMbps = 0;  // 0: chosen from the picture size
    std::string encoder = "auto";  // auto, hardware, software
    double seconds = 0;      // 0: until stopped
};

// Also the command line's exit codes.
enum class SessionResult { Done = 0, Error = 1, Refused = 3, ConnectionFailed = 4, ReceiverEnded = 5 };

struct SessionStatus {
    enum class State { Connecting, Streaming, Ended };
    State state = State::Connecting;
    std::string text;  // for the status line
    SessionResult result = SessionResult::Done;
};

struct EncoderCandidate;
// Whether an encoder opens and takes 1080p60 NV12 (tried once, remembered).
bool encoderUsable(const EncoderCandidate& candidate, std::string* why);

// What this PC can send, best first, honouring --codec and --encoder.
std::vector<Codec> encodableCodecs(const std::string& encoderMode, const std::string& codecChoice, std::string* why);

class Session {
public:
    using StatusFn = std::function<void(const SessionStatus&)>;

    // Runs on the calling thread until the session is over.
    SessionResult run(const SessionOptions& options, StatusFn status, std::string* message);
    // From any thread: end the session (with BYE).
    void stop() { stop_ = true; }

private:
    std::atomic<bool> stop_{false};
};

}  // namespace mango
