// Unit tests for the portable parts of the Windows sender. They build with
// any C++17 compiler:  g++ -std=c++17 -I../src tests/unit_tests.cpp src/core/*.cpp
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "core/annexb.h"
#include "core/audio_convert.h"
#include "core/json.h"
#include "core/options.h"
#include "core/protocol.h"
#include "core/stream_math.h"

using namespace mango;

static const double kPi = 3.14159265358979323846;

static int failures = 0;
static int checks = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        checks++;                                                            \
        if (!(cond)) {                                                       \
            failures++;                                                      \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
        }                                                                    \
    } while (0)

static std::vector<uint8_t> bytes(std::initializer_list<int> v) {
    std::vector<uint8_t> out;
    for (int b : v) out.push_back(static_cast<uint8_t>(b));
    return out;
}

static std::vector<int> types(Codec c, const std::vector<uint8_t>& au) {
    std::vector<int> t;
    for (auto& r : splitAnnexB(au.data(), au.size())) t.push_back(nalType(c, au.data() + r.offset, r.size));
    return t;
}

static void testJson() {
    json::Value v;
    std::string err;
    CHECK(json::parse(R"({"protocol": 1, "name": "Living Room é😀", "codec": "h265",
        "maxWidth": 3840, "maxHeight": 2160, "fps": 60, "extra": [1, 2, {"x": null}],
        "audio": {"format": "pcm_s16le", "rate": 48000, "channels": 2}, "ok": true})",
                      v, &err));
    CHECK(v.getNumber("protocol") == 1);
    CHECK(v.getString("name") == "Living Room \xc3\xa9\xf0\x9f\x98\x80");
    CHECK(v.getString("codec") == "h265");
    CHECK(v.getNumber("maxWidth") == 3840);
    CHECK(v.getBool("ok"));
    const json::Value* a = v.get("audio");
    CHECK(a && a->isObject() && a->getNumber("rate") == 48000);
    CHECK(json::parse(R"({"audio": null})", v) && v.get("audio") && v.get("audio")->isNull());
    CHECK(!json::parse("{\"a\": }", v, &err));
    CHECK(!json::parse("{\"a\": 1} x", v, &err));
    CHECK(!json::parse("\"abc", v, &err));
    CHECK(json::quote("a\"b\\c\n\x01") == "\"a\\\"b\\\\c\\n\\u0001\"");
}

static void testSplit() {
    auto au = bytes({0, 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 4, 5, 0, 0});
    auto nals = splitAnnexB(au.data(), au.size());
    CHECK(nals.size() == 4);
    CHECK(types(Codec::H264, au) == (std::vector<int>{9, 7, 8, 5}));
    CHECK(nals[1].size == 3);       // 67 01 02, the zero before the next start code isn't part of it
    CHECK(nals[3].size == 3);       // trailing zeros dropped
    CHECK(splitAnnexB(nullptr, 0).empty());
}

static void testFixerH264() {
    AccessUnitFixer fx(Codec::H264);
    std::vector<uint8_t> out;
    // AUD, SEI, SPS, PPS, IDR: AUD goes, SPS and PPS move to the front.
    auto key = bytes({0, 0, 0, 1, 0x09, 0x10, 0, 0, 0, 1, 0x06, 5, 0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE,
                      0, 0, 0, 1, 0x65, 0x88});
    auto r = fx.fix(key.data(), key.size(), out);
    CHECK(r.picture && r.key && !r.missingParameters);
    CHECK(types(Codec::H264, out) == (std::vector<int>{7, 8, 6, 5}));
    CHECK(out[0] == 0 && out[1] == 0 && out[2] == 0 && out[3] == 1);
    // A P picture with an AUD: just the slice.
    auto p = bytes({0, 0, 0, 1, 0x09, 0x30, 0, 0, 1, 0x41, 0x9A});
    r = fx.fix(p.data(), p.size(), out);
    CHECK(r.picture && !r.key);
    CHECK(types(Codec::H264, out) == (std::vector<int>{1}));
    // A bare IDR gets the remembered SPS and PPS.
    auto bare = bytes({0, 0, 0, 1, 0x65, 0x88, 0x84});
    r = fx.fix(bare.data(), bare.size(), out);
    CHECK(r.key && !r.missingParameters);
    CHECK(types(Codec::H264, out) == (std::vector<int>{7, 8, 5}));
    // Without any known, it says so.
    AccessUnitFixer fresh(Codec::H264);
    r = fresh.fix(bare.data(), bare.size(), out);
    CHECK(r.key && r.missingParameters);
    // ... and learns them from a sequence header.
    auto header = bytes({0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0xEE});
    fresh.learn(header.data(), header.size());
    CHECK(fresh.hasParameterSets());
    r = fresh.fix(bare.data(), bare.size(), out);
    CHECK(r.key && !r.missingParameters);
    CHECK(types(Codec::H264, out) == (std::vector<int>{7, 8, 5}));
    CHECK(out[4] == 0x67 && out[5] == 0x64);
}

static void testFixerHevc() {
    AccessUnitFixer fx(Codec::HEVC);
    std::vector<uint8_t> out;
    // AUD(35), VPS(32), SPS(33), PPS(34), prefix SEI(39), IDR_W_RADL(19)
    auto key = bytes({0, 0, 0, 1, 0x46, 0x01, 0x50, 0, 0, 0, 1, 0x40, 0x01, 0x0C, 0, 0, 0, 1, 0x42, 0x01, 0x01, 0, 0,
                      0, 1, 0x44, 0x01, 0xC1, 0, 0, 0, 1, 0x4E, 0x01, 0x05, 0, 0, 0, 1, 0x26, 0x01, 0xAF});
    auto r = fx.fix(key.data(), key.size(), out);
    CHECK(r.picture && r.key);
    CHECK(types(Codec::HEVC, out) == (std::vector<int>{32, 33, 34, 39, 19}));
    // SPS before VPS in the encoder output gets put in order.
    auto odd = bytes({0, 0, 0, 1, 0x42, 0x01, 0x02, 0, 0, 0, 1, 0x40, 0x01, 0x0D, 0, 0, 0, 1, 0x44, 0x01, 0xC2, 0, 0,
                      0, 1, 0x28, 0x01, 0xAF});  // ..., IDR_N_LP(20)
    r = fx.fix(odd.data(), odd.size(), out);
    CHECK(r.key);
    CHECK(types(Codec::HEVC, out) == (std::vector<int>{32, 33, 34, 20}));
    // TRAIL_R (1) is not a keyframe; CRA (21) is.
    auto trail = bytes({0, 0, 0, 1, 0x02, 0x01, 0xD0});
    r = fx.fix(trail.data(), trail.size(), out);
    CHECK(r.picture && !r.key);
    CHECK(types(Codec::HEVC, out) == (std::vector<int>{1}));
    auto cra = bytes({0, 0, 0, 1, 0x2A, 0x01, 0xAF});
    r = fx.fix(cra.data(), cra.size(), out);
    CHECK(r.key && !r.missingParameters);
    CHECK(types(Codec::HEVC, out) == (std::vector<int>{32, 33, 34, 21}));
    CHECK(out[4 + 2] == 0x0D);  // the newest VPS
}

static void testFit() {
    CHECK(fitInside({2560, 1440}, {3840, 2160}) == (Size{2560, 1440}));
    CHECK(fitInside({2560, 1440}, {1920, 1080}) == (Size{1920, 1080}));
    CHECK(fitInside({3840, 2160}, {1920, 1080}) == (Size{1920, 1080}));
    CHECK(fitInside({1366, 768}, {640, 360}) == (Size{640, 358}));
    CHECK(fitInside({1366, 768}, {683, 384}) == (Size{682, 384}));
    CHECK(fitInside({1024, 768}, {640, 360}) == (Size{480, 360}));
    CHECK(fitInside({1080, 1920}, {1920, 1080}) == (Size{606, 1080}));
    CHECK(fitInside({1365, 767}, {3840, 2160}) == (Size{1364, 766}));
    CHECK(fitInside({3440, 1440}, {1920, 1080}) == (Size{1920, 802}));
    Size s = fitInside({5120, 2880}, {3840, 2160});
    CHECK(s.width % 2 == 0 && s.height % 2 == 0 && s.width <= 3840 && s.height <= 2160);
}

static void testBitrate() {
    double b1080 = defaultBitrate({1920, 1080}, 60);
    double b1440 = defaultBitrate({2560, 1440}, 60);
    double b4k = defaultBitrate({3840, 2160}, 60);
    CHECK(b1080 == 16e6);
    CHECK(b1440 >= 23e6 && b1440 <= 26e6);
    CHECK(b4k >= 38e6 && b4k <= 44e6);
    CHECK(defaultBitrate({640, 360}, 30) == 2e6);
}

static void testCongestion() {
    CongestionControl cc;
    const double fps = 60, rate = 16e6;
    cc.reset(rate, fps, 0);
    const size_t frame = static_cast<size_t>(rate / 8 / fps);  // 33 kB
    bool key = false;
    // Calm: everything goes.
    CHECK(cc.beforeEncode(0, 0, 10, &key) && !key);
    CHECK(cc.shouldSend(false, frame, 5, 10));
    // A big keyframe that drains fast isn't congestion.
    CHECK(cc.beforeEncode(10 * frame, 10, 20, &key) && !key);
    // A backlog of more than 3 frames that has waited: drop and lower the bitrate.
    CHECK(!cc.beforeEncode(4 * frame, 80, 30, &key));
    CHECK(cc.dropping());
    CHECK(cc.takeBitrateChange() && cc.bitrate() < rate);
    CHECK(!cc.takeBitrateChange());
    CHECK(!cc.shouldSend(false, 4 * frame, 80, 31));
    CHECK(!cc.shouldSend(true, 4 * frame, 80, 31));  // nothing until the backlog clears
    CHECK(!cc.beforeEncode(2 * frame, 80, 40, &key));
    // Cleared: the next picture is encoded as a keyframe ...
    CHECK(cc.beforeEncode(0, 0, 50, &key) && key);
    // ... asked for once ...
    CHECK(cc.beforeEncode(0, 0, 60, &key) && !key);
    // ... and pictures the encoder still had (not keyframes) are dropped.
    CHECK(!cc.shouldSend(false, 0, 0, 60));
    CHECK(cc.shouldSend(true, 0, 0, 70));
    CHECK(cc.shouldSend(false, 0, 0, 80));
    // If the keyframe never comes it's asked for again after a second.
    cc.reset(rate, fps, 0);
    CHECK(!cc.beforeEncode(5 * frame, 100, 100, &key));
    CHECK(cc.beforeEncode(0, 0, 200, &key) && key);
    CHECK(cc.beforeEncode(0, 0, 900, &key) && !key);
    CHECK(cc.beforeEncode(0, 0, 1300, &key) && key);
    // The bitrate comes back up after a calm spell, step by step.
    cc.reset(rate, fps, 0);
    CHECK(!cc.beforeEncode(5 * frame, 100, 100, &key));
    double low = cc.bitrate();
    cc.takeBitrateChange();
    CHECK(cc.beforeEncode(0, 0, 200, &key));
    CHECK(cc.shouldSend(true, 0, 0, 210));
    CHECK(cc.beforeEncode(0, 0, 3000, &key) && cc.bitrate() == low);
    CHECK(cc.beforeEncode(0, 0, 4200, &key) && cc.bitrate() > low && cc.takeBitrateChange());
    for (double t = 4200; t < 60000; t += 100) cc.beforeEncode(0, 0, t, &key);
    CHECK(cc.bitrate() == rate);
    // It never goes below a floor.
    cc.reset(rate, fps, 0);
    for (int i = 0; i < 40; i++) {
        cc.beforeEncode(100 * frame, 1000, i * 10.0, &key);  // drop
        cc.beforeEncode(0, 0, i * 10.0 + 5, &key);           // clear
        cc.shouldSend(true, 0, 0, i * 10.0 + 6);
    }
    CHECK(cc.bitrate() >= 2.4e6 - 1 && cc.congestions() == 40);
}

static void testAudio() {
    AudioConverter conv;
    std::vector<int16_t> out;
    // 48 kHz float stereo passes straight through.
    InputAudioFormat f;
    f.sample = SampleFormat::Float32;
    f.channels = 2;
    f.rate = 48000;
    f.blockAlign = 8;
    conv.reset(f);
    float st[4] = {0.5f, -0.5f, 1.5f, -2.0f};
    conv.process(reinterpret_cast<uint8_t*>(st), 2, out);
    CHECK(out.size() == 4);
    CHECK(out[0] == 16384 && out[1] == -16384 && out[2] == 32767 && out[3] == -32768);
    // Silence.
    out.clear();
    conv.process(nullptr, 480, out);
    CHECK(out.size() == 960 && out[0] == 0 && out[959] == 0);

    // 5.1 (FL FR FC LFE BL BR) to stereo: centre in both, LFE dropped.
    out.clear();
    f.channels = 6;
    f.blockAlign = 24;
    f.channelMask = 0x3F;
    conv.reset(f);
    float six[6] = {0.2f, 0.0f, 0.4f, 0.9f, 0.0f, 0.1f};
    conv.process(reinterpret_cast<uint8_t*>(six), 1, out);
    CHECK(out.size() == 2);
    CHECK(std::abs(out[0] - std::lround((0.2 + 0.4 * 0.7071) * 32767)) <= 2);
    CHECK(std::abs(out[1] - std::lround((0.4 * 0.7071 + 0.1 * 0.7071) * 32767)) <= 2);

    // Mono 16-bit to both sides.
    out.clear();
    f.sample = SampleFormat::Int16;
    f.channels = 1;
    f.blockAlign = 2;
    f.channelMask = 0;
    conv.reset(f);
    int16_t mono[2] = {1000, -1000};
    conv.process(reinterpret_cast<uint8_t*>(mono), 2, out);
    CHECK((out == std::vector<int16_t>{1000, 1000, -1000, -1000}));

    // 24-bit in 32-bit containers.
    out.clear();
    f.sample = SampleFormat::Int24;
    f.channels = 2;
    f.blockAlign = 8;
    conv.reset(f);
    uint8_t s24[8] = {0, 0x00, 0x00, 0x40, 0, 0x00, 0x00, 0xC0};  // +0.5, -0.5
    conv.process(s24, 1, out);
    CHECK(out.size() == 2 && std::abs(out[0] - 16384) <= 1 && std::abs(out[1] + 16384) <= 1);

    // 44.1 kHz to 48 kHz: the right number of frames, in pieces of any size,
    // and a sine stays a sine.
    out.clear();
    f.sample = SampleFormat::Float32;
    f.channels = 2;
    f.blockAlign = 8;
    f.rate = 44100;
    conv.reset(f);
    std::vector<float> sine(44100 * 2);
    for (int i = 0; i < 44100; i++) sine[i * 2] = sine[i * 2 + 1] = static_cast<float>(0.5 * std::sin(2 * kPi * 1000 * i / 44100.0));
    size_t done = 0;
    size_t piece = 1;
    while (done < 44100) {
        size_t n = std::min(piece, static_cast<size_t>(44100) - done);
        conv.process(reinterpret_cast<uint8_t*>(sine.data() + done * 2), n, out);
        done += n;
        piece = piece * 3 % 997 + 1;
    }
    size_t frames = out.size() / 2;
    CHECK(frames >= 47990 && frames <= 48000);
    double err = 0;
    for (size_t i = 100; i < frames - 100; i++) {
        double want = 0.5 * std::sin(2 * kPi * 1000 * (static_cast<double>(i) + 1.0 - 48000.0 / 44100.0) / 48000.0) * 32767;
        // The output lags the input by one input frame (the history frame).
        err = std::max(err, std::abs(out[i * 2] - want));
    }
    CHECK(err < 200);  // well under 1% of full scale
    CHECK(out[2000] == out[2001]);
}

static void testProtocol() {
    uint8_t h[5];
    writeHeader(h, MsgType::Video, 0x01020304);
    CHECK(h[0] == 0x10 && h[1] == 1 && h[2] == 2 && h[3] == 3 && h[4] == 4);
    uint8_t ts[8];
    writeU64(ts, 0x0102030405060708ULL);
    CHECK(ts[0] == 1 && ts[7] == 8 && readU64(ts) == 0x0102030405060708ULL);
    CHECK(readU32(h + 1) == 0x01020304);

    Hello hello;
    hello.name = "DESKTOP-\"4F2K\"";
    hello.os = "Windows 11";
    hello.codecs = {Codec::HEVC, Codec::H264};
    hello.width = 2560;
    hello.height = 1440;
    hello.fps = 60;
    hello.audio = true;
    json::Value v;
    CHECK(json::parse(buildHello(hello), v));
    CHECK(v.getNumber("protocol") == 1 && v.getString("name") == "DESKTOP-\"4F2K\"");
    CHECK(v.get("codecs") && v.get("codecs")->array.size() == 2 && v.get("codecs")->array[0].string == "h265");
    CHECK(v.getNumber("width") == 2560 && v.getBool("audio"));

    Welcome w;
    std::string err;
    CHECK(parseWelcome(R"({"protocol":1,"name":"Living Room","codec":"h264","maxWidth":1920,"maxHeight":1080,
        "fps":30,"audio":{"format":"pcm_s16le","rate":48000,"channels":2}})",
                       w, &err));
    CHECK(w.name == "Living Room" && w.codec == Codec::H264 && w.maxWidth == 1920 && w.fps == 30 && w.audio);
    CHECK(parseWelcome(R"({"protocol":1,"codec":"h265","maxWidth":3840,"maxHeight":2160,"fps":60,"audio":null})",
                       w, &err));
    CHECK(w.codec == Codec::HEVC && !w.audio && w.audioProblem.empty());
    CHECK(parseWelcome(R"({"codec":"h264","maxWidth":1,"maxHeight":1,"fps":60,
        "audio":{"format":"pcm_f32le","rate":44100,"channels":2}})",
                       w, &err));
    CHECK(!w.audio && !w.audioProblem.empty());
    CHECK(!parseWelcome(R"({"codec":"vp9"})", w, &err) && !err.empty());
    CHECK(!parseWelcome("nope", w, &err));

    Refusal r = parseRefuse(R"({"reason":"busy","message":"Another device is mirroring."})");
    CHECK(r.reason == "busy" && r.message == "Another device is mirroring.");
    CHECK(describeRefusal(r).find("Another device is mirroring.") != std::string::npos);
    r = parseRefuse("garbage");
    CHECK(!describeRefusal(r).empty());

    std::string host;
    int port = 0;
    CHECK(parseHostPort("192.168.1.20", host, port) && host == "192.168.1.20" && port == 7270);
    CHECK(parseHostPort("tv.local:7300", host, port) && host == "tv.local" && port == 7300);
    CHECK(parseHostPort("[fe80::1%4]:7271", host, port) && host == "fe80::1%4" && port == 7271);
    CHECK(parseHostPort("fe80::1", host, port) && host == "fe80::1" && port == 7270);
    CHECK(!parseHostPort("tv:0", host, port));
    CHECK(!parseHostPort("tv:99999", host, port));
    CHECK(!parseHostPort("", host, port));
}

static void testOptions() {
    Options o;
    std::string err;
    CHECK(parseOptions({}, o, &err) && !o.commandLine());
    CHECK(parseOptions({"--log", "x.log"}, o, &err) && !o.commandLine() && o.logFile == "x.log");
    CHECK(parseOptions({"--list", "--wait", "1500"}, o, &err) && o.list && o.waitMs == 1500 && o.commandLine());
    CHECK(parseOptions({"--connect", "192.168.1.5:7300", "--seconds=10", "--codec", "h265", "--no-audio", "--fps",
                        "30", "--bitrate", "12.5", "--monitor", "2", "--encoder", "software"},
                       o, &err));
    CHECK(o.connect == "192.168.1.5:7300" && o.seconds == 10 && o.codec == "h265" && o.noAudio && o.fps == 30 &&
          o.bitrateMbps == 12.5 && o.monitor == 2 && o.encoder == "software");
    CHECK(parseOptions({"--connect", "tv", "--codec", "hevc"}, o, &err) && o.codec == "h265");
    CHECK(o.capture == "auto");
    CHECK(parseOptions({"--connect", "tv", "--capture", "dda"}, o, &err) && o.capture == "duplication");
    CHECK(!parseOptions({"--capture", "gdi"}, o, &err));
    CHECK(!parseOptions({"--codec", "vp9"}, o, &err) && !err.empty());
    CHECK(!parseOptions({"--fps", "0"}, o, &err));
    CHECK(!parseOptions({"--monitor", "1.5"}, o, &err));
    CHECK(!parseOptions({"--connect"}, o, &err));
    CHECK(!parseOptions({"--seconds", "5"}, o, &err));  // needs --connect
    CHECK(!parseOptions({"--frobnicate"}, o, &err) && err.find("--frobnicate") != std::string::npos);
    CHECK(!parseOptions({"--list=yes"}, o, &err));
    CHECK(!usage().empty());
}

int main() {
    testOptions();
    testJson();
    testSplit();
    testFixerH264();
    testFixerHevc();
    testFit();
    testBitrate();
    testCongestion();
    testAudio();
    testProtocol();
    std::printf("%d checks, %d failed\n", checks, failures);
    return failures ? 1 : 0;
}
