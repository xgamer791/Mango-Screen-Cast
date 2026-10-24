import Foundation

/// Rebuilds frames from shards (docs/PROTOCOL.md §4.3). The TV app has the
/// production receiver; this one backs loopback tests and any future Apple
/// receiver.
public final class FrameAssembler {
    public struct Frame {
        public var number: UInt32
        public var timestamp: UInt64
        public var flags: FrameFlags
        public var data: [UInt8]
    }

    public enum Event {
        case frame(Frame)
        /// A frame that could not be rebuilt.
        case lost(frameNumber: UInt32)
    }

    private final class Block {
        let k: Int, m: Int, size: Int, offset: Int
        var shards: [[UInt8]?]
        var received = 0
        var done = false
        init(k: Int, m: Int, size: Int, offset: Int) {
            self.k = k; self.m = m; self.size = size; self.offset = offset
            shards = Array(repeating: nil, count: k + m)
        }
    }

    private final class Pending {
        let header: MediaPacketHeader
        var blocks: [Block?]
        var blocksDone = 0
        let firstSeen: TimeInterval
        init(header: MediaPacketHeader, now: TimeInterval) {
            self.header = header
            blocks = Array(repeating: nil, count: Int(header.blockCount))
            firstSeen = now
        }
    }

    public let session: UInt16
    public let type: MediaType
    public var timeout: TimeInterval = 0.060

    private var pending: [UInt32: Pending] = [:]
    private var completed: [UInt32: Frame] = [:]
    private var nextNumber: UInt32?
    private var newest: UInt32?

    public private(set) var recoveredShards = 0
    public private(set) var missingShards = 0

    public init(session: UInt16, type: MediaType) {
        self.session = session
        self.type = type
    }

    /// Feeds one datagram. Returns frames (in order) and losses it unblocked.
    public func receive(_ datagram: UnsafeRawBufferPointer, now: TimeInterval) -> [Event] {
        guard let h = MediaPacketHeader(parsing: datagram), h.session == session, h.type == type else { return [] }
        if let next = nextNumber, serialLess(h.frameNumber, next) { return [] } // stale
        if nextNumber == nil { nextNumber = h.frameNumber }
        if newest == nil || serialLess(newest!, h.frameNumber) { newest = h.frameNumber }

        let frame = pending[h.frameNumber] ?? {
            let p = Pending(header: h, now: now)
            pending[h.frameNumber] = p
            return p
        }()
        let bi = Int(h.blockIndex)
        if bi < frame.blocks.count {
            let block = frame.blocks[bi] ?? {
                let b = Block(k: Int(h.dataShards), m: Int(h.parityShards), size: Int(h.shardSize),
                              offset: Int(h.blockOffset))
                frame.blocks[bi] = b
                return b
            }()
            let si = Int(h.shardIndex)
            if !block.done, si < block.shards.count, block.shards[si] == nil, Int(h.shardSize) == block.size {
                block.shards[si] = Array(UnsafeRawBufferPointer(rebasing: datagram[MediaPacketHeader.size..<(MediaPacketHeader.size + block.size)]))
                block.received += 1
                if block.received >= block.k { finish(block, in: frame) }
            }
        }
        if frame.blocksDone == frame.blocks.count { completeFrame(frame) }
        return drain(now: now)
    }

    /// Call periodically so frames that never complete time out.
    public func poll(now: TimeInterval) -> [Event] { drain(now: now) }

    private func finish(_ block: Block, in frame: Pending) {
        let missingData = (0..<block.k).filter { block.shards[$0] == nil }.count
        if missingData > 0 {
            let codec = ReedSolomon.shared(dataShards: block.k, parityShards: block.m)
            guard codec.reconstruct(&block.shards) else { return }
            recoveredShards += missingData
        }
        missingShards += block.k + block.m - block.received
        block.done = true
        frame.blocksDone += 1
    }

    private func completeFrame(_ frame: Pending) {
        let h = frame.header
        var data = [UInt8](repeating: 0, count: Int(h.frameLength))
        for case let block? in frame.blocks {
            for i in 0..<block.k {
                let start = block.offset + i * block.size
                guard start < data.count, let shard = block.shards[i] else { continue }
                let n = min(block.size, data.count - start)
                data.replaceSubrange(start..<(start + n), with: shard[0..<n])
            }
        }
        pending[h.frameNumber] = nil
        completed[h.frameNumber] = Frame(number: h.frameNumber, timestamp: h.timestamp, flags: h.flags, data: data)
    }

    private func drain(now: TimeInterval) -> [Event] {
        var events: [Event] = []
        while let next = nextNumber {
            if let frame = completed.removeValue(forKey: next) {
                events.append(.frame(frame))
            } else if let p = pending[next] {
                let superseded = newest.map { serialDistance(next, $0) >= 2 } ?? false
                guard superseded || now - p.firstSeen > timeout else { break }
                pending[next] = nil
                events.append(.lost(frameNumber: next))
            } else if let newest = newest, serialLess(next, newest) {
                // Never saw a single shard of this frame.
                events.append(.lost(frameNumber: next))
            } else {
                break
            }
            nextNumber = next &+ 1
        }
        return events
    }
}

@inline(__always) func serialDistance(_ a: UInt32, _ b: UInt32) -> Int32 { Int32(bitPattern: b &- a) }
@inline(__always) func serialLess(_ a: UInt32, _ b: UInt32) -> Bool { serialDistance(a, b) > 0 }
