import Foundation

/// Helpers for the Annex B (start-code delimited) H.264 / HEVC frames the
/// AirPlay server hands over, and for turning them back into the
/// length-prefixed form VideoToolbox decodes.
enum NalUnits {
    /// One NAL unit: `header` is the offset of its first byte (just after the
    /// start code), `end` is one past its last byte.
    struct Unit: Equatable {
        let header: Int
        let end: Int
        let type: Int

        var length: Int { end - header }
    }

    static func type(_ header: UInt8, h265: Bool) -> Int {
        h265 ? Int(header >> 1) & 0x3F : Int(header) & 0x1F
    }

    /// VPS/SPS/PPS: they describe the stream and go into the format description.
    static func isParameterSet(_ type: Int, h265: Bool) -> Bool {
        h265 ? (32...34).contains(type) : type == 7 || type == 8
    }

    /// IDR/IRAP pictures: decoding can start here.
    static func isKeyframe(_ type: Int, h265: Bool) -> Bool {
        h265 ? (16...21).contains(type) : type == 5
    }

    static func isPicture(_ type: Int, h265: Bool) -> Bool {
        h265 ? (0...31).contains(type) : (1...5).contains(type)
    }

    /// Splits a frame into NAL units. Emulation prevention guarantees that
    /// 00 00 01 never occurs inside a NAL unit, so every one found is a
    /// boundary. Searching for the 01 bytes with memchr keeps this fast on
    /// multi-megabyte 4K keyframes.
    static func units(in frame: UnsafeRawBufferPointer, h265: Bool) -> [Unit] {
        guard let raw = frame.baseAddress, frame.count >= 4 else { return [] }
        let bytes = raw.assumingMemoryBound(to: UInt8.self)
        let count = frame.count

        var codes: [(start: Int, header: Int)] = []
        var position = 2
        while position < count, let found = memchr(bytes + position, 1, count - position) {
            let one = raw.distance(to: UnsafeRawPointer(found))
            if bytes[one - 1] == 0, bytes[one - 2] == 0 {
                let start = one >= 3 && bytes[one - 3] == 0 ? one - 3 : one - 2
                codes.append((start, one + 1))
            }
            position = one + 1
        }

        var units: [Unit] = []
        units.reserveCapacity(codes.count)
        for (index, code) in codes.enumerated() {
            let end = index + 1 < codes.count ? codes[index + 1].start : count
            guard code.header < end else { continue }
            units.append(Unit(header: code.header, end: end, type: type(bytes[code.header], h265: h265)))
        }
        return units
    }

    /// What a frame holds, as far as the decoder cares.
    struct Frame {
        /// Parameter sets in stream order (VPS, SPS, PPS), without start codes.
        var parameterSets: [Data] = []
        /// Everything else (slices, SEI, delimiters), to be sent as the sample.
        var payload: [Unit] = []
        var keyframe = false
        var hasPicture = false

        /// Size of the payload once each unit gets a 4-byte length prefix.
        var sampleLength: Int { payload.reduce(0) { $0 + 4 + $1.length } }
    }

    static func frame(_ data: UnsafeRawBufferPointer, h265: Bool) -> Frame {
        var frame = Frame()
        guard let base = data.baseAddress else { return frame }
        for unit in units(in: data, h265: h265) {
            if isParameterSet(unit.type, h265: h265) {
                frame.parameterSets.append(Data(bytes: base.advanced(by: unit.header), count: unit.length))
                continue
            }
            frame.payload.append(unit)
            if isPicture(unit.type, h265: h265) {
                frame.hasPicture = true
                if isKeyframe(unit.type, h265: h265) { frame.keyframe = true }
            }
        }
        return frame
    }

    /// Writes `units` from `source` to `destination` with 4-byte big-endian
    /// length prefixes (the "AVCC"/"HVCC" form). `destination` must hold
    /// `Frame.sampleLength` bytes.
    static func writeLengthPrefixed(_ units: [Unit], from source: UnsafeRawBufferPointer,
                                    to destination: UnsafeMutableRawPointer) {
        guard let base = source.baseAddress else { return }
        var offset = 0
        for unit in units {
            var length = UInt32(unit.length).bigEndian
            withUnsafeBytes(of: &length) { destination.advanced(by: offset).copyMemory(from: $0.baseAddress!, byteCount: 4) }
            offset += 4
            destination.advanced(by: offset).copyMemory(from: base.advanced(by: unit.header), byteCount: unit.length)
            offset += unit.length
        }
    }
}
