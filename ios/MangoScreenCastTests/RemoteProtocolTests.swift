import CryptoKit
import Network
import XCTest

final class RemoteProtocolTests: XCTestCase {
    typealias Message = RemoteProtocol.Message

    private let everyMessage: [Message] = [
        .hello(name: "Mango Cast iPad", version: RemoteProtocol.version),
        .picture(width: 2732, height: 2048),
        .point(fx: 0.25, fy: 0.75),
        .click(fx: 0.5, fy: 0.5, button: .left, count: 2),
        .press(fx: 0.1, fy: 0.9, button: .right, down: true, count: 1),
        .wheel(fx: 0.5, fy: 0.5, fdx: 0, fdy: 0.0125),
        .text("héllo 你好 👋\n"),
        .key(code: MacKeys.returnKey, modifiers: [.command, .shift]),
        .pair(publicKey: Data(repeating: 7, count: 32), name: "iPad"),
        .status(name: "Mac", canControl: false),
        .paired(publicKey: Data(repeating: 1, count: 32), sealedKey: Data(repeating: 2, count: 60), name: "Mac"),
        .rejected("Wrong code"),
        .linkRequest(name: "Mango Cast iPad"),
        .codeShown(macName: "Chris’s MacBook Pro"),
    ]

    func testEveryMessageSurvivesJSON() throws {
        for message in everyMessage {
            let data = try JSONEncoder().encode(message)
            XCTAssertFalse(data.contains(0x0A), "a message must fit on one line: \(message)")
            XCTAssertEqual(try JSONDecoder().decode(Message.self, from: data), message)
        }
    }

    func testCodesAreSixDigits() {
        for _ in 0..<100 {
            let code = RemoteProtocol.newCode()
            XCTAssertEqual(code.count, 6)
            XCTAssertTrue(code.allSatisfy(\.isNumber))
        }
    }

    func testPairingHandsOverTheSessionKey() throws {
        let iPad = Curve25519.KeyAgreement.PrivateKey()
        let sessionKey = RemoteProtocol.newSessionKey()
        let answer = try RemoteProtocol.answerPairing(iPadKey: iPad.publicKey.rawRepresentation, code: "123456",
                                                      sessionKey: sessionKey, macName: "Mac")
        guard case let .paired(macKey, sealed, name) = answer else { return XCTFail("not paired: \(answer)") }
        XCTAssertEqual(name, "Mac")
        let opened = try RemoteProtocol.openPairing(own: iPad, macKey: macKey, sealedKey: sealed, code: "123456")
        XCTAssertEqual(bytes(opened), bytes(sessionKey))
    }

    func testPairingWithTheWrongCodeOpensNothing() throws {
        let iPad = Curve25519.KeyAgreement.PrivateKey()
        let answer = try RemoteProtocol.answerPairing(iPadKey: iPad.publicKey.rawRepresentation, code: "123456",
                                                      sessionKey: RemoteProtocol.newSessionKey(), macName: "Mac")
        guard case let .paired(macKey, sealed, _) = answer else { return XCTFail("not paired: \(answer)") }
        XCTAssertThrowsError(try RemoteProtocol.openPairing(own: iPad, macKey: macKey, sealedKey: sealed,
                                                            code: "654321"))
        // Nor does another device's key.
        XCTAssertThrowsError(try RemoteProtocol.openPairing(own: Curve25519.KeyAgreement.PrivateKey(),
                                                            macKey: macKey, sealedKey: sealed, code: "123456"))
    }

    func testPairingKeysDependOnTheCode() {
        XCTAssertEqual(bytes(RemoteProtocol.pairingKey(code: "000001")), bytes(RemoteProtocol.pairingKey(code: "000001")))
        XCTAssertNotEqual(bytes(RemoteProtocol.pairingKey(code: "000001")),
                          bytes(RemoteProtocol.pairingKey(code: "000002")))
    }

    // MARK: - Over the network, on this machine

    /// A listener on the loopback that answers each message with `reply`.
    private final class Loopback {
        let listener: NWListener
        let queue = DispatchQueue(label: "loopback")
        var channels: [RemoteChannel] = []
        var received: [Message] = []
        var onReceive: ((Message) -> Void)?

        convenience init(key: SymmetricKey, identity: String, reply: @escaping (Message) -> Message?) throws {
            try self.init(parameters: RemoteProtocol.parameters(key: key, identity: identity), reply: reply)
        }

        init(parameters: NWParameters, reply: @escaping (Message) -> Message?) throws {
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            listener = try NWListener(using: parameters)
            listener.newConnectionHandler = { [unowned self] connection in
                let channel = RemoteChannel(connection: connection)
                channel.onMessage = { [unowned self, unowned channel] message in
                    self.received.append(message)
                    self.onReceive?(message)
                    if let answer = reply(message) { channel.send(answer) }
                }
                self.channels.append(channel)
                channel.start(queue: self.queue)
            }
        }

        func start() throws -> NWEndpoint.Port {
            let ready = DispatchSemaphore(value: 0)
            listener.stateUpdateHandler = { state in
                if case .ready = state { ready.signal() }
            }
            listener.start(queue: queue)
            guard ready.wait(timeout: .now() + 10) == .success, let port = listener.port else {
                throw NSError(domain: "loopback", code: 1, userInfo: [NSLocalizedDescriptionKey: "listener not ready"])
            }
            return port
        }

        func stop() {
            channels.forEach { $0.cancel() }
            listener.cancel()
        }
    }

    private func connect(to port: NWEndpoint.Port, key: SymmetricKey, identity: String) -> RemoteChannel {
        RemoteChannel(connection: NWConnection(host: "127.0.0.1", port: port,
                                               using: RemoteProtocol.parameters(key: key, identity: identity)))
    }

    func testASessionCarriesMessagesBothWays() throws {
        let key = RemoteProtocol.newSessionKey()
        let mac = try Loopback(key: key, identity: RemoteProtocol.sessionIdentity) { message in
            if case .hello = message { return .status(name: "Mac", canControl: true) }
            return nil
        }
        let port = try mac.start()
        defer { mac.stop() }

        let iPad = connect(to: port, key: key, identity: RemoteProtocol.sessionIdentity)
        let status = expectation(description: "status from the Mac")
        let allThere = expectation(description: "the Mac has every message")
        iPad.onMessage = { message in
            if message == .status(name: "Mac", canControl: true) { status.fulfill() }
        }
        let sent: [Message] = [
            .hello(name: "iPad", version: RemoteProtocol.version),
            .click(fx: 0.5, fy: 0.5, button: .left, count: 1),
            .wheel(fx: 0.5, fy: 0.5, fdx: 0, fdy: 0.05),
            .text("typed on the iPad"),
            .key(code: MacKeys.returnKey, modifiers: []),
        ]
        mac.onReceive = { _ in
            if mac.received.count == sent.count { allThere.fulfill() }
        }
        iPad.onState = { state in
            if case .ready = state { sent.forEach(iPad.send) }
        }
        iPad.start(queue: DispatchQueue(label: "iPad"))
        defer { iPad.cancel() }

        wait(for: [status, allThere], timeout: 15)
        XCTAssertEqual(mac.queue.sync { mac.received }, sent)
    }

    func testTheWrongKeyGetsNoSession() throws {
        let mac = try Loopback(key: RemoteProtocol.newSessionKey(), identity: RemoteProtocol.sessionIdentity) { _ in nil }
        let port = try mac.start()
        defer { mac.stop() }

        let stranger = connect(to: port, key: RemoteProtocol.newSessionKey(), identity: RemoteProtocol.sessionIdentity)
        let refused = expectation(description: "the handshake fails")
        let connected = expectation(description: "connected")
        connected.isInverted = true
        var done = false
        stranger.onState = { state in
            switch state {
            case .ready: connected.fulfill()
            case .failed, .waiting, .cancelled:
                if !done { done = true; refused.fulfill() }
            default: break
            }
        }
        stranger.start(queue: DispatchQueue(label: "stranger"))
        defer { stranger.cancel() }
        wait(for: [refused, connected], timeout: 10)
        XCTAssertTrue(mac.queue.sync { mac.received }.isEmpty)
    }

    func testPairingOverTheNetwork() throws {
        // What RemoteServer does on the Mac, and RemoteControl on the iPad.
        let code = "482913"
        let sessionKey = RemoteProtocol.newSessionKey()
        let mac = try Loopback(key: RemoteProtocol.pairingKey(code: code), identity: RemoteProtocol.pairingIdentity) { message in
            guard case let .pair(publicKey, _) = message else { return nil }
            return try? RemoteProtocol.answerPairing(iPadKey: publicKey, code: code, sessionKey: sessionKey,
                                                     macName: "Mac")
        }
        let port = try mac.start()
        defer { mac.stop() }

        let own = Curve25519.KeyAgreement.PrivateKey()
        let iPad = connect(to: port, key: RemoteProtocol.pairingKey(code: code), identity: RemoteProtocol.pairingIdentity)
        let paired = expectation(description: "paired")
        var opened: SymmetricKey?
        iPad.onMessage = { message in
            if case let .paired(macKey, sealed, _) = message {
                opened = try? RemoteProtocol.openPairing(own: own, macKey: macKey, sealedKey: sealed, code: code)
                paired.fulfill()
            }
        }
        iPad.onState = { state in
            if case .ready = state { iPad.send(.pair(publicKey: own.publicKey.rawRepresentation, name: "iPad")) }
        }
        iPad.start(queue: DispatchQueue(label: "iPad"))
        defer { iPad.cancel() }

        wait(for: [paired], timeout: 15)
        XCTAssertEqual(opened.map(bytes), bytes(sessionKey))
    }

    func testLinkAsksTheMacToShowACode() throws {
        // What RemoteServer does on the Mac: Link pops up a code, and says so.
        let mac = try Loopback(parameters: RemoteProtocol.linkParameters()) { message in
            guard case .linkRequest = message else { return nil }
            return .codeShown(macName: "Mac")
        }
        let port = try mac.start()
        defer { mac.stop() }

        let iPad = RemoteChannel(connection: NWConnection(host: "127.0.0.1", port: port,
                                                          using: RemoteProtocol.linkParameters()))
        let shown = expectation(description: "the code is showing")
        iPad.onMessage = { message in
            if message == .codeShown(macName: "Mac") { shown.fulfill() }
        }
        iPad.onState = { state in
            if case .ready = state { iPad.send(.linkRequest(name: "Mango Cast iPad")) }
        }
        iPad.start(queue: DispatchQueue(label: "iPad"))
        defer { iPad.cancel() }
        wait(for: [shown], timeout: 15)
        XCTAssertEqual(mac.queue.sync { mac.received }, [.linkRequest(name: "Mango Cast iPad")])
    }

    func testALongLineArrivesWhole() throws {
        let key = RemoteProtocol.newSessionKey()
        let mac = try Loopback(key: key, identity: RemoteProtocol.sessionIdentity) { _ in nil }
        let port = try mac.start()
        defer { mac.stop() }
        let long = String(repeating: "Mango ", count: 40_000) // past one 64 KB read
        let iPad = connect(to: port, key: key, identity: RemoteProtocol.sessionIdentity)
        let arrived = expectation(description: "arrived")
        mac.onReceive = { message in
            if message == .text(long) { arrived.fulfill() }
        }
        iPad.onState = { state in
            if case .ready = state { iPad.send(.text(long)); iPad.send(.text("after")) }
        }
        iPad.start(queue: DispatchQueue(label: "iPad"))
        defer { iPad.cancel() }
        wait(for: [arrived], timeout: 15)
    }
}

private func bytes(_ key: SymmetricKey) -> Data {
    key.withUnsafeBytes { Data($0) }
}
