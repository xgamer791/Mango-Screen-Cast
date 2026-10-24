import Foundation

public enum MediaType: UInt8 {
    case video = 1
    case audio = 2
}

public struct FrameFlags: OptionSet, Hashable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }

    public static let keyframe = FrameFlags(rawValue: 0x01)
    public static let parameterSets = FrameFlags(rawValue: 0x02)
}

/// The 32-byte header in front of every media shard (docs/PROTOCOL.md §4).
public struct MediaPacketHeader: Equatable {
    public static let size = 32
    public static let magic: UInt8 = 0x4D
    public static let version: UInt8 = 1

    public var type: MediaType
    public var session: UInt16
    public var frameNumber: UInt32
    public var frameLength: UInt32
    public var timestamp: UInt64
    public var flags: FrameFlags
    public var blockIndex: UInt8
    public var blockCount: UInt8
    public var shardIndex: UInt8
    public var dataShards: UInt8
    public var parityShards: UInt8
    public var shardSize: UInt16
    public var blockOffset: UInt32

    public init(type: MediaType, session: UInt16, frameNumber: UInt32, frameLength: UInt32,
                timestamp: UInt64, flags: FrameFlags, blockIndex: UInt8, blockCount: UInt8,
                shardIndex: UInt8, dataShards: UInt8, parityShards: UInt8, shardSize: UInt16,
                blockOffset: UInt32) {
        self.type = type
        self.session = session
        self.frameNumber = frameNumber
        self.frameLength = frameLength
        self.timestamp = timestamp
        self.flags = flags
        self.blockIndex = blockIndex
        self.blockCount = blockCount
        self.shardIndex = shardIndex
        self.dataShards = dataShards
        self.parityShards = parityShards
        self.shardSize = shardSize
        self.blockOffset = blockOffset
    }

    public func write(to p: UnsafeMutableRawPointer) {
        let b = p.assumingMemoryBound(to: UInt8.self)
        b[0] = Self.magic
        b[1] = Self.version << 4 | type.rawValue
        Self.put16(session, b + 2)
        Self.put32(frameNumber, b + 4)
        Self.put32(frameLength, b + 8)
        Self.put32(UInt32(truncatingIfNeeded: timestamp >> 32), b + 12)
        Self.put32(UInt32(truncatingIfNeeded: timestamp), b + 16)
        b[20] = flags.rawValue
        b[21] = blockIndex
        b[22] = blockCount
        b[23] = shardIndex
        b[24] = dataShards
        b[25] = parityShards
        Self.put16(shardSize, b + 26)
        Self.put32(blockOffset, b + 28)
    }

    /// Parses and validates a datagram. Returns nil for anything malformed.
    public init?(parsing bytes: UnsafeRawBufferPointer) {
        guard bytes.count >= Self.size else { return nil }
        let b = bytes.bindMemory(to: UInt8.self)
        guard b[0] == Self.magic, b[1] >> 4 == Self.version,
              let type = MediaType(rawValue: b[1] & 0x0F) else { return nil }
        self.type = type
        session = Self.get16(b, 2)
        frameNumber = Self.get32(b, 4)
        frameLength = Self.get32(b, 8)
        timestamp = UInt64(Self.get32(b, 12)) << 32 | UInt64(Self.get32(b, 16))
        flags = FrameFlags(rawValue: b[20])
        blockIndex = b[21]
        blockCount = b[22]
        shardIndex = b[23]
        dataShards = b[24]
        parityShards = b[25]
        shardSize = Self.get16(b, 26)
        blockOffset = Self.get32(b, 28)
        guard blockCount > 0, blockIndex < blockCount, dataShards > 0,
              Int(dataShards) + Int(parityShards) <= 255,
              shardIndex < dataShards &+ parityShards, shardSize > 0,
              bytes.count >= Self.size + Int(shardSize), frameLength > 0 else { return nil }
    }

    @inline(__always) static func put16(_ v: UInt16, _ p: UnsafeMutablePointer<UInt8>) {
        p[0] = UInt8(v >> 8); p[1] = UInt8(v & 0xFF)
    }
    @inline(__always) static func put32(_ v: UInt32, _ p: UnsafeMutablePointer<UInt8>) {
        p[0] = UInt8(v >> 24); p[1] = UInt8((v >> 16) & 0xFF); p[2] = UInt8((v >> 8) & 0xFF); p[3] = UInt8(v & 0xFF)
    }
    @inline(__always) static func get16(_ b: UnsafeBufferPointer<UInt8>, _ i: Int) -> UInt16 {
        UInt16(b[i]) << 8 | UInt16(b[i + 1])
    }
    @inline(__always) static func get32(_ b: UnsafeBufferPointer<UInt8>, _ i: Int) -> UInt32 {
        UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3])
    }
}
