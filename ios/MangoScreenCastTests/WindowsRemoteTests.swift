import CoreGraphics
import CryptoKit
import XCTest

/// Controlling a Windows PC (WindowsRemoteProtocol, TouchpadGestures). The
/// PC's side is C++ in the Mango Screen Cast For Windows repository; its CI
/// also runs this file's protocol against the PC's server for real.
final class WindowsRemoteTests: XCTestCase {
    typealias Message = WindowsRemoteProtocol.Message

    private func json(_ message: Message) throws -> [String: Any] {
        let line = try XCTUnwrap(message.line)
        XCTAssertEqual(line.last, 0x0A)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: line.dropLast()) as? [String: Any])
    }

    func testMessagesAreTheLinesThePCReads() throws {
        var o = try json(.move(dx: 12.5, dy: -3))
        XCTAssertEqual(o["t"] as? String, "move")
        XCTAssertEqual(o["dx"] as? Double, 12.5)
        XCTAssertEqual(o["dy"] as? Double, -3)
        o = try json(.button(right: true, down: true))
        XCTAssertEqual(o["t"] as? String, "button")
        XCTAssertEqual(o["button"] as? String, "right")
        XCTAssertEqual(o["down"] as? Bool, true)
        o = try json(.click(right: false))
        XCTAssertEqual(o["button"] as? String, "left")
        o = try json(.key(code: 8, modifiers: [.command, .shift]))
        XCTAssertEqual(o["code"] as? Int, 8)
        XCTAssertEqual(o["modifiers"] as? Int, 9)
        o = try json(.text("café ☕️"))
        XCTAssertEqual(o["text"] as? String, "café ☕️")
        o = try json(.hello(name: "Jo’s iPhone", version: 1))
        XCTAssertEqual(o["name"] as? String, "Jo’s iPhone")
    }

    func testEveryMessageReadsBack() throws {
        let messages: [Message] = [
            .hello(name: "iPhone", version: 1), .move(dx: 1.5, dy: -2), .button(right: false, down: true),
            .click(right: true), .scroll(dx: 0, dy: 30), .text("hi\nthere"),
            .key(code: WindowsRemoteProtocol.windowsKey, modifiers: []),
            .pair(publicKey: Data(repeating: 7, count: 32), name: "iPhone"), .linkRequest(name: "iPhone"),
            .status(name: "PC", canControl: true),
            .paired(publicKey: Data(repeating: 1, count: 32), sealedKey: Data(repeating: 2, count: 60), name: "PC"),
            .rejected("no"), .codeShown(name: "PC"),
        ]
        for message in messages {
            let line = try XCTUnwrap(message.line)
            XCTAssertEqual(Message(line: line.dropLast()), message)
        }
        XCTAssertNil(Message(line: Data("nope".utf8)))
        XCTAssertNil(Message(line: Data(#"{"t":"bogus"}"#.utf8)))
        XCTAssertNil(Message(line: Data(#"{"t":"move","dx":1}"#.utf8)))
    }

    /// What the PC sends (written by its C++) reads as the iPhone expects.
    func testThePCsLinesRead() throws {
        let status = try XCTUnwrap(Message(line: Data(#"{"t": "status", "name": "GAMING-PC", "canControl": true}"#.utf8)))
        XCTAssertEqual(status, .status(name: "GAMING-PC", canControl: true))
        let shown = try XCTUnwrap(Message(line: Data(#"{"t": "codeShown", "name": "GAMING-PC"}"#.utf8)))
        XCTAssertEqual(shown, .codeShown(name: "GAMING-PC"))
    }

    func testPairingKeyMatchesThePCs() {
        // The same value the PC's tests check (HKDF-SHA256, computed independently).
        let key = WindowsRemoteProtocol.pairingKey(code: "123456").withUnsafeBytes { Data($0) }
        XCTAssertEqual(key.map { String(format: "%02x", $0) }.joined(),
                       "b13658714a3a47d0d0f00c1318cdc2c7dfa8eef439ba0d4a1a2e8c6f8763a0ba")
    }

    func testPairingSealsTheSessionKeyForThisIPhoneOnly() throws {
        let phone = Curve25519.KeyAgreement.PrivateKey()
        let session = SymmetricKey(size: .bits256)
        let answer = try WindowsRemoteProtocol.answerPairing(phoneKey: phone.publicKey.rawRepresentation, code: "424242",
                                                             sessionKey: session, pcName: "PC")
        guard case .paired(let pcKey, let sealed, let name) = answer else { return XCTFail("not paired") }
        XCTAssertEqual(name, "PC")
        XCTAssertEqual(sealed.count, 60)
        let opened = try WindowsRemoteProtocol.openPairing(own: phone, pcKey: pcKey, sealedKey: sealed, code: "424242")
        XCTAssertEqual(opened.withUnsafeBytes { Data($0) }, session.withUnsafeBytes { Data($0) })
        XCTAssertThrowsError(try WindowsRemoteProtocol.openPairing(own: phone, pcKey: pcKey, sealedKey: sealed,
                                                                   code: "424243"))
        XCTAssertThrowsError(try WindowsRemoteProtocol.openPairing(own: Curve25519.KeyAgreement.PrivateKey(),
                                                                   pcKey: pcKey, sealedKey: sealed, code: "424242"))
    }

    func testTouchpadGestures() {
        typealias G = TouchpadGestures
        XCTAssertEqual(G.ending(fingers: 1, duration: 0.1, travel: 2, dragging: false), .click)
        XCTAssertEqual(G.ending(fingers: 2, duration: 0.1, travel: 2, dragging: false), .rightClick)
        XCTAssertEqual(G.ending(fingers: 1, duration: 0.1, travel: 40, dragging: false), .nothing)  // a swipe
        XCTAssertEqual(G.ending(fingers: 1, duration: 0.8, travel: 2, dragging: false), .nothing)   // a long rest
        XCTAssertEqual(G.ending(fingers: 3, duration: 0.1, travel: 0, dragging: false), .nothing)
        XCTAssertEqual(G.ending(fingers: 1, duration: 2, travel: 300, dragging: true), .endDrag)
        // Slow is precise, fast goes far, never less nor more than the bounds.
        XCTAssertEqual(G.gain(speed: 0), 1.4, accuracy: 0.001)
        XCTAssertEqual(G.gain(speed: 5000), 4.0, accuracy: 0.001)
        XCTAssertLessThan(G.gain(speed: 300), G.gain(speed: 900))
    }
}
