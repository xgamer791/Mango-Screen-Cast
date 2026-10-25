import Foundation

/// Mango Cast, version 1 (protocol/mango-cast.md): how a Windows computer,
/// which can't send AirPlay, puts its screen and sound on this device. One
/// TCP connection; every message is a type byte, a 4-byte big-endian length
/// and its payload. The TV app speaks the same protocol.
enum MangoCast {
    static let version = 1
    static let serviceType = "_mangocast._tcp"
    static let defaultPort: UInt16 = 7270
    static let maxPayload = 16 << 20

    enum Message: UInt8 {
        case hello = 0x01, welcome = 0x02, refuse = 0x03
        case video = 0x10, keyframe = 0x11, audio = 0x20
        case ping = 0x30, pong = 0x31, bye = 0x3F
    }

    /// Sound comes as signed 16-bit little-endian PCM, 48 kHz stereo.
    static let audioRate = 48_000
    static let audioChannels = 2

    /// VIDEO and AUDIO payloads start with the sender's 8-byte timestamp.
    static let timestampBytes = 8

    static let helloTimeout: TimeInterval = 5
    static let silenceTimeout: TimeInterval = 8
    static let keepAlive: TimeInterval = 2

    static let h265 = "h265"
    static let h264 = "h264"

    /// One message as it goes on the wire.
    static func frame(_ type: Message, _ payload: Data = Data()) -> Data {
        frame(type.rawValue, payload)
    }

    static func frame(_ type: UInt8, _ payload: Data) -> Data {
        var data = Data([type])
        let length = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: length) { data.append(contentsOf: $0) }
        data.append(payload)
        return data
    }

    /// A message header's type and payload length.
    static func header(_ data: Data) -> (type: UInt8, length: Int)? {
        guard data.count >= 5 else { return nil }
        let bytes = [UInt8](data.prefix(5))
        let length = Int(bytes[1]) << 24 | Int(bytes[2]) << 16 | Int(bytes[3]) << 8 | Int(bytes[4])
        return (bytes[0], length)
    }
}

/// What a sender says about itself (HELLO).
struct Hello: Equatable {
    var protocolVersion: Int
    var name: String
    var os: String
    /// What it can encode, in its order of preference.
    var codecs: [String]
    var width: Int
    var height: Int
    var fps: Int
    var audio: Bool

    /// Reads a HELLO payload; nil when it isn't a JSON object.
    static func parse(_ payload: Data) -> Hello? {
        guard let json = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] else { return nil }
        func text(_ key: String, _ fallback: String) -> String {
            let value = (json[key] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty ? fallback : String(value.prefix(80))
        }
        return Hello(
            protocolVersion: json["protocol"] as? Int ?? 0,
            name: text("name", "Windows"),
            os: text("os", "Windows"),
            codecs: (json["codecs"] as? [Any] ?? []).compactMap { ($0 as? String)?.lowercased() },
            width: json["width"] as? Int ?? 0,
            height: json["height"] as? Int ?? 0,
            fps: json["fps"] as? Int ?? 60,
            audio: json["audio"] as? Bool ?? false)
    }
}

/// What this device takes.
struct Offer: Equatable {
    var name: String
    var maxWidth: Int
    var maxHeight: Int
    var fps: Int
    var h265: Bool
    var audio: Bool

    /// What this device takes in `codec`: H.264 no bigger than 1920 × 1080
    /// (the same shape, smaller), as Apple devices send it, because a PC
    /// without an HEVC encoder can't keep up beyond it.
    func forCodec(_ codec: String) -> Offer {
        let (maxW, maxH) = (1920, 1080)
        guard codec == MangoCast.h264, maxWidth > maxW || maxHeight > maxH else { return self }
        var capped = self
        if maxWidth * maxH >= maxHeight * maxW {
            (capped.maxWidth, capped.maxHeight) = (maxW, maxHeight * maxW / maxWidth & ~1)
        } else {
            (capped.maxWidth, capped.maxHeight) = (maxWidth * maxH / maxHeight & ~1, maxH)
        }
        return capped
    }

    /// The first of the sender's codecs this device decodes, or nil.
    func codec(for hello: Hello) -> String? {
        hello.codecs.first { $0 == MangoCast.h264 || ($0 == MangoCast.h265 && h265) }
    }

    /// The WELCOME payload for a sender that will send `codec`.
    func welcome(codec: String, hello: Hello) -> Data {
        var json: [String: Any] = [
            "protocol": MangoCast.version, "name": name, "codec": codec,
            "maxWidth": maxWidth, "maxHeight": maxHeight, "fps": fps,
        ]
        json["audio"] = audio && hello.audio
            ? ["format": "pcm_s16le", "rate": MangoCast.audioRate, "channels": MangoCast.audioChannels] as [String: Any]
            : NSNull()
        return (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data()
    }

    /// The picture the sender will send: its screen scaled to fit, keeping its shape, even sides.
    func picture(for hello: Hello) -> (width: Int, height: Int) {
        let w = hello.width > 0 ? hello.width : maxWidth
        let h = hello.height > 0 ? hello.height : maxHeight
        let fit: (Int, Int)
        if w <= maxWidth && h <= maxHeight {
            fit = (w, h)
        } else if w * maxHeight >= h * maxWidth {
            // Wider than this screen's shape: as wide as it takes.
            fit = (maxWidth, h * maxWidth / w)
        } else {
            fit = (w * maxHeight / h, maxHeight)
        }
        return (max(2, fit.0 & ~1), max(2, fit.1 & ~1))
    }

    /// The TXT record announced with `_mangocast._tcp`.
    func txtRecord(id: String, model: String) -> [String: String] {
        ["v": String(MangoCast.version), "id": id, "model": model,
         "w": String(maxWidth), "h": String(maxHeight), "fps": String(fps),
         "codecs": h265 ? "h265,h264" : "h264", "audio": audio ? "1" : "0"]
    }
}

/// This device's answer to a HELLO.
enum Decision: Equatable {
    case accept(Offer)
    /// `reason` is `busy`, `protocol` or `off` (protocol/mango-cast.md); `message` is for people.
    case refuse(reason: String, message: String)

    static func refusal(reason: String, message: String) -> Data {
        (try? JSONSerialization.data(withJSONObject: ["reason": reason, "message": message], options: [.sortedKeys]))
            ?? Data()
    }
}
