#include "protocol.h"

#include <cstdlib>

#include "json.h"

namespace mango {

void writeHeader(uint8_t* out, MsgType type, uint32_t length) {
    out[0] = static_cast<uint8_t>(type);
    out[1] = static_cast<uint8_t>(length >> 24);
    out[2] = static_cast<uint8_t>(length >> 16);
    out[3] = static_cast<uint8_t>(length >> 8);
    out[4] = static_cast<uint8_t>(length);
}

void writeU64(uint8_t* out, uint64_t v) {
    for (int i = 7; i >= 0; i--) {
        out[i] = static_cast<uint8_t>(v);
        v >>= 8;
    }
}

uint64_t readU64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | p[i];
    return v;
}

uint32_t readU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) << 24 | static_cast<uint32_t>(p[1]) << 16 | static_cast<uint32_t>(p[2]) << 8 |
           p[3];
}

std::string buildHello(const Hello& h) {
    std::string s = "{\"protocol\": " + std::to_string(kProtocolVersion);
    s += ", \"name\": " + json::quote(h.name);
    s += ", \"os\": " + json::quote(h.os);
    s += ", \"codecs\": [";
    for (size_t i = 0; i < h.codecs.size(); i++) {
        if (i) s += ", ";
        s += json::quote(codecName(h.codecs[i]));
    }
    s += "], \"width\": " + std::to_string(h.width);
    s += ", \"height\": " + std::to_string(h.height);
    s += ", \"fps\": " + std::to_string(h.fps);
    s += ", \"audio\": ";
    s += h.audio ? "true" : "false";
    s += "}";
    return s;
}

bool parseWelcome(const std::string& text, Welcome& out, std::string* error) {
    json::Value v;
    std::string why;
    if (!json::parse(text, v, &why) || !v.isObject()) {
        if (error) *error = "the receiver's WELCOME isn't valid JSON (" + why + ")";
        return false;
    }
    out = Welcome();
    out.name = v.getString("name");
    std::string codec = v.getString("codec");
    if (codec == "h264") {
        out.codec = Codec::H264;
    } else if (codec == "h265" || codec == "hevc") {
        out.codec = Codec::HEVC;
    } else {
        if (error) *error = "the receiver asked for a codec we don't send (\"" + codec + "\")";
        return false;
    }
    out.maxWidth = static_cast<int>(v.getNumber("maxWidth", 0));
    out.maxHeight = static_cast<int>(v.getNumber("maxHeight", 0));
    out.fps = static_cast<int>(v.getNumber("fps", 60));
    if (out.fps < 1) out.fps = 1;
    if (out.maxWidth < 0) out.maxWidth = 0;
    if (out.maxHeight < 0) out.maxHeight = 0;
    const json::Value* a = v.get("audio");
    if (a && a->isObject()) {
        std::string format = a->getString("format");
        int rate = static_cast<int>(a->getNumber("rate", 0));
        int channels = static_cast<int>(a->getNumber("channels", 0));
        if (format == "pcm_s16le" && rate == 48000 && channels == 2) {
            out.audio = true;
        } else {
            out.audioProblem = "the receiver asked for sound as " + format + " " + std::to_string(rate) + " Hz " +
                               std::to_string(channels) + " channels; only pcm_s16le 48000 Hz 2 channels is sent";
        }
    }
    return true;
}

Refusal parseRefuse(const std::string& text) {
    Refusal r;
    json::Value v;
    if (json::parse(text, v) && v.isObject()) {
        r.reason = v.getString("reason");
        r.message = v.getString("message");
    }
    return r;
}

std::string describeRefusal(const Refusal& r) {
    std::string s = r.message;
    if (s.empty()) {
        if (r.reason == "busy") s = "Another device is already showing its screen on it.";
        else if (r.reason == "protocol") s = "It doesn't speak this version of Mango Cast or take our video.";
        else if (r.reason == "off") s = "Windows connections are turned off in its settings.";
        else s = "No reason given.";
    }
    if (!r.reason.empty()) s += " (" + r.reason + ")";
    return s;
}

bool parseHostPort(const std::string& text, std::string& host, int& port) {
    port = kDefaultPort;
    std::string portText;
    bool hasPort = false;
    if (text.empty()) return false;
    if (text[0] == '[') {
        size_t close = text.find(']');
        if (close == std::string::npos) return false;
        host = text.substr(1, close - 1);
        if (close + 1 < text.size()) {
            if (text[close + 1] != ':') return false;
            portText = text.substr(close + 2);
            hasPort = true;
        }
    } else {
        size_t colon = text.find(':');
        if (colon != std::string::npos && text.find(':', colon + 1) == std::string::npos) {
            host = text.substr(0, colon);
            portText = text.substr(colon + 1);
            hasPort = true;
        } else {
            host = text;  // a name, IPv4, or a bare IPv6 address
        }
    }
    if (host.empty()) return false;
    if (hasPort) {
        char* end = nullptr;
        long p = std::strtol(portText.c_str(), &end, 10);
        if (portText.empty() || !end || *end != '\0' || p < 1 || p > 65535) return false;
        port = static_cast<int>(p);
    }
    return true;
}

}  // namespace mango
