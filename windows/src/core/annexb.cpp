#include "annexb.h"

namespace mango {

const char* codecName(Codec c) { return c == Codec::HEVC ? "h265" : "h264"; }

std::vector<NalRange> splitAnnexB(const uint8_t* data, size_t size) {
    std::vector<NalRange> nals;
    size_t i = 0;
    size_t start = SIZE_MAX;  // payload start of the NAL unit being read
    while (i + 3 <= size) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            if (start != SIZE_MAX) {
                size_t end = i;
                while (end > start && data[end - 1] == 0) end--;
                if (end > start) nals.push_back({start, end - start});
            }
            i += 3;
            start = i;
        } else {
            i++;
        }
    }
    if (start != SIZE_MAX) {
        size_t end = size;
        while (end > start && data[end - 1] == 0) end--;
        if (end > start) nals.push_back({start, end - start});
    }
    return nals;
}

int nalType(Codec codec, const uint8_t* nal, size_t size) {
    if (size == 0) return -1;
    return codec == Codec::H264 ? (nal[0] & 0x1F) : ((nal[0] >> 1) & 0x3F);
}

bool isAccessUnitDelimiter(Codec codec, int type) { return codec == Codec::H264 ? type == 9 : type == 35; }

bool isParameterSet(Codec codec, int type) {
    return codec == Codec::H264 ? (type == 7 || type == 8) : (type >= 32 && type <= 34);
}

bool isPicture(Codec codec, int type) {
    return codec == Codec::H264 ? (type >= 1 && type <= 5) : (type >= 0 && type <= 31);
}

bool isKeyPicture(Codec codec, int type) {
    return codec == Codec::H264 ? type == 5 : (type >= 16 && type <= 23);
}

static void appendNal(std::vector<uint8_t>& out, const uint8_t* nal, size_t size) {
    static const uint8_t kStart[4] = {0, 0, 0, 1};
    out.insert(out.end(), kStart, kStart + 4);
    out.insert(out.end(), nal, nal + size);
}

void AccessUnitFixer::remember(int type, const uint8_t* nal, size_t size) {
    std::vector<uint8_t>* slot = nullptr;
    if (codec_ == Codec::H264) {
        slot = type == 7 ? &sps_ : type == 8 ? &pps_ : nullptr;
    } else {
        slot = type == 32 ? &vps_ : type == 33 ? &sps_ : type == 34 ? &pps_ : nullptr;
    }
    if (slot) slot->assign(nal, nal + size);
}

void AccessUnitFixer::learn(const uint8_t* data, size_t size) {
    for (const auto& r : splitAnnexB(data, size)) {
        int type = nalType(codec_, data + r.offset, r.size);
        if (isParameterSet(codec_, type)) remember(type, data + r.offset, r.size);
    }
}

bool AccessUnitFixer::hasParameterSets() const {
    return !sps_.empty() && !pps_.empty() && (codec_ == Codec::H264 || !vps_.empty());
}

void AccessUnitFixer::forget() {
    vps_.clear();
    sps_.clear();
    pps_.clear();
}

AccessUnitFixer::Result AccessUnitFixer::fix(const uint8_t* data, size_t size, std::vector<uint8_t>& out) {
    Result res;
    out.clear();
    auto nals = splitAnnexB(data, size);
    // Parameter set types in the order they go: VPS (HEVC only), SPS, PPS.
    const int order[3] = {codec_ == Codec::HEVC ? 32 : -1, codec_ == Codec::HEVC ? 33 : 7,
                          codec_ == Codec::HEVC ? 34 : 8};
    bool has[3] = {codec_ == Codec::H264, false, false};
    bool anyParameterSet = false;
    for (const auto& r : nals) {
        int type = nalType(codec_, data + r.offset, r.size);
        if (isPicture(codec_, type)) {
            res.picture = true;
            if (isKeyPicture(codec_, type)) res.key = true;
        }
        if (isParameterSet(codec_, type)) {
            anyParameterSet = true;
            remember(type, data + r.offset, r.size);
            for (int k = 0; k < 3; k++)
                if (order[k] == type) has[k] = true;
        }
    }
    out.reserve(size + vps_.size() + sps_.size() + pps_.size() + 16);
    if (res.key || anyParameterSet) {
        // Parameter sets first, in order: this access unit's own, and on a
        // keyframe the last ones seen for any it lacks.
        for (int k = 0; k < 3; k++) {
            if (order[k] < 0) continue;
            if (has[k]) {
                for (const auto& r : nals)
                    if (nalType(codec_, data + r.offset, r.size) == order[k]) appendNal(out, data + r.offset, r.size);
            } else if (res.key) {
                const std::vector<uint8_t>& cached = k == 0 ? vps_ : k == 1 ? sps_ : pps_;
                if (cached.empty()) res.missingParameters = true;
                else appendNal(out, cached.data(), cached.size());
            }
        }
    }
    for (const auto& r : nals) {
        int type = nalType(codec_, data + r.offset, r.size);
        if (isAccessUnitDelimiter(codec_, type) || isParameterSet(codec_, type)) continue;
        appendNal(out, data + r.offset, r.size);
    }
    return res;
}

}  // namespace mango
