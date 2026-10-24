import Foundation

/// Decoder setup for the AAC flavours AirPlay sends.
enum AudioCookie {
    /// AudioSpecificConfig for mirroring audio: AAC-ELD, 44.1 kHz, stereo,
    /// 480 samples per frame (the same bytes UxPlay hands its desktop decoders).
    static let aacEld: [UInt8] = [0xF8, 0xE8, 0x50, 0x00]
    /// AAC-LC, 44.1 kHz, stereo.
    static let aacLc: [UInt8] = [0x12, 0x10]

    /// Wraps an AudioSpecificConfig in an MPEG-4 ES descriptor (the contents
    /// of an `esds` box), which is the magic-cookie form Core Audio's AAC
    /// decoders read.
    static func esds(audioSpecificConfig config: [UInt8]) -> [UInt8] {
        let decoderSpecificInfo: [UInt8] = [0x05, UInt8(config.count)] + config
        // MPEG-4 audio, audio stream; buffer size and bitrates unknown (0).
        let decoderConfigBody: [UInt8] = [0x40, 0x15] + [UInt8](repeating: 0, count: 11) + decoderSpecificInfo
        let decoderConfig: [UInt8] = [0x04, UInt8(decoderConfigBody.count)] + decoderConfigBody
        let slConfig: [UInt8] = [0x06, 0x01, 0x02]
        // ES_ID 0, no flags.
        let body: [UInt8] = [0x00, 0x00, 0x00] + decoderConfig + slConfig
        return [0x03, UInt8(body.count)] + body
    }

    /// AirPlay volume (-30 dB quietest .. 0 dB, -144 mute) as a gain.
    static func gain(decibels db: Float) -> Float {
        if db <= -144 { return 0 }
        if db >= 0 { return 1 }
        return powf(10, max(db, -30) / 20)
    }
}
