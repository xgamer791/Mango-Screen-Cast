import Foundation

/// Well-known values from docs/PROTOCOL.md.
public enum Mango {
    public static let protocolVersion = 1
    public static let sourceControlPort: UInt16 = 47800
    public static let receiverControlPort: UInt16 = 47801
    public static let receiverMediaPort: UInt16 = 47802
    public static let beaconPort: UInt16 = 47809
    public static let sourceServiceType = "_mangocast._tcp"
    public static let receiverServiceType = "_mangotv._tcp"
    /// A peer that sends nothing for this long is gone.
    public static let peerTimeout: TimeInterval = 5
}

public struct Hello: Codable, Equatable {
    public var proto: Int
    public var role: String
    public var id: String
    public var name: String
    public var os: String
    public var model: String?
    public var app: String?

    public init(role: String, id: String, name: String, os: String, model: String? = nil, app: String? = nil) {
        self.proto = Mango.protocolVersion
        self.role = role
        self.id = id
        self.name = name
        self.os = os
        self.model = model
        self.app = app
    }
}

public struct DisplayInfo: Codable, Equatable {
    public var width: Int
    public var height: Int
    public var refresh: [Double]?
    public init(width: Int, height: Int, refresh: [Double]? = nil) {
        self.width = width
        self.height = height
        self.refresh = refresh
    }
}

public enum VideoCodec: String, Codable, CaseIterable {
    case hevc
    case h264
}

public struct StreamMode: Codable, Equatable, Hashable {
    public var width: Int
    public var height: Int
    public var fps: Int
    public var codec: String

    public init(width: Int, height: Int, fps: Int, codec: String) {
        self.width = width
        self.height = height
        self.fps = fps
        self.codec = codec
    }

    public var videoCodec: VideoCodec? { VideoCodec(rawValue: codec) }
}

public struct AudioFormat: Codable, Equatable {
    public var codec: String
    public var rate: Int
    public var channels: Int
    public var frameMs: Int?

    public init(codec: String, rate: Int, channels: Int, frameMs: Int? = nil) {
        self.codec = codec
        self.rate = rate
        self.channels = channels
        self.frameMs = frameMs
    }

    public static let pcm48kStereo = AudioFormat(codec: "pcm_s16le", rate: 48000, channels: 2, frameMs: 10)
}

public struct Caps: Codable, Equatable {
    public var display: DisplayInfo
    public var modes: [StreamMode]
    public var audio: AudioFormat?
    public var udpPort: Int
    public var maxBitrateKbps: Int?
    public var transports: [String]?

    public init(display: DisplayInfo, modes: [StreamMode], audio: AudioFormat?, udpPort: Int,
                maxBitrateKbps: Int? = nil, transports: [String]? = nil) {
        self.display = display
        self.modes = modes
        self.audio = audio
        self.udpPort = udpPort
        self.maxBitrateKbps = maxBitrateKbps
        self.transports = transports
    }
}

public struct VideoParams: Codable, Equatable {
    public var codec: String
    public var width: Int
    public var height: Int
    public var fps: Int
    public var bitrateKbps: Int

    public init(codec: String, width: Int, height: Int, fps: Int, bitrateKbps: Int) {
        self.codec = codec
        self.width = width
        self.height = height
        self.fps = fps
        self.bitrateKbps = bitrateKbps
    }
}

public struct Start: Codable, Equatable {
    public var session: UInt32
    public var video: VideoParams?
    public var audio: AudioFormat?
    public var transport: String
    public var content: String?

    public init(session: UInt32, video: VideoParams?, audio: AudioFormat?, transport: String, content: String?) {
        self.session = session
        self.video = video
        self.audio = audio
        self.transport = transport
        self.content = content
    }
}

public struct Stop: Codable, Equatable {
    public var reason: String
    public var message: String?
    public init(reason: String, message: String? = nil) {
        self.reason = reason
        self.message = message
    }
}

public struct KeyframeRequest: Codable, Equatable {
    public var reason: String?
    public init(reason: String? = nil) { self.reason = reason }
}

public struct Stats: Codable, Equatable {
    public var intervalMs: Int?
    public var packets: Int?
    public var lostPackets: Int?
    public var recoveredPackets: Int?
    public var frames: Int?
    public var lostFrames: Int?
    public var droppedFrames: Int?
    public var decodeMs: Double?
    public var jitterMs: Double?

    public init(intervalMs: Int? = nil, packets: Int? = nil, lostPackets: Int? = nil,
                recoveredPackets: Int? = nil, frames: Int? = nil, lostFrames: Int? = nil,
                droppedFrames: Int? = nil, decodeMs: Double? = nil, jitterMs: Double? = nil) {
        self.intervalMs = intervalMs
        self.packets = packets
        self.lostPackets = lostPackets
        self.recoveredPackets = recoveredPackets
        self.frames = frames
        self.lostFrames = lostFrames
        self.droppedFrames = droppedFrames
        self.decodeMs = decodeMs
        self.jitterMs = jitterMs
    }
}

public struct Ping: Codable, Equatable {
    public var t: UInt64
    public init(t: UInt64) { self.t = t }
}

public struct TransportRequest: Codable, Equatable {
    public var mode: String
    public init(mode: String) { self.mode = mode }
}

public enum ControlMessage: Equatable {
    case hello(Hello)
    case caps(Caps)
    case start(Start)
    case stop(Stop)
    case keyframe(KeyframeRequest)
    case stats(Stats)
    case ping(Ping)
    case pong(Ping)
    case media(Data)
    case transport(TransportRequest)
    case unknown(UInt8, Data)

    public var typeCode: UInt8 {
        switch self {
        case .hello: return 0x01
        case .caps: return 0x02
        case .start: return 0x03
        case .stop: return 0x04
        case .keyframe: return 0x05
        case .stats: return 0x06
        case .ping: return 0x07
        case .pong: return 0x08
        case .media: return 0x09
        case .transport: return 0x0A
        case .unknown(let t, _): return t
        }
    }

    public func payload() throws -> Data {
        let enc = JSONEncoder()
        switch self {
        case .hello(let v): return try enc.encode(v)
        case .caps(let v): return try enc.encode(v)
        case .start(let v): return try enc.encode(v)
        case .stop(let v): return try enc.encode(v)
        case .keyframe(let v): return try enc.encode(v)
        case .stats(let v): return try enc.encode(v)
        case .ping(let v), .pong(let v): return try enc.encode(v)
        case .transport(let v): return try enc.encode(v)
        case .media(let d), .unknown(_, let d): return d
        }
    }

    /// The full framed message: length, type, payload.
    public func framed() throws -> Data {
        let body = try payload()
        var out = Data(capacity: body.count + 5)
        let length = UInt32(body.count + 1)
        out.append(contentsOf: [UInt8(length >> 24), UInt8((length >> 16) & 0xFF),
                                UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF), typeCode])
        out.append(body)
        return out
    }

    public static func decode(type: UInt8, payload: Data) throws -> ControlMessage {
        let dec = JSONDecoder()
        switch type {
        case 0x01: return .hello(try dec.decode(Hello.self, from: payload))
        case 0x02: return .caps(try dec.decode(Caps.self, from: payload))
        case 0x03: return .start(try dec.decode(Start.self, from: payload))
        case 0x04: return .stop(try dec.decode(Stop.self, from: payload))
        case 0x05: return .keyframe(payload.isEmpty ? KeyframeRequest() : try dec.decode(KeyframeRequest.self, from: payload))
        case 0x06: return .stats(try dec.decode(Stats.self, from: payload))
        case 0x07: return .ping(try dec.decode(Ping.self, from: payload))
        case 0x08: return .pong(try dec.decode(Ping.self, from: payload))
        case 0x09: return .media(payload)
        case 0x0A: return .transport(try dec.decode(TransportRequest.self, from: payload))
        default: return .unknown(type, payload)
        }
    }
}

public enum ControlFramingError: Error {
    case messageTooLarge(Int)
    case emptyMessage
}

/// Incremental parser for the control stream.
public struct ControlFramer {
    public static let maxMessage = 16 << 20
    private var buffer = Data()

    public init() {}

    public mutating func append(_ bytes: Data) throws -> [ControlMessage] {
        buffer.append(bytes)
        var messages: [ControlMessage] = []
        var start = buffer.startIndex
        while buffer.endIndex - start >= 4 {
            let length = Int(buffer[start]) << 24 | Int(buffer[start + 1]) << 16
                | Int(buffer[start + 2]) << 8 | Int(buffer[start + 3])
            guard length >= 1 else { throw ControlFramingError.emptyMessage }
            guard length <= Self.maxMessage else { throw ControlFramingError.messageTooLarge(length) }
            guard buffer.endIndex - start >= 4 + length else { break }
            let type = buffer[start + 4]
            let payload = Data(buffer[(start + 5)..<(start + 4 + length)])
            // A malformed JSON payload is dropped rather than tearing down the session.
            if let message = try? ControlMessage.decode(type: type, payload: payload) {
                messages.append(message)
            }
            start += 4 + length
        }
        buffer = Data(buffer[start...])
        return messages
    }
}

/// UDP discovery beacon (docs/PROTOCOL.md §2.2).
public struct Beacon: Codable, Equatable {
    public var mango: Int
    public var role: String
    public var id: String
    public var name: String
    public var os: String?
    public var model: String?
    public var port: Int
    public var w: Int?
    public var h: Int?
    public var probe: Bool?

    public init(role: String, id: String, name: String, os: String?, model: String?, port: Int,
                w: Int? = nil, h: Int? = nil, probe: Bool? = nil) {
        self.mango = Mango.protocolVersion
        self.role = role
        self.id = id
        self.name = name
        self.os = os
        self.model = model
        self.port = port
        self.w = w
        self.h = h
        self.probe = probe
    }
}

public enum LocalNetwork {
    /// True for loopback, private (RFC 1918 / RFC 4193), CGNAT and link-local
    /// addresses: the only peers the apps talk to (docs/PROTOCOL.md §6).
    public static func isLocal(_ host: String) -> Bool {
        var address = host.lowercased()
        if let percent = address.firstIndex(of: "%") { address = String(address[..<percent]) }
        if address.hasPrefix("::ffff:") { address = String(address.dropFirst(7)) }
        let parts = address.split(separator: ".").compactMap { Int($0) }
        if parts.count == 4 {
            switch (parts[0], parts[1]) {
            case (10, _), (127, _), (192, 168), (169, 254): return true
            case (172, 16...31): return true
            case (100, 64...127): return true
            default: return false
            }
        }
        return address == "::1" || address.hasPrefix("fe8") || address.hasPrefix("fe9")
            || address.hasPrefix("fea") || address.hasPrefix("feb")
            || address.hasPrefix("fc") || address.hasPrefix("fd")
    }
}
