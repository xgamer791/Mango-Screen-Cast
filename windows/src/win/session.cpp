#include "session.h"

#include <windows.h>

#include <algorithm>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>

#include "audio.h"
#include "capture.h"
#include "convert.h"
#include "core/protocol.h"
#include "core/stream_math.h"
#include "d3d.h"
#include "discovery.h"
#include "duplication.h"
#include "encoder.h"
#include "log.h"
#include "monitors.h"
#include "net.h"
#include "util.h"

namespace mango {

namespace {

constexpr int kConnectTimeoutMs = 5000;
constexpr int kWelcomeTimeoutMs = 10000;
constexpr int kNameSearchMs = 3000;
constexpr int64_t kRepeatUs = 100000;        // a still screen is sent again 10 times a second
constexpr int64_t kKeyframeWaitUs = 1500000;  // an encoder that ignores a keyframe request is restarted
constexpr int64_t kStallUs = 2000000;         // an encoder that stops for this long is restarted
const char kDash[] = "\xE2\x80\x94";         // em dash
const char kTimes[] = "\xC3\x97";            // multiplication sign

const char* prettyCodec(Codec c) { return c == Codec::HEVC ? "HEVC" : "H.264"; }

std::string sizeText(Size s) { return std::to_string(s.width) + kTimes + std::to_string(s.height); }

struct Trial {
    bool ok = false;
    std::string why;
};

// Whether an encoder opens at all (at 1080p60), remembered per encoder.
Trial tryEncoder(const EncoderCandidate& c) {
    static std::mutex m;
    static std::map<std::string, Trial> known;
    const std::string key = c.name + "/" + std::to_string(c.adapter.HighPart) + ":" +
                            std::to_string(c.adapter.LowPart) + "/" + codecName(c.codec) + (c.hardware ? "/hw" : "/sw");
    {
        std::lock_guard<std::mutex> lock(m);
        auto it = known.find(key);
        if (it != known.end()) return it->second;
    }
    Trial t;
    D3DDevice dev;
    ID3D11Device* device = nullptr;
    if (c.hardware && createD3DDevice(c.hasAdapter ? &c.adapter : nullptr, false, dev, &t.why) &&
        Nv12Converter::canRenderNv12(dev.device.Get()))
        device = dev.device.Get();
    VideoEncoder e;
    VideoEncoder::Config cfg;
    cfg.codec = c.codec;
    cfg.size = {1920, 1080};
    cfg.fps = 60;
    cfg.bitrate = defaultBitrate(cfg.size, cfg.fps);
    t.ok = e.open(c, cfg, device, nullptr, &t.why);
    e.close();
    if (t.ok) t.why.clear();
    else LOG("Not using %s: %s", c.name.c_str(), t.why.c_str());
    std::lock_guard<std::mutex> lock(m);
    known[key] = t;
    return t;
}

bool encoderWorks(const EncoderCandidate& c) { return tryEncoder(c).ok; }

std::vector<EncoderCandidate> usableEncoders(Codec codec, bool hardware) {
    std::vector<EncoderCandidate> out;
    for (auto& c : findEncoders(codec, hardware))
        if (encoderWorks(c)) out.push_back(c);
    return out;
}

// The encoders to try for a codec, best first.
std::vector<EncoderCandidate> encodersFor(Codec codec, const std::string& mode) {
    std::vector<EncoderCandidate> list;
    if (mode != "software") list = usableEncoders(codec, true);
    // Software HEVC only when asked for by name (--encoder software); it's too slow otherwise.
    if (mode != "hardware" && (codec == Codec::H264 || mode == "software")) {
        auto sw = usableEncoders(codec, false);
        list.insert(list.end(), sw.begin(), sw.end());
    }
    return list;
}

}  // namespace

bool encoderUsable(const EncoderCandidate& candidate, std::string* why) {
    Trial t = tryEncoder(candidate);
    if (why) *why = t.why;
    return t.ok;
}

std::vector<Codec> encodableCodecs(const std::string& mode, const std::string& choice, std::string* why) {
    if (!startMediaFoundation(why)) return {};
    std::vector<Codec> list;
    bool hevc = !usableEncoders(Codec::HEVC, true).empty() && mode != "software";
    if (!hevc && choice == "h265" && mode != "hardware") hevc = !usableEncoders(Codec::HEVC, false).empty();
    bool h264 = !encodersFor(Codec::H264, mode).empty();
    if (hevc && choice != "h264") list.push_back(Codec::HEVC);
    if (h264 && choice != "h265") list.push_back(Codec::H264);
    if (list.empty() && why) {
        if (choice == "h265")
            *why = "This PC can't encode HEVC: there's no HEVC hardware encoder (try --codec h264).";
        else if (mode == "hardware")
            *why = "This PC has no hardware video encoder that works (try --encoder software).";
        else
            *why = "This PC has no H.264 or HEVC video encoder that works.";
    }
    return list;
}

namespace {

// The picture side of a session: capture, conversion, encoding, and the
// decisions about what to send.
class VideoSender {
public:
    VideoSender(Connection& conn, Codec codec, Size maxSize, int fps, double bitrateOverride, std::string mode,
                std::string captureMode, int64_t originUs)
        : captureMode_(std::move(captureMode)),
          conn_(conn),
          codec_(codec),
          maxSize_(maxSize),
          fps_(fps),
          bitrateOverride_(bitrateOverride),
          mode_(std::move(mode)),
          originUs_(originUs),
          fixer_(codec) {}

    ~VideoSender() {
        encoder_.close();
        capture_.stop();
        duplication_.stop();
    }

    bool setup(HMONITOR monitor, std::string* error) {
        candidates_ = encodersFor(codec_, mode_);
        if (candidates_.empty()) {
            if (error) *error = std::string("No ") + prettyCodec(codec_) + " encoder works on this PC.";
            return false;
        }
        // The Direct3D device goes on the adapter of the best encoder, so it
        // can take our textures directly.
        const EncoderCandidate& best = candidates_.front();
        if (!createD3DDevice(best.hardware && best.hasAdapter ? &best.adapter : nullptr, false, d3d_, error))
            return false;
        LOG("Graphics: %s%s", d3d_.adapterName.c_str(), d3d_.software ? " (drawn on the CPU)" : "");
        if (!converter_.init(d3d_.device.Get(), error)) return false;
        canRenderNv12_ = Nv12Converter::canRenderNv12(d3d_.device.Get());
        std::string why;
        useDuplication_ = captureMode_ == "duplication";
        if (captureMode_ == "auto" && !ScreenCapture::supported(&why)) {
            LOG("%s; using Desktop Duplication", why.c_str());
            useDuplication_ = true;
        }
        if (useDuplication_) return duplication_.start(d3d_.device.Get(), monitor, error);
        return capture_.start(d3d_.device.Get(), monitor, error);
    }

    void requestKeyframe() { keyRequested_ = true; }
    Size size() const { return size_; }
    std::string encoderName() const { return encoder_.name() + (encoder_.hardware() ? "" : " (software)"); }
    bool captureClosed() const { return useDuplication_ ? duplication_.closed() : capture_.closed(); }

    // One tick of the frame clock. False (and why) when it can't go on.
    bool tick(int64_t nowUs, std::string* error) {
        ScreenCapture::Frame frame;
        if (useDuplication_ ? duplication_.latest(frame) : capture_.latest(frame)) {
            Size out = fitInside(frame.content, maxSize_);
            if (out != size_ || !encoder_.isOpen()) {
                if (!openEncoder(out, error)) return false;
            }
            if (!converter_.convert(frame.texture.Get(), frame.content, error)) return false;
            if (converter_.output() == Nv12Converter::Output::Memory && !converter_.read(picture_, error)) return false;
            captureUs_ = frame.timeUs;
            havePicture_ = true;
            fresh_ = true;
        }
        if (keyRequested_.exchange(false)) {
            LOG("The receiver asked for a keyframe");
            wantKey_ = true;
        }
        if (!havePicture_ || !encoder_.isOpen()) return true;

        std::string why;
        if (encoder_.failed(&why)) return recover(why, error, true);
        if (keyAskedUs_ >= 0 && lastKeyOutUs_.load() < keyAskedUs_ && nowUs - keyAskedUs_ > kKeyframeWaitUs) {
            // It ignored the request: a fresh encoder starts with one.
            return recover("it didn't make a keyframe when asked", error, false);
        }
        if (submittedSinceOutput_.load() > std::max(30, 2 * fps_) && nowUs - lastOutputUs_.load() > kStallUs)
            return recover("it stopped giving pictures back", error, true);

        const bool repeat = !fresh_ && nowUs - lastEncodeUs_ >= kRepeatUs;
        if (!fresh_ && !repeat && !wantKey_) return true;

        bool congestionKey = false;
        bool go;
        double bitrate = 0, target = 0;
        bool bitrateChanged;
        {
            std::lock_guard<std::mutex> lock(ccMutex_);
            go = cc_.beforeEncode(conn_.queuedBytes(), conn_.oldestQueuedMs(), nowMs(), &congestionKey);
            bitrateChanged = cc_.takeBitrateChange();
            bitrate = cc_.bitrate();
            target = cc_.targetBitrate();
        }
        if (bitrateChanged) {
            encoder_.setBitrate(bitrate);
            LOG("Bitrate now %.1f Mbit/s (of %.1f)", bitrate / 1e6, target / 1e6);
        }
        if (!go) {
            fresh_ = false;  // this picture is dropped
            dropped_++;
            return true;
        }
        if (!encoder_.ready()) {
            // Busy: try again next tick, unless it has stopped asking altogether.
            if (notReadySinceUs_ < 0) notReadySinceUs_ = nowUs;
            if (nowUs - notReadySinceUs_ > kStallUs) return recover("it stopped asking for pictures", error, true);
            return true;
        }
        notReadySinceUs_ = -1;

        const bool key = wantKey_ || congestionKey;
        if (key && !encoder_.forcesKeyframes() && submittedSinceOpen_ > 0) {
            // It can't be told to make a keyframe: start a fresh one, which begins with one.
            if (!openEncoder(size_, error)) return false;
        }
        int64_t ts = (fresh_ ? captureUs_ : nowUs) - originUs_;
        if (ts <= lastTs_) ts = lastTs_ + 1;
        bool ok = encoder_.takesTextures()
                      ? encoder_.encodeTexture(converter_.current(), ts * 10, key, &why)
                      : encoder_.encodeMemory(picture_.data(), ts * 10, key, &why);
        if (!ok) {
            if (encoder_.ready() || !encoder_.isOpen()) return recover(why, error, true);
            return true;
        }
        if (submittedSinceOutput_++ == 0) lastOutputUs_ = nowUs;  // start the stall clock
        submittedSinceOpen_++;
        lastTs_ = ts;
        lastEncodeUs_ = nowUs;
        fresh_ = false;
        encoded_++;
        if (key) {
            wantKey_ = false;
            if (keyAskedUs_ < 0 || lastKeyOutUs_.load() >= keyAskedUs_) keyAskedUs_ = nowUs;
        }
        return true;
    }

    void logStats(double seconds) {
        double bitrate;
        {
            std::lock_guard<std::mutex> lock(ccMutex_);
            bitrate = cc_.bitrate();
        }
        LOG("Last %.0f s: %lld pictures sent (%lld keyframes, %.1f Mbit/s), %lld not sent, %lld skipped; "
            "queue %zu bytes; round trip %.1f ms; bitrate %.1f Mbit/s",
            seconds, static_cast<long long>(sent_.exchange(0)), static_cast<long long>(keys_.exchange(0)),
            static_cast<double>(sentBytes_.exchange(0)) * 8 / 1e6 / seconds,
            static_cast<long long>(notSent_.exchange(0)), static_cast<long long>(dropped_.exchange(0)),
            conn_.queuedBytes(), conn_.roundTripMs(), bitrate / 1e6);
        encoded_ = 0;
    }

private:
    bool openEncoder(Size size, std::string* error) {
        encoder_.close();
        // Every size change starts afresh with a keyframe carrying the new parameter sets.
        fixer_.forget();
        firstSent_ = false;
        std::string lastError;
        std::vector<Size> sizes = {size};
        if (!encoderTakes(size, false)) {
            // If no encoder takes the full size, or only the software one is
            // left, 1080p at most.
            Size smaller = fitInside(size, {1920, 1080});
            if (smaller != size) sizes.push_back(smaller);
        }
        for (Size s : sizes) {
            for (size_t i = 0; i < candidates_.size(); i++) {
                const EncoderCandidate& c = candidates_[i];
                if (!encoderTakes(s, c.hardware)) continue;
                VideoEncoder::Config cfg;
                cfg.codec = codec_;
                cfg.size = s;
                cfg.fps = fps_;
                cfg.bitrate = bitrateOverride_ > 0 ? bitrateOverride_ : defaultBitrate(s, fps_);
                // Textures only on our own adapter, when we can draw NV12.
                bool sameAdapter = !c.hasAdapter || sameLuid(c.adapter, d3d_.luid);
                cfg.forceTextures = testTextures_ && canRenderNv12_;
                ID3D11Device* device =
                    (c.hardware && sameAdapter && canRenderNv12_) || cfg.forceTextures ? d3d_.device.Get() : nullptr;
                std::string why;
                auto output = [this](const uint8_t* data, size_t n, int64_t time100ns, bool clean) {
                    onEncoded(data, n, time100ns, clean);
                };
                // A fresh activation object for each try.
                auto fresh = findEncoders(codec_, c.hardware);
                auto match = std::find_if(fresh.begin(), fresh.end(), [&](const EncoderCandidate& f) {
                    return f.name == c.name && f.hasAdapter == c.hasAdapter && sameLuid(f.adapter, c.adapter);
                });
                if (match == fresh.end()) continue;
                if (encoder_.open(*match, cfg, device, output, &why)) {
                    size_ = s;
                    auto header = encoder_.sequenceHeader();
                    if (!header.empty()) fixer_.learn(header.data(), header.size());
                    Nv12Converter::Output kind =
                        encoder_.takesTextures() ? Nv12Converter::Output::Textures : Nv12Converter::Output::Memory;
                    if (!converter_.configure(s, kind, error)) return false;
                    {
                        std::lock_guard<std::mutex> lock(ccMutex_);
                        cc_.reset(cfg.bitrate, fps_, nowMs());
                    }
                    wantKey_ = true;
                    keyAskedUs_ = -1;
                    notReadySinceUs_ = -1;
                    submittedSinceOpen_ = 0;
                    submittedSinceOutput_ = 0;
                    if (s != size)
                        LOG("Sending %s instead of %s: %s", sizeText(s).c_str(), sizeText(size).c_str(),
                            c.hardware ? "no encoder takes the full size" : "the software encoder sends at most 1080p");
                    return true;
                }
                lastError = why;
                LOG("Couldn't open %s: %s", c.name.c_str(), why.c_str());
            }
        }
        if (error) *error = "Couldn't start the video encoder: " + lastError;
        return false;
    }

    // The encoder failed or stopped listening: open it again (the next
    // encoder if this one keeps failing; failures a minute apart are forgiven).
    bool recover(const std::string& why, std::string* error, bool failure) {
        LOG("Restarting the encoder: %s", why.c_str());
        if (failure) {
            const double now = nowMs();
            if (now - lastFailureMs_ > 60000) failures_ = 0;
            lastFailureMs_ = now;
            if (++failures_ > 3 && candidates_.size() > 1) {
                LOG("Giving up on %s", candidates_.front().name.c_str());
                candidates_.erase(candidates_.begin());
                failures_ = 0;
            } else if (failures_ > 6) {
                if (error) *error = "The video encoder keeps failing: " + why;
                return false;
            }
        }
        return openEncoder(size_, error);
    }

    // Runs on the encoder's thread (or inside encode..() for a synchronous one).
    void onEncoded(const uint8_t* data, size_t n, int64_t time100ns, bool) {
        submittedSinceOutput_ = 0;
        lastOutputUs_ = qpcNowUs();
        AccessUnitFixer::Result r = fixer_.fix(data, n, au_);
        if (!r.picture) return;
        if (r.key) lastKeyOutUs_ = qpcNowUs();
        if (r.key && r.missingParameters) {
            LOG("A keyframe came without parameter sets and none are known; asking for another");
            keyRequested_ = true;
            return;
        }
        if (!firstSent_ && !r.key) return;  // the first picture sent is a keyframe
        bool send;
        {
            std::lock_guard<std::mutex> lock(ccMutex_);
            send = cc_.shouldSend(r.key, conn_.queuedBytes(), conn_.oldestQueuedMs(), nowMs());
        }
        if (!send) {
            notSent_++;
            return;
        }
        uint8_t ts[8];
        writeU64(ts, static_cast<uint64_t>(std::max<int64_t>(0, time100ns / 10)));
        conn_.send(MsgType::Video, ts, 8, au_.data(), au_.size());
        firstSent_ = true;
        sent_++;
        sentBytes_ += static_cast<long long>(au_.size());
        if (r.key) keys_++;
    }

    std::string captureMode_;
    Connection& conn_;
    Codec codec_;
    Size maxSize_;
    int fps_;
    double bitrateOverride_;
    std::string mode_;
    int64_t originUs_;

    D3DDevice d3d_;
    ScreenCapture capture_;
    DesktopDuplication duplication_;
    bool useDuplication_ = false;
    Nv12Converter converter_;
    VideoEncoder encoder_;
    std::vector<EncoderCandidate> candidates_;
    bool canRenderNv12_ = false;
    // MANGO_TEST_TEXTURES=1: the hardware encoders' texture path, with any encoder (for CI).
    const bool testTextures_ = GetEnvironmentVariableW(L"MANGO_TEST_TEXTURES", nullptr, 0) > 0;
    Size size_;
    std::vector<uint8_t> picture_;  // NV12 in memory (software path)
    int64_t captureUs_ = 0, lastEncodeUs_ = 0, lastTs_ = -1;
    bool havePicture_ = false, fresh_ = false, wantKey_ = true;
    std::atomic<bool> keyRequested_{false};
    int64_t keyAskedUs_ = -1;
    std::atomic<int64_t> lastKeyOutUs_{-1};
    std::atomic<int64_t> lastOutputUs_{0};
    std::atomic<int> submittedSinceOutput_{0};
    int64_t notReadySinceUs_ = -1;
    int submittedSinceOpen_ = 0;
    int failures_ = 0;
    double lastFailureMs_ = 0;

    // Used by onEncoded.
    AccessUnitFixer fixer_;
    std::vector<uint8_t> au_;
    std::atomic<bool> firstSent_{false};
    std::mutex ccMutex_;
    CongestionControl cc_;

    std::atomic<long long> sent_{0}, keys_{0}, sentBytes_{0}, notSent_{0}, dropped_{0}, encoded_{0};
};

}  // namespace

SessionResult Session::run(const SessionOptions& opt, StatusFn status, std::string* message) {
    auto report = [&](SessionStatus::State state, const std::string& text, SessionResult result = SessionResult::Done) {
        SessionStatus s;
        s.state = state;
        s.text = text;
        s.result = result;
        if (status) status(s);
    };
    auto finish = [&](SessionResult result, const std::string& text) {
        if (message) *message = text;
        LOG("%s", text.c_str());
        report(SessionStatus::State::Ended, text, result);
        return result;
    };

    std::string targetName = opt.targetName.empty() ? opt.target : opt.targetName;
    report(SessionStatus::State::Connecting, "Connecting to " + targetName + "...");

    // What we can do, checked before connecting.
    std::string why;
    if (!startWinsock(&why) || !startMediaFoundation(&why)) return finish(SessionResult::Error, why);
    if (opt.capture == "wgc" && !ScreenCapture::supported(&why))
        return finish(SessionResult::Error, "Can't capture the screen: " + why + " (try --capture duplication)");
    auto monitors = listMonitors();
    if (monitors.empty()) return finish(SessionResult::Error, "No screen to capture.");
    const MonitorInfo* monitor = &monitors.front();
    if (opt.monitor > 0) {
        if (opt.monitor > static_cast<int>(monitors.size()))
            return finish(SessionResult::Error, "There's no screen " + std::to_string(opt.monitor) + " (this PC has " +
                                                    std::to_string(monitors.size()) + ").");
        monitor = &monitors[static_cast<size_t>(opt.monitor - 1)];
    }
    std::vector<Codec> codecs = encodableCodecs(opt.encoder, opt.codec, &why);
    if (codecs.empty()) return finish(SessionResult::Error, why);
    std::string soundDevice;
    bool haveSound = opt.audio && LoopbackAudio::defaultDevice(&soundDevice);
    if (opt.audio && !haveSound) LOG("No playback device: no sound will be sent");

    // Connect: an address, or a receiver's name looked up on the network.
    Connection conn;
    std::string host;
    int port = kDefaultPort;
    bool notFound = false;
    std::string connectError;
    bool connected = parseHostPort(opt.target, host, port) &&
                     conn.connect(host, port, kConnectTimeoutMs, &connectError, &notFound);
    if (!connected && (notFound || connectError.empty()) && !stop_) {
        LOG("Looking for a receiver called \"%s\"", opt.target.c_str());
        for (const auto& r : Browser::browseFor(kNameSearchMs, nullptr)) {
            if (_stricmp(r.name.c_str(), opt.target.c_str()) == 0 || (!r.id().empty() && r.id() == opt.target)) {
                if (parseHostPort(r.target(), host, port) &&
                    conn.connect(host, port, kConnectTimeoutMs, &connectError, nullptr)) {
                    connected = true;
                    if (opt.targetName.empty()) targetName = r.name;
                }
                break;
            }
        }
        if (!connected && notFound) connectError = "Couldn't find \"" + opt.target + "\" by address or by name.";
    }
    if (!connected) return finish(SessionResult::ConnectionFailed, connectError.empty() ? "Couldn't connect." : connectError);

    Hello hello;
    hello.name = computerName();
    hello.os = windowsName();
    hello.codecs = codecs;
    hello.width = monitor->width();
    hello.height = monitor->height();
    hello.fps = opt.fps;
    hello.audio = haveSound;
    std::string helloJson = buildHello(hello);
    // An extra field (allowed by the protocol) saying what's sending.
    helloJson.insert(helloJson.size() - 1, ", \"app\": \"Mango Cast for Windows " MANGO_VERSION "\"");
    LOG("HELLO %s", helloJson.c_str());
    if (!conn.sendNow(MsgType::Hello, helloJson, &why)) return finish(SessionResult::ConnectionFailed, why);

    Welcome welcome;
    double deadline = nowMs() + kWelcomeTimeoutMs;
    for (;;) {
        MsgType type;
        std::vector<uint8_t> payload;
        int left = static_cast<int>(deadline - nowMs());
        if (left <= 0 || stop_) {
            return finish(SessionResult::ConnectionFailed,
                          stop_ ? "Stopped." : "The receiver didn't answer (no WELCOME within 10 seconds).");
        }
        Connection::Read r = conn.readMessage(type, payload, left, &why);
        if (r == Connection::Read::Timeout) continue;
        if (r != Connection::Read::Ok) return finish(SessionResult::ConnectionFailed, why);
        std::string text(payload.begin(), payload.end());
        if (type == MsgType::Refuse) {
            LOG("REFUSE %s", text.c_str());
            std::string who = opt.targetName.empty() && targetName == opt.target ? "The receiver" : targetName;
            return finish(SessionResult::Refused, who + " said no: " + describeRefusal(parseRefuse(text)));
        }
        if (type == MsgType::Ping) {
            conn.sendNow(MsgType::Pong, text, nullptr);
            continue;
        }
        if (type == MsgType::Bye)
            return finish(SessionResult::ReceiverEnded, "The receiver ended the session before it began" +
                                                            (text.empty() ? std::string(".") : ": " + text));
        if (type != MsgType::Welcome) continue;  // nothing else should come first; skip it
        LOG("WELCOME %s", text.c_str());
        if (!parseWelcome(text, welcome, &why)) {
            conn.sendNow(MsgType::Bye, "protocol", nullptr);
            return finish(SessionResult::Error, "Can't follow the receiver: " + why);
        }
        break;
    }
    if (std::find(codecs.begin(), codecs.end(), welcome.codec) == codecs.end()) {
        conn.sendNow(MsgType::Bye, "protocol", nullptr);
        return finish(SessionResult::Error, std::string("The receiver asked for ") + codecName(welcome.codec) +
                                                ", which wasn't offered.");
    }
    if (!welcome.name.empty()) targetName = welcome.name;
    if (!welcome.audioProblem.empty()) LOG("No sound: %s", welcome.audioProblem.c_str());
    const bool sound = haveSound && welcome.audio;
    if (haveSound && !welcome.audio && welcome.audioProblem.empty()) LOG("The receiver's sound is off: picture only");
    const int fps = std::max(1, std::min(opt.fps, welcome.fps));
    const Size maxSize{welcome.maxWidth, welcome.maxHeight};
    const int64_t originUs = qpcNowUs();

    VideoSender video(conn, welcome.codec, maxSize, fps, opt.bitrateMbps * 1e6, opt.encoder, opt.capture, originUs);
    conn.start([&](MsgType type, const std::vector<uint8_t>&) {
        if (type == MsgType::Keyframe) video.requestKeyframe();
    });
    if (!video.setup(monitor->handle, &why)) {
        conn.bye("error", 2000);
        conn.close();  // its reader thread uses `video`
        return finish(SessionResult::Error, why);
    }

    LoopbackAudio audio;
    if (sound) {
        audio.start([&](int64_t timeUs, const int16_t* samples, size_t frames) {
            uint8_t ts[8];
            writeU64(ts, static_cast<uint64_t>(std::max<int64_t>(0, timeUs - originUs)));
            conn.send(MsgType::Audio, ts, 8, reinterpret_cast<const uint8_t*>(samples), frames * 4);
        });
    }

    // The frame clock.
    PreciseSleeper sleeper;
    const int64_t intervalUs = 1000000 / fps;
    const int64_t startUs = qpcNowUs();
    int64_t next = startUs;
    double lastStats = nowMs();
    bool announced = false;
    SessionResult result = SessionResult::Done;
    std::string endText;
    for (;;) {
        int64_t now = qpcNowUs();
        if (next > now) sleeper.sleepUs(next - now);
        now = qpcNowUs();
        next += intervalUs;
        if (next < now - intervalUs) next = now;  // fell behind: don't try to catch up

        std::string reason;
        if (stop_) {
            endText = "Stopped.";
            break;
        }
        if (opt.seconds > 0 && now - startUs >= static_cast<int64_t>(opt.seconds * 1e6)) {
            endText = "Done: streamed for " + std::to_string(static_cast<int>(opt.seconds)) + " seconds.";
            break;
        }
        if (conn.receiverSaidBye(&reason)) {
            result = SessionResult::ReceiverEnded;
            endText = targetName + " ended the session" + (reason.empty() ? std::string(".") : ": " + reason);
            break;
        }
        if (conn.ended(&reason)) {
            result = SessionResult::ConnectionFailed;
            endText = "Lost the connection to " + targetName + ": " + reason;
            break;
        }
        if (video.captureClosed()) {
            result = SessionResult::Error;
            endText = "The screen being shared went away.";
            break;
        }
        if (!video.tick(now, &why)) {
            result = SessionResult::Error;
            endText = why;
            break;
        }
        if (!announced && video.size().width > 0) {
            announced = true;
            std::string text = "Streaming to " + targetName + " " + kDash + " " + sizeText(video.size()) + " " +
                               prettyCodec(welcome.codec) + " " + std::to_string(fps) + " fps" +
                               (sound ? ", with sound" : "");
            LOG("%s (encoder: %s)", text.c_str(), video.encoderName().c_str());
            report(SessionStatus::State::Streaming, text);
        }
        if (nowMs() - lastStats >= 5000) {
            video.logStats((nowMs() - lastStats) / 1000.0);
            lastStats = nowMs();
        }
    }
    audio.stop();
    if (result == SessionResult::Done) {
        conn.bye(stop_ ? "stopped" : "done", 2000);
    } else if (result == SessionResult::Error) {
        conn.bye("error", 2000);
    }
    conn.close();
    return finish(result, endText);
}

}  // namespace mango
