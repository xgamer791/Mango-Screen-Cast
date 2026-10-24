import Foundation

/// How a frame is cut into Reed–Solomon blocks (docs/PROTOCOL.md §4.1).
public struct ShardLayout: Equatable {
    public struct Block: Equatable {
        public var dataShards: Int
        public var parityShards: Int
        public var offset: Int
    }

    public var shardSize: Int
    public var blocks: [Block]

    public static let maxDatagram = 1400
    public static let maxShard = maxDatagram - MediaPacketHeader.size
    public static let maxDataShardsPerBlock = 128

    public init(frameLength length: Int, fecPercent: Int) {
        precondition(length > 0)
        let total = (length + Self.maxShard - 1) / Self.maxShard
        let size = (length + total - 1) / total
        let blockCount = (total + Self.maxDataShardsPerBlock - 1) / Self.maxDataShardsPerBlock
        var offset = 0
        var blocks: [Block] = []
        for b in 0..<blockCount {
            let k = total / blockCount + (b < total % blockCount ? 1 : 0)
            let m = fecPercent <= 0 ? 0 : min(255 - k, max(1, (k * fecPercent + 99) / 100))
            blocks.append(Block(dataShards: k, parityShards: m, offset: offset))
            offset += k * size
        }
        self.shardSize = size
        self.blocks = blocks
    }

    public var packetCount: Int { blocks.reduce(0) { $0 + $1.dataShards + $1.parityShards } }
}

/// Splits frames into datagrams with FEC. Reuses its buffers across frames,
/// so one instance must not be used from two threads at once.
public final class Packetizer {
    private var packet = [UInt8](repeating: 0, count: ShardLayout.maxDatagram)
    private var padded = [UInt8](repeating: 0, count: ShardLayout.maxShard)
    private var parity = [UInt8]()

    public init() {}

    /// Calls `emit` once per datagram, in send order. The buffer passed to
    /// `emit` is only valid during the call.
    public func packetize(frame: UnsafeRawBufferPointer, type: MediaType, session: UInt32,
                          frameNumber: UInt32, timestamp: UInt64, flags: FrameFlags,
                          fecPercent: Int, emit: (UnsafeRawBufferPointer) -> Void) {
        guard frame.count > 0, let base = frame.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
        let layout = ShardLayout(frameLength: frame.count, fecPercent: fecPercent)
        let size = layout.shardSize
        var header = MediaPacketHeader(
            type: type, session: UInt16(truncatingIfNeeded: session), frameNumber: frameNumber,
            frameLength: UInt32(frame.count), timestamp: timestamp, flags: flags, blockIndex: 0,
            blockCount: UInt8(layout.blocks.count), shardIndex: 0, dataShards: 0, parityShards: 0,
            shardSize: UInt16(size), blockOffset: 0)

        let maxParity = layout.blocks.map(\.parityShards).max() ?? 0
        if parity.count < maxParity * size { parity = [UInt8](repeating: 0, count: maxParity * size) }

        packet.withUnsafeMutableBytes { packetBuf in
            padded.withUnsafeMutableBufferPointer { paddedBuf in
                parity.withUnsafeMutableBufferPointer { parityBuf in
                    let packetPtr = packetBuf.baseAddress!
                    let payload = packetPtr.advanced(by: MediaPacketHeader.size).assumingMemoryBound(to: UInt8.self)
                    for (bi, block) in layout.blocks.enumerated() {
                        header.blockIndex = UInt8(bi)
                        header.dataShards = UInt8(block.dataShards)
                        header.parityShards = UInt8(block.parityShards)
                        header.blockOffset = UInt32(block.offset)

                        var dataPointers = [UnsafePointer<UInt8>]()
                        dataPointers.reserveCapacity(block.dataShards)
                        for i in 0..<block.dataShards {
                            let start = block.offset + i * size
                            let available = min(size, frame.count - start)
                            let shard: UnsafePointer<UInt8>
                            if available == size {
                                shard = base + start
                            } else {
                                // Only the frame's last shard is short; pad it with zeros.
                                let p = paddedBuf.baseAddress!
                                p.update(from: base + start, count: available)
                                (p + available).update(repeating: 0, count: size - available)
                                shard = UnsafePointer(p)
                            }
                            dataPointers.append(shard)
                            header.shardIndex = UInt8(i)
                            header.write(to: packetPtr)
                            payload.update(from: shard, count: size)
                            emit(UnsafeRawBufferPointer(start: packetPtr, count: MediaPacketHeader.size + size))
                        }

                        guard block.parityShards > 0 else { continue }
                        let outputs = (0..<block.parityShards).map { parityBuf.baseAddress! + $0 * size }
                        ReedSolomon.shared(dataShards: block.dataShards, parityShards: block.parityShards)
                            .encodeParity(data: dataPointers, parity: outputs, shardSize: size)
                        for j in 0..<block.parityShards {
                            header.shardIndex = UInt8(block.dataShards + j)
                            header.write(to: packetPtr)
                            payload.update(from: outputs[j], count: size)
                            emit(UnsafeRawBufferPointer(start: packetPtr, count: MediaPacketHeader.size + size))
                        }
                    }
                }
            }
        }
    }

    /// Convenience for tests: returns every datagram as its own array.
    public func packetize(frame: [UInt8], type: MediaType, session: UInt32, frameNumber: UInt32,
                          timestamp: UInt64, flags: FrameFlags, fecPercent: Int) -> [[UInt8]] {
        var out: [[UInt8]] = []
        frame.withUnsafeBytes { buf in
            packetize(frame: buf, type: type, session: session, frameNumber: frameNumber,
                      timestamp: timestamp, flags: flags, fecPercent: fecPercent) { out.append(Array($0)) }
        }
        return out
    }
}
