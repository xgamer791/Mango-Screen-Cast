import Network
import XCTest

/// A real server on a real loopback connection, with a scripted Windows computer on the other end.
final class MangoCastServerTests: XCTestCase {
    private let offer = Offer(name: "Mango iPad", maxWidth: 1920, maxHeight: 1080, fps: 60, h265: true, audio: true)

    private final class Recorder: MangoCastSink {
        var decide: (Hello) -> Decision
        private let lock = NSLock()
        private var _hellos: [Hello] = []
        private var _started: [MangoCastServer.Session] = []
        private var _video: [Data] = []
        private var _audio: [Data] = []
        private var _ended: [(reason: String, lost: Bool)] = []
        let startedSignal = DispatchSemaphore(value: 0)
        let videoSignal = DispatchSemaphore(value: 0)
        let audioSignal = DispatchSemaphore(value: 0)
        let endedSignal = DispatchSemaphore(value: 0)

        init(decide: @escaping (Hello) -> Decision) {
            self.decide = decide
        }

        var hellos: [Hello] { lock.withLock { _hellos } }
        var started: [MangoCastServer.Session] { lock.withLock { _started } }
        var video: [Data] { lock.withLock { _video } }
        var audio: [Data] { lock.withLock { _audio } }
        var ended: [(reason: String, lost: Bool)] { lock.withLock { _ended } }

        func mangoCastHello(_ hello: Hello) -> Decision {
            lock.withLock { _hellos.append(hello) }
            return decide(hello)
        }

        func mangoCastStarted(_ session: MangoCastServer.Session) {
            lock.withLock { _started.append(session) }
            startedSignal.signal()
        }

        func mangoCastVideo(_ session: MangoCastServer.Session, _ frame: UnsafeRawBufferPointer) {
            lock.withLock { _video.append(Data(frame)) }
            videoSignal.signal()
        }

        func mangoCastAudio(_ session: MangoCastServer.Session, _ pcm: UnsafeRawBufferPointer) {
            lock.withLock { _audio.append(Data(pcm)) }
            audioSignal.signal()
        }

        func mangoCastEnded(_ session: MangoCastServer.Session, reason: String, lost: Bool) {
            lock.withLock { _ended.append((reason, lost)) }
            endedSignal.signal()
        }
    }

    /// A Windows computer, as far as the protocol goes.
    private final class Sender {
        let connection: NWConnection
        private let queue = DispatchQueue(label: "test.sender")
        private(set) var closed = false

        init(port: UInt16) {
            connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            connection.start(queue: queue)
        }

        func send(_ type: UInt8, _ payload: Data) {
            connection.send(content: MangoCast.frame(type, payload), completion: .idempotent)
        }

        func send(raw: Data) {
            connection.send(content: raw, completion: .idempotent)
        }

        func hello(_ json: String = """
            {"protocol": 1, "name": "DESKTOP-4F2K", "os": "Windows 11", "codecs": ["h265", "h264"],
             "width": 2560, "height": 1440, "fps": 60, "audio": true}
            """) {
            send(MangoCast.Message.hello.rawValue, Data(json.utf8))
        }

        func stamped(_ type: MangoCast.Message, _ data: Data) {
            var stamp = UInt64(123_456).bigEndian
            send(type.rawValue, Data(bytes: &stamp, count: 8) + data)
        }

        /// The next message, or nil if none came in time or the connection closed.
        func read(timeout: TimeInterval = 5) -> (type: UInt8, payload: Data)? {
            let done = DispatchSemaphore(value: 0)
            var result: (UInt8, Data)?
            connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { data, _, complete, error in
                guard let data, let header = MangoCast.header(data) else {
                    if complete || error != nil { self.closed = true }
                    done.signal()
                    return
                }
                guard header.length > 0 else {
                    result = (header.type, Data())
                    done.signal()
                    return
                }
                self.connection.receive(minimumIncompleteLength: header.length, maximumLength: header.length) {
                    payload, _, complete, error in
                    if let payload { result = (header.type, payload) }
                    if payload == nil && (complete || error != nil) { self.closed = true }
                    done.signal()
                }
            }
            _ = done.wait(timeout: .now() + timeout)
            return result.map { (type: $0.0, payload: $0.1) }
        }

        /// The next message that isn't a PING.
        func readSkippingPings(timeout: TimeInterval = 5) -> (type: UInt8, payload: Data)? {
            while let message = read(timeout: timeout) {
                if message.type != MangoCast.Message.ping.rawValue { return message }
            }
            return nil
        }

        /// True when the other end closed the connection (reading what it sent first).
        func closedByReceiver() -> Bool {
            while read(timeout: 3) != nil {}
            return closed
        }

        func close() {
            connection.cancel()
        }
    }

    private var sink: Recorder!
    private var server: MangoCastServer!
    private var port: UInt16 = 0
    private var logs: [String] = []
    private let logLock = NSLock()

    override func setUp() {
        super.setUp()
        let offer = self.offer
        sink = Recorder { _ in .accept(offer) }
        server = MangoCastServer()
        server.sink = sink
        server.log = { [weak self] line in self?.logLock.withLock { self?.logs.append(line) } }
        let ready = DispatchSemaphore(value: 0)
        var listening: UInt16?
        server.start(name: nil, preferredPort: 0) { port in
            listening = port
            ready.signal()
        }
        XCTAssertEqual(ready.wait(timeout: .now() + 5), .success)
        port = listening ?? 0
        XCTAssertGreaterThan(port, 0)
    }

    override func tearDown() {
        server.stop(reason: "The test is over.")
        super.tearDown()
    }

    private func object(_ data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func welcomed() throws -> Sender {
        let sender = Sender(port: port)
        sender.hello()
        let welcome = try XCTUnwrap(sender.readSkippingPings())
        XCTAssertEqual(welcome.type, MangoCast.Message.welcome.rawValue)
        XCTAssertEqual(object(welcome.payload)["codec"] as? String, "h265")
        XCTAssertEqual(sink.startedSignal.wait(timeout: .now() + 2), .success)
        return sender
    }

    func testWelcomesASenderAndPassesOnItsPicturesAndSound() throws {
        let sender = try welcomed()
        let session = try XCTUnwrap(sink.started.first)
        XCTAssertEqual(session.hello.name, "DESKTOP-4F2K")
        XCTAssertTrue(session.h265)
        XCTAssertEqual([session.width, session.height], [1920, 1080])
        XCTAssertTrue(session.audio)
        XCTAssertTrue(server.session === session)

        let picture = Data([0, 0, 0, 1, 0x40, 1, 2, 3])
        sender.stamped(.video, picture)
        XCTAssertEqual(sink.videoSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.video, [picture], "the timestamp is taken off")

        let pcm = Data((0..<1920).map { UInt8($0 & 0xFF) })
        sender.stamped(.audio, pcm)
        XCTAssertEqual(sink.audioSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.audio, [pcm])

        var stamp = UInt64(42).bigEndian
        sender.send(MangoCast.Message.ping.rawValue, Data(bytes: &stamp, count: 8))
        let pong = try XCTUnwrap(sender.readSkippingPings())
        XCTAssertEqual(pong.type, MangoCast.Message.pong.rawValue)
        XCTAssertEqual([UInt8](pong.payload), [0, 0, 0, 0, 0, 0, 0, 42])

        // A message type from a newer version is skipped, and the session goes on.
        sender.send(0x55, Data([9, 9, 9]))
        sender.stamped(.video, picture)
        XCTAssertEqual(sink.videoSignal.wait(timeout: .now() + 2), .success)

        sender.send(MangoCast.Message.bye.rawValue, Data("Stopped".utf8))
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.ended.first?.reason, "DESKTOP-4F2K stopped: Stopped")
        XCTAssertEqual(sink.ended.first?.lost, false)
        XCTAssertNil(server.session)
        XCTAssertTrue(sender.closedByReceiver())
    }

    func testRefusesWhenThisDeviceSaysSo() throws {
        sink.decide = { _ in .refuse(reason: "busy", message: "A Mac is mirroring to Mango iPad right now.") }
        let sender = Sender(port: port)
        sender.hello()
        let refusal = try XCTUnwrap(sender.read())
        XCTAssertEqual(refusal.type, MangoCast.Message.refuse.rawValue)
        XCTAssertEqual(object(refusal.payload)["reason"] as? String, "busy")
        XCTAssertEqual(object(refusal.payload)["message"] as? String, "A Mac is mirroring to Mango iPad right now.")
        XCTAssertTrue(sender.closedByReceiver())
        XCTAssertTrue(sink.started.isEmpty)
        XCTAssertTrue(sink.ended.isEmpty)
    }

    func testOneSenderAtATime() throws {
        let first = try welcomed()
        let second = Sender(port: port)
        second.hello(#"{"protocol": 1, "name": "LAPTOP", "codecs": ["h264"]}"#)
        let refusal = try XCTUnwrap(second.read())
        XCTAssertEqual(refusal.type, MangoCast.Message.refuse.rawValue)
        XCTAssertEqual(object(refusal.payload)["reason"] as? String, "busy")
        XCTAssertTrue((object(refusal.payload)["message"] as? String ?? "").contains("DESKTOP-4F2K"))
        XCTAssertEqual(sink.hellos.count, 1, "this device wasn't asked about the second")
        XCTAssertTrue(second.closedByReceiver())
        // The first carries on.
        first.stamped(.video, Data([0, 0, 0, 1, 0x26, 1]))
        XCTAssertEqual(sink.videoSignal.wait(timeout: .now() + 2), .success)
    }

    func testRefusesAnotherVersionOrNoCodecInCommon() throws {
        let newer = Sender(port: port)
        newer.hello(#"{"protocol": 2, "name": "FUTURE", "codecs": ["h264"]}"#)
        XCTAssertEqual(object(try XCTUnwrap(newer.read()).payload)["reason"] as? String, "protocol")

        var noHevc = offer
        noHevc.h265 = false
        sink.decide = { _ in .accept(noHevc) }
        let hevcOnly = Sender(port: port)
        hevcOnly.hello(#"{"protocol": 1, "name": "HEVC-ONLY", "codecs": ["h265"]}"#)
        let refusal = try XCTUnwrap(hevcOnly.read())
        XCTAssertEqual(refusal.type, MangoCast.Message.refuse.rawValue)
        XCTAssertEqual(object(refusal.payload)["reason"] as? String, "protocol")
        XCTAssertTrue(sink.started.isEmpty)
    }

    func testAnH264SenderIsAskedForNoMoreThan1080p() throws {
        var big = offer
        (big.maxWidth, big.maxHeight) = (2752, 2064)
        sink.decide = { _ in .accept(big) }
        let sender = Sender(port: port)
        sender.hello(#"{"protocol": 1, "name": "OLD-PC", "codecs": ["h264"], "width": 2560, "height": 1440}"#)
        let welcome = object(try XCTUnwrap(sender.readSkippingPings()).payload)
        XCTAssertEqual(welcome["codec"] as? String, "h264")
        XCTAssertEqual(welcome["maxWidth"] as? Int, 1440)
        XCTAssertEqual(welcome["maxHeight"] as? Int, 1080)
        XCTAssertEqual(sink.startedSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.started.first.map { [$0.width, $0.height] }, [1440, 810])
    }

    func testNoSoundUnlessItWasAskedFor() throws {
        var quiet = offer
        quiet.audio = false
        sink.decide = { _ in .accept(quiet) }
        let sender = Sender(port: port)
        sender.hello()
        let welcome = try XCTUnwrap(sender.readSkippingPings())
        XCTAssertTrue(object(welcome.payload)["audio"] is NSNull)
        XCTAssertEqual(sink.startedSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.started.first?.audio, false)
        sender.stamped(.audio, Data(count: 64))
        sender.stamped(.video, Data([0, 0, 0, 1, 0x26, 1]))
        XCTAssertEqual(sink.videoSignal.wait(timeout: .now() + 2), .success)
        XCTAssertTrue(sink.audio.isEmpty, "sound it didn't ask for is dropped")
    }

    func testAsksForKeyframesAtMostTwiceASecond() throws {
        let sender = try welcomed()
        let session = try XCTUnwrap(sink.started.first)
        session.requestKeyframe()
        session.requestKeyframe()
        XCTAssertEqual(sender.readSkippingPings()?.type, MangoCast.Message.keyframe.rawValue)
        Thread.sleep(forTimeInterval: 0.6)
        session.requestKeyframe()
        XCTAssertEqual(sender.readSkippingPings()?.type, MangoCast.Message.keyframe.rawValue)
        // Nothing else came in between: the second request was dropped.
        XCTAssertNil(sender.readSkippingPings(timeout: 0.4))
    }

    func testThisDeviceCanEndIt() throws {
        let sender = try welcomed()
        server.disconnect("Disconnected on Mango iPad.")
        let bye = try XCTUnwrap(sender.readSkippingPings())
        XCTAssertEqual(bye.type, MangoCast.Message.bye.rawValue)
        XCTAssertEqual(String(decoding: bye.payload, as: UTF8.self), "Disconnected on Mango iPad.")
        XCTAssertTrue(sender.closedByReceiver())
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(sink.ended.first?.lost, false)
    }

    func testASenderThatDropsOffIsLost() throws {
        let sender = try welcomed()
        sender.close()
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 3), .success)
        XCTAssertEqual(sink.ended.first?.lost, true)
        XCTAssertEqual(sink.ended.first?.reason, "Lost the connection to DESKTOP-4F2K.")
    }

    func testAnOversizedMessageEndsTheSession() throws {
        let sender = try welcomed()
        let length = UInt32(MangoCast.maxPayload + 1).bigEndian
        sender.send(raw: Data([MangoCast.Message.video.rawValue]) + withUnsafeBytes(of: length) { Data($0) })
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 3), .success)
        XCTAssertEqual(sink.ended.first?.lost, true)
        XCTAssertTrue(logLock.withLock { logs.contains { $0.contains("broke the protocol") } })
    }

    func testANewSenderCanComeOnceTheLastHasGone() throws {
        let first = try welcomed()
        first.send(MangoCast.Message.bye.rawValue, Data())
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 2), .success)
        let second = Sender(port: port)
        second.hello(#"{"protocol": 1, "name": "LAPTOP", "codecs": ["h264"]}"#)
        let welcome = try XCTUnwrap(second.readSkippingPings())
        XCTAssertEqual(welcome.type, MangoCast.Message.welcome.rawValue)
        XCTAssertEqual(object(welcome.payload)["codec"] as? String, "h264")
    }

    func testAnythingButHelloFirstIsRefused() {
        let sender = Sender(port: port)
        sender.stamped(.video, Data([0, 0, 0, 1, 0x65]))
        XCTAssertTrue(sender.closedByReceiver())
        XCTAssertTrue(sink.hellos.isEmpty)
        XCTAssertTrue(sink.started.isEmpty)
    }

    func testKeepsTheConnectionAliveWhenIdle() throws {
        let sender = try welcomed()
        // This device mostly listens; it still says something every couple of seconds.
        let ping = try XCTUnwrap(sender.read(timeout: 5))
        XCTAssertEqual(ping.type, MangoCast.Message.ping.rawValue)
        XCTAssertEqual(ping.payload.count, 8)
        XCTAssertNotNil(server.session)
    }

    func testStoppingTheServerEndsTheSession() throws {
        let sender = try welcomed()
        server.stop(reason: "Mango stopped receiving.")
        XCTAssertEqual(sender.readSkippingPings()?.type, MangoCast.Message.bye.rawValue)
        XCTAssertEqual(sink.endedSignal.wait(timeout: .now() + 2), .success)
        XCTAssertNil(server.port)
    }
}
