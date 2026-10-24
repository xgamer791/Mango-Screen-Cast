import Foundation
import XCTest
@testable import MangoProtocol

/// Checks the Swift implementation against protocol/test-vectors.json, the
/// same file the TV app's tests use, so both ends agree byte for byte.
final class VectorTests: XCTestCase {
    struct Vectors: Decodable {
        struct DetBytes: Decodable { var seed: UInt32; var first16: String }
        struct RSCase: Decodable { var k: Int; var m: Int; var size: Int; var seed: Int; var parity: [String] }
        struct Layout: Decodable {
            struct Block: Decodable { var k: Int; var m: Int; var offset: Int }
            var length: Int; var fec: Int; var shardSize: Int; var blocks: [Block]
        }
        struct PacketCase: Decodable {
            var frameSeed: UInt32; var frameLength: Int; var session: UInt32; var number: UInt32
            var timestamp: UInt64; var flags: UInt8; var fec: Int; var packets: [String]
        }
        var detBytes: DetBytes
        var reedSolomon: [RSCase]
        var layouts: [Layout]
        var packetize: PacketCase
    }

    static let vectors: Vectors = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors.json")
        let data = try! Data(contentsOf: url)
        return try! JSONDecoder().decode(Vectors.self, from: data)
    }()

    func testDeterministicBytes() {
        XCTAssertEqual(hex(detBytes(16, seed: Self.vectors.detBytes.seed)), Self.vectors.detBytes.first16)
    }

    func testReedSolomonParityMatchesReference() {
        for c in Self.vectors.reedSolomon {
            let data = (0..<c.k).map { detBytes(c.size, seed: UInt32(c.seed * 1000 + $0)) }
            let parity = ReedSolomon(dataShards: c.k, parityShards: c.m).encodeParity(data)
            XCTAssertEqual(parity.map(hex), c.parity, "k=\(c.k) m=\(c.m)")
        }
    }

    func testLayoutsMatchReference() {
        for l in Self.vectors.layouts {
            let layout = ShardLayout(frameLength: l.length, fecPercent: l.fec)
            XCTAssertEqual(layout.shardSize, l.shardSize, "length \(l.length)")
            XCTAssertEqual(layout.blocks.map { [$0.dataShards, $0.parityShards, $0.offset] },
                           l.blocks.map { [$0.k, $0.m, $0.offset] }, "length \(l.length)")
        }
    }

    func testPacketBytesMatchReference() {
        let c = Self.vectors.packetize
        let frame = detBytes(c.frameLength, seed: c.frameSeed)
        let packets = Packetizer().packetize(frame: frame, type: .video, session: c.session,
                                             frameNumber: c.number, timestamp: c.timestamp,
                                             flags: FrameFlags(rawValue: c.flags), fecPercent: c.fec)
        XCTAssertEqual(packets.map(hex), c.packets)
    }
}

final class RoundTripTests: XCTestCase {
    func testRecoversFromAnyMLosses() {
        var rng = SystemRandomNumberGenerator()
        for (k, m) in [(1, 1), (3, 2), (10, 4), (64, 10), (128, 20)] {
            let data = (0..<k).map { _ in (0..<24).map { _ in UInt8.random(in: 0...255, using: &rng) } }
            let codec = ReedSolomon(dataShards: k, parityShards: m)
            var shards: [[UInt8]?] = data + codec.encodeParity(data)
            for i in Array(0..<(k + m)).shuffled().prefix(m) { shards[i] = nil }
            XCTAssertTrue(codec.reconstruct(&shards))
            XCTAssertEqual(shards.prefix(k).map { $0! }, data, "k=\(k) m=\(m)")
        }
    }

    func testAssemblerRebuildsFramesWithLoss() {
        let packetizer = Packetizer()
        let assembler = FrameAssembler(session: 0x1234, type: .video)
        var delivered: [[UInt8]] = []
        var lost: [UInt32] = []
        var frames: [[UInt8]] = []
        for n in 0..<30 {
            let frame = detBytes(1 + n * 9_973, seed: UInt32(n))
            frames.append(frame)
            var packets = packetizer.packetize(frame: frame, type: .video, session: 0x1234,
                                               frameNumber: UInt32(n), timestamp: UInt64(n),
                                               flags: n == 0 ? .keyframe : [], fecPercent: 20)
            // Drop every 9th packet: well within 20% FEC.
            packets = packets.enumerated().filter { $0.offset % 9 != 4 }.map(\.element)
            for p in packets {
                p.withUnsafeBytes { buf in
                    for event in assembler.receive(buf, now: Double(n) * 0.016) {
                        switch event {
                        case .frame(let f): delivered.append(f.data)
                        case .lost(let number): lost.append(number)
                        }
                    }
                }
            }
        }
        XCTAssertEqual(lost, [])
        XCTAssertEqual(delivered, frames)
        XCTAssertGreaterThan(assembler.recoveredShards, 0)
    }

    func testAssemblerReportsUnrecoverableFrame() {
        let packetizer = Packetizer()
        let assembler = FrameAssembler(session: 1, type: .video)
        var events: [String] = []
        for n in 0..<4 {
            var packets = packetizer.packetize(frame: detBytes(20_000, seed: 7), type: .video, session: 1,
                                               frameNumber: UInt32(n), timestamp: 0, flags: [], fecPercent: 10)
            if n == 1 { packets.removeFirst(5) } // more than the 2 parity shards
            for p in packets {
                p.withUnsafeBytes { buf in
                    for event in assembler.receive(buf, now: 0) {
                        switch event {
                        case .frame(let f): events.append("f\(f.number)")
                        case .lost(let number): events.append("x\(number)")
                        }
                    }
                }
            }
        }
        XCTAssertEqual(events, ["f0", "x1", "f2", "f3"])
    }

    func testControlFramingSplitsAndJoins() throws {
        let messages: [ControlMessage] = [
            .hello(Hello(role: "source", id: "abc", name: "Mac", os: "macos")),
            .keyframe(KeyframeRequest(reason: "loss")),
            .media(Data([1, 2, 3])),
            .ping(Ping(t: 42)),
        ]
        var stream = Data()
        for m in messages { stream.append(try m.framed()) }
        var framer = ControlFramer()
        var out: [ControlMessage] = []
        for byte in stream { out += try framer.append(Data([byte])) }
        XCTAssertEqual(out, messages)
    }

    func testParsesReceiverCaps() throws {
        let json = """
        {"display":{"width":3840,"height":2160,"refresh":[60,59.94,50]},
         "modes":[{"width":3840,"height":2160,"fps":60,"codec":"hevc"},
                  {"width":1920,"height":1080,"fps":60,"codec":"h264"}],
         "audio":{"codec":"pcm_s16le","rate":48000,"channels":2},
         "udpPort":47802,"maxBitrateKbps":150000,"transports":["udp","tcp"],"future":true}
        """
        guard case .caps(let caps) = try ControlMessage.decode(type: 0x02, payload: Data(json.utf8)) else {
            return XCTFail("not caps")
        }
        XCTAssertEqual(caps.modes.count, 2)
        XCTAssertEqual(ModeSelection.choose(from: caps.modes, codecs: [.hevc, .h264], maxFps: 60)?.width, 3840)
        XCTAssertEqual(ModeSelection.choose(from: caps.modes, codecs: [.h264], maxFps: 60)?.width, 1920)
        XCTAssertEqual(ModeSelection.choose(from: caps.modes, codecs: [.hevc], maxFps: 30)?.fps, 30)
    }

    func testFitKeepsAspectAndEvenSizes() {
        let mode = StreamMode(width: 3840, height: 2160, fps: 60, codec: "hevc")
        XCTAssertTrue(ModeSelection.fit(width: 1179, height: 2556, into: mode) == (996, 2160))
        XCTAssertTrue(ModeSelection.fit(width: 3024, height: 1964, into: mode) == (3024, 1964))
        XCTAssertTrue(ModeSelection.fit(width: 5120, height: 2880, into: mode) == (3840, 2160))
    }

    func testRateControllerBacksOffAndRecovers() {
        var rc = RateController(targetKbps: 60_000)
        XCTAssertTrue(rc.update(with: Stats(packets: 900, lostPackets: 100, lostFrames: 1)))
        XCTAssertEqual(rc.bitrateKbps, 48_000)
        XCTAssertEqual(rc.fecPercent, 20)
        for _ in 0..<20 { rc.update(with: Stats(packets: 1000, lostPackets: 0, lostFrames: 0)) }
        XCTAssertEqual(rc.bitrateKbps, 60_000)
        XCTAssertEqual(rc.fecPercent, RateController.minimumFec)
    }

    func testLocalAddressFilter() {
        for host in ["192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.3", "127.0.0.1",
                     "fe80::1%en0", "fd12::1", "::ffff:192.168.0.2", "100.100.1.1"] {
            XCTAssertTrue(LocalNetwork.isLocal(host), host)
        }
        for host in ["8.8.8.8", "172.32.0.1", "2001:db8::1", "1.1.1.1"] {
            XCTAssertFalse(LocalNetwork.isLocal(host), host)
        }
    }
}

func detBytes(_ n: Int, seed: UInt32) -> [UInt8] {
    var x = seed
    return (0..<n).map { _ in
        x = x &* 1103515245 &+ 12345
        return UInt8((x >> 16) & 0xFF)
    }
}

func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }
