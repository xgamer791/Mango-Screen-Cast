// Mango Cast protocol version 1 (protocol/mango-cast.md): message framing,
// HELLO, WELCOME and REFUSE. Portable C++17.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "annexb.h"

namespace mango {

constexpr int kProtocolVersion = 1;
constexpr int kDefaultPort = 7270;
constexpr uint32_t kMaxPayload = 16u * 1024 * 1024;
constexpr size_t kHeaderSize = 5;

enum class MsgType : uint8_t {
    Hello = 0x01,
    Welcome = 0x02,
    Refuse = 0x03,
    Video = 0x10,
    Keyframe = 0x11,
    Audio = 0x20,
    Ping = 0x30,
    Pong = 0x31,
    Bye = 0x3F,
};

void writeHeader(uint8_t* out, MsgType type, uint32_t length);
void writeU64(uint8_t* out, uint64_t v);
uint64_t readU64(const uint8_t* p);
uint32_t readU32(const uint8_t* p);

struct Hello {
    std::string name;  // the computer's name
    std::string os;    // e.g. "Windows 11"
    std::vector<Codec> codecs;  // what we can encode, best first
    int width = 0, height = 0;  // the screen's size
    int fps = 60;               // the most we'll send
    bool audio = false;         // whether we'd send sound
};
std::string buildHello(const Hello& h);

struct Welcome {
    std::string name;  // the receiver's name
    Codec codec = Codec::H264;
    int maxWidth = 0, maxHeight = 0;
    int fps = 60;
    bool audio = false;        // send 48 kHz stereo s16le
    std::string audioProblem;  // set when it asked for sound we can't make
};
// False (and why in *error) when it isn't a WELCOME we can follow.
bool parseWelcome(const std::string& json, Welcome& out, std::string* error);

struct Refusal {
    std::string reason;   // busy, protocol, off, ...
    std::string message;  // in words a person can read
};
Refusal parseRefuse(const std::string& json);
std::string describeRefusal(const Refusal& r);

// "host", "host:port", "[v6]:port" or a bare IPv6 address; port 7270 when
// none is given.
bool parseHostPort(const std::string& text, std::string& host, int& port);

}  // namespace mango
