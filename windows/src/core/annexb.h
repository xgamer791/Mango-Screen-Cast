// Annex B access units: splitting into NAL units, and putting an encoder's
// output into the form the Mango Cast protocol asks for (no access unit
// delimiters, parameter sets at the front of every keyframe). Portable C++17.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace mango {

enum class Codec { H264, HEVC };

const char* codecName(Codec c);  // "h264" / "h265"

struct NalRange {
    size_t offset;  // first byte of the NAL header, after the start code
    size_t size;    // without the start code and trailing zero bytes
};

// The NAL units of an Annex B byte stream (3- or 4-byte start codes).
std::vector<NalRange> splitAnnexB(const uint8_t* data, size_t size);

int nalType(Codec codec, const uint8_t* nal, size_t size);
bool isAccessUnitDelimiter(Codec codec, int type);
bool isParameterSet(Codec codec, int type);  // H.264 SPS/PPS; HEVC VPS/SPS/PPS
bool isPicture(Codec codec, int type);       // a coded slice (VCL NAL unit)
bool isKeyPicture(Codec codec, int type);    // H.264 IDR; HEVC IRAP (BLA, IDR, CRA)

class AccessUnitFixer {
public:
    explicit AccessUnitFixer(Codec codec) : codec_(codec) {}

    Codec codec() const { return codec_; }

    // Remembers the parameter sets in an Annex B blob (e.g. the encoder's
    // MF_MT_MPEG_SEQUENCE_HEADER), for keyframes that come without them.
    void learn(const uint8_t* data, size_t size);

    // Rewrites one encoded picture: drops access unit delimiters, puts the
    // parameter sets first (in VPS, SPS, PPS order) and, on a keyframe that
    // has none, the last ones seen. Every NAL unit gets a 4-byte start code.
    struct Result {
        bool picture = false;          // there was a coded picture in it
        bool key = false;              // ... and it's a keyframe
        bool missingParameters = false;  // a keyframe, but no parameter sets known
    };
    Result fix(const uint8_t* data, size_t size, std::vector<uint8_t>& out);

    bool hasParameterSets() const;
    void forget();

private:
    void remember(int type, const uint8_t* nal, size_t size);
    Codec codec_;
    std::vector<uint8_t> vps_, sps_, pps_;
};

}  // namespace mango
