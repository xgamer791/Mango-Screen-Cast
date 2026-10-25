import CryptoKit
import Foundation
import Network
import Security

/// Mango Cast as a touchpad and keyboard for a Windows PC running Mango
/// Screen Cast For Windows (whose picture is on the TV, not here).
///
/// Kept entirely apart from controlling a Mac (RemoteProtocol): its own
/// Bonjour types, TLS identities, keys and keychain items, so nothing here
/// changes how a Mac is found, linked or controlled.
///
/// It works the same way:
///  - Linking: Link on the iPhone asks the PC (`linkRequest`, over plain
///    TCP: nothing secret goes this way) and the PC pops up a 6-digit code.
///  - Pairing: the iPhone connects with TLS keyed by that code (getting past
///    TLS proves it), the two do a Curve25519 key agreement, and the PC sends
///    its session key sealed with the result.
///  - Sessions: TLS 1.2 with that pre-shared key, so everything is encrypted
///    and only a linked iPhone gets in.
///
/// Messages are JSON, one object per line, `{"t": kind, ...}`.
enum WindowsRemoteProtocol {
    static let serviceType = "_mangowinctl._tcp"
    static let pairingServiceType = "_mangowinpair._tcp"
    static let linkServiceType = "_mangowinlink._tcp"
    static let version = 1

    static let sessionIdentity = "mango-windows-session"
    static let pairingIdentity = "mango-windows-pairing"

    /// Key codes are the Mac's virtual key codes (what TypingBox sends), plus these.
    static let windowsKey = 1000

    /// Shortcut modifiers, as RemoteProtocol.Modifiers' bits: ⌘ and ⌃ press
    /// Ctrl on the PC, ⌥ Alt.
    struct Modifiers: OptionSet, Hashable {
        let rawValue: Int
        static let command = Modifiers(rawValue: 1)
        static let option = Modifiers(rawValue: 2)
        static let control = Modifiers(rawValue: 4)
        static let shift = Modifiers(rawValue: 8)
    }

    enum Message: Equatable {
        // iPhone → PC
        case hello(name: String, version: Int)
        /// Moves the pointer by (dx, dy) pixels of a 1080-line screen; the PC
        /// scales that to its own.
        case move(dx: Double, dy: Double)
        /// A mouse button pressed or let go where the pointer is (dragging).
        case button(right: Bool, down: Bool)
        /// A click where the pointer is.
        case click(right: Bool)
        /// Scrolls by (dx, dy) points of finger movement: content follows the
        /// finger, as on the iPhone.
        case scroll(dx: Double, dy: Double)
        /// Characters to type, whatever the PC's keyboard layout.
        case text(String)
        /// A key with modifiers held (a Mac virtual key code, or windowsKey).
        case key(code: Int, modifiers: Modifiers)
        case pair(publicKey: Data, name: String)
        case linkRequest(name: String)
        // PC → iPhone
        case status(name: String, canControl: Bool)
        case paired(publicKey: Data, sealedKey: Data, name: String)
        case rejected(String)
        case codeShown(name: String)

        var json: [String: Any] {
            switch self {
            case .hello(let name, let version): return ["t": "hello", "name": name, "version": version]
            case .move(let dx, let dy): return ["t": "move", "dx": dx, "dy": dy]
            case .button(let right, let down): return ["t": "button", "button": right ? "right" : "left", "down": down]
            case .click(let right): return ["t": "click", "button": right ? "right" : "left"]
            case .scroll(let dx, let dy): return ["t": "scroll", "dx": dx, "dy": dy]
            case .text(let text): return ["t": "text", "text": text]
            case .key(let code, let modifiers): return ["t": "key", "code": code, "modifiers": modifiers.rawValue]
            case .pair(let publicKey, let name):
                return ["t": "pair", "publicKey": publicKey.base64EncodedString(), "name": name]
            case .linkRequest(let name): return ["t": "linkRequest", "name": name]
            case .status(let name, let canControl): return ["t": "status", "name": name, "canControl": canControl]
            case .paired(let publicKey, let sealedKey, let name):
                return ["t": "paired", "publicKey": publicKey.base64EncodedString(),
                        "sealedKey": sealedKey.base64EncodedString(), "name": name]
            case .rejected(let reason): return ["t": "rejected", "reason": reason]
            case .codeShown(let name): return ["t": "codeShown", "name": name]
            }
        }

        init?(json: [String: Any]) {
            func string(_ key: String) -> String? { json[key] as? String }
            func number(_ key: String) -> Double? { (json[key] as? NSNumber)?.doubleValue }
            func flag(_ key: String) -> Bool? { (json[key] as? NSNumber)?.boolValue }
            func data(_ key: String) -> Data? { string(key).flatMap { Data(base64Encoded: $0) } }
            switch string("t") {
            case "hello":
                guard let name = string("name") else { return nil }
                self = .hello(name: name, version: Int(number("version") ?? 0))
            case "move":
                guard let dx = number("dx"), let dy = number("dy") else { return nil }
                self = .move(dx: dx, dy: dy)
            case "button":
                guard let down = flag("down") else { return nil }
                self = .button(right: string("button") == "right", down: down)
            case "click":
                self = .click(right: string("button") == "right")
            case "scroll":
                guard let dx = number("dx"), let dy = number("dy") else { return nil }
                self = .scroll(dx: dx, dy: dy)
            case "text":
                guard let text = string("text") else { return nil }
                self = .text(text)
            case "key":
                guard let code = number("code") else { return nil }
                self = .key(code: Int(code), modifiers: Modifiers(rawValue: Int(number("modifiers") ?? 0)))
            case "pair":
                guard let key = data("publicKey"), let name = string("name") else { return nil }
                self = .pair(publicKey: key, name: name)
            case "linkRequest":
                guard let name = string("name") else { return nil }
                self = .linkRequest(name: name)
            case "status":
                guard let name = string("name") else { return nil }
                self = .status(name: name, canControl: flag("canControl") ?? true)
            case "paired":
                guard let key = data("publicKey"), let sealed = data("sealedKey") else { return nil }
                self = .paired(publicKey: key, sealedKey: sealed, name: string("name") ?? "")
            case "rejected":
                self = .rejected(string("reason") ?? "")
            case "codeShown":
                self = .codeShown(name: string("name") ?? "")
            default:
                return nil
            }
        }

        /// One line of JSON, newline included.
        var line: Data? {
            guard var data = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) else { return nil }
            data.append(0x0A)
            return data
        }

        init?(line: Data) {
            guard let object = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { return nil }
            self.init(json: object)
        }
    }

    // MARK: - Keys and TLS

    /// TLS 1.2 with a pre-shared key, over TCP with Nagle's delay off (pointer
    /// movement is many small messages that shouldn't wait for each other).
    static func parameters(key: SymmetricKey, identity: String) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        let keyData = key.withUnsafeBytes { DispatchData(bytes: $0) }
        let identityData = Data(identity.utf8).withUnsafeBytes { DispatchData(bytes: $0) }
        sec_protocol_options_add_pre_shared_key(options, keyData as __DispatchData, identityData as __DispatchData)
        sec_protocol_options_append_tls_ciphersuite(
            options, tls_ciphersuite_t(rawValue: UInt16(TLS_PSK_WITH_AES_128_GCM_SHA256))!)
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(options, .TLSv12)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        tcp.keepaliveInterval = 2
        tcp.keepaliveCount = 3
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.includePeerToPeer = false
        return parameters
    }

    /// Asking to link happens in the open (plain TCP): it only makes the PC
    /// show a code, which pairing then needs.
    static func linkParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.includePeerToPeer = false
        return parameters
    }

    /// The TLS key for pairing, from the code shown on the PC.
    static func pairingKey(code: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(code.utf8)),
                               salt: Data("Mango Screen Cast For Windows pairing".utf8), info: Data("tls".utf8),
                               outputByteCount: 32)
    }

    /// The key that seals the session key during pairing: from the key
    /// agreement, bound to the code and both public keys.
    static func sealKey(own: Curve25519.KeyAgreement.PrivateKey, peer: Data, code: String, phoneKey: Data,
                        pcKey: Data) throws -> SymmetricKey {
        let peerKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer)
        let secret = try own.sharedSecretFromKeyAgreement(with: peerKey)
        return secret.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: Data(code.utf8),
            sharedInfo: Data("Mango Screen Cast For Windows session key".utf8) + phoneKey + pcKey,
            outputByteCount: 32)
    }

    /// iPhone side of pairing: opens the session key the PC sent.
    static func openPairing(own: Curve25519.KeyAgreement.PrivateKey, pcKey: Data, sealedKey: Data,
                            code: String) throws -> SymmetricKey {
        let key = try sealKey(own: own, peer: pcKey, code: code, phoneKey: own.publicKey.rawRepresentation,
                              pcKey: pcKey)
        let bytes = try ChaChaPoly.open(ChaChaPoly.SealedBox(combined: sealedKey), using: key)
        return SymmetricKey(data: bytes)
    }

    /// PC side of pairing (the PC does this in C++; here for tests).
    static func answerPairing(phoneKey: Data, code: String, sessionKey: SymmetricKey,
                              pcName: String) throws -> Message {
        let own = Curve25519.KeyAgreement.PrivateKey()
        let pcKey = own.publicKey.rawRepresentation
        let key = try sealKey(own: own, peer: phoneKey, code: code, phoneKey: phoneKey, pcKey: pcKey)
        let session = sessionKey.withUnsafeBytes { Data($0) }
        return .paired(publicKey: pcKey, sealedKey: try ChaChaPoly.seal(session, using: key).combined, name: pcName)
    }
}

/// One connection carrying WindowsRemoteProtocol messages as lines of JSON.
/// Callbacks arrive on the queue passed to start().
final class WindowsRemoteChannel {
    let connection: NWConnection
    var onMessage: ((WindowsRemoteProtocol.Message) -> Void)?
    var onState: ((NWConnection.State) -> Void)?

    private var buffer = Data()
    private static let maxLine = 1 << 20

    init(connection: NWConnection) {
        self.connection = connection
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in self?.onState?(state) }
        connection.start(queue: queue)
        receive()
    }

    func send(_ message: WindowsRemoteProtocol.Message) {
        guard let line = message.line else { return }
        connection.send(content: line, completion: .contentProcessed { _ in })
    }

    func cancel() {
        onMessage = nil
        connection.cancel()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                while let end = self.buffer.firstIndex(of: 0x0A) {
                    let line = self.buffer[self.buffer.startIndex..<end]
                    self.buffer.removeSubrange(self.buffer.startIndex...end)
                    if let message = WindowsRemoteProtocol.Message(line: Data(line)) { self.onMessage?(message) }
                }
                if self.buffer.count > Self.maxLine { self.buffer.removeAll() }
            }
            if complete || error != nil {
                self.connection.cancel()
            } else {
                self.receive()
            }
        }
    }
}
