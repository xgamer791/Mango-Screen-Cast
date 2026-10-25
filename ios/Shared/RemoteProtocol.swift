import CryptoKit
import Foundation
import Network
import Security

/// Mango Cast Remote: the iPad app sends touches on the mirrored picture
/// (taps, swipes) and typing to a small helper app on the Mac, which plays
/// them there. AirPlay only carries the picture and sound one way, so this
/// is a connection of its own.
///
/// Security: every connection is TLS with a pre-shared key, so it's
/// encrypted and only a device holding the key gets in.
///  - Linking: Link on the iPad asks the Mac (`linkRequest`, over plain
///    TCP: nothing secret goes this way), and the Mac pops up a 6-digit code.
///  - Pairing: with the Mac showing a code (popped up by Link, or from its
///    menu's Pair an iPad), the iPad's code is entered.
///    The iPad connects with a key derived from that code, the two do a
///    Curve25519 key agreement, and the Mac sends its session key sealed
///    with the result. Someone who records the pairing and later guesses
///    the code still can't open the session key.
///  - Sessions: the iPad connects with the session key from then on.
///    "Forget paired iPads" on the Mac makes a new one.
///
/// Messages are JSON, one per line.
enum RemoteProtocol {
    static let serviceType = "_mangoremote._tcp"
    static let pairingServiceType = "_mangopair._tcp"
    static let linkServiceType = "_mangolink._tcp"
    static let version = 1

    enum MouseButton: String, Codable {
        case left, right
    }

    struct Modifiers: OptionSet, Codable, Hashable {
        let rawValue: UInt8
        static let command = Modifiers(rawValue: 1)
        static let option = Modifiers(rawValue: 2)
        static let control = Modifiers(rawValue: 4)
        static let shift = Modifiers(rawValue: 8)
    }

    /// Positions are shares of the picture the iPad shows (0 to 1 across and
    /// down), which the Mac maps onto the screen it's sending there.
    enum Message: Codable, Equatable {
        // iPad → Mac
        /// `name`: the receiver's name, which is what the Mac calls the
        /// AirPlay display it sends to.
        case hello(name: String, version: Int)
        /// The size of the picture being shown, when it's known or changes.
        case picture(width: Int, height: Int)
        /// The pointer goes there (a mouse moving over the picture, or
        /// dragging with a button held).
        case point(fx: Double, fy: Double)
        /// A click there; `count` 2 or 3 makes it part of a double or
        /// triple click.
        case click(fx: Double, fy: Double, button: MouseButton, count: Int)
        /// A mouse button pressed or let go there.
        case press(fx: Double, fy: Double, button: MouseButton, down: Bool, count: Int)
        /// The wheel turned there by (fdx, fdy) shares of the picture; positive
        /// scrolls down and right, as a web page's wheel does.
        case wheel(fx: Double, fy: Double, fdx: Double, fdy: Double)
        /// Characters to type, whatever the Mac's keyboard layout.
        case text(String)
        /// One key press with modifiers held (a Mac virtual key code).
        case key(code: UInt16, modifiers: Modifiers)
        case pair(publicKey: Data, name: String)
        /// Link asked for on the iPad: the Mac pops up a code to enter there.
        case linkRequest(name: String)
        // Mac → iPad
        case status(name: String, canControl: Bool)
        case paired(publicKey: Data, sealedKey: Data, name: String)
        case rejected(String)
        /// The Mac is showing a code for the iPad that asked to link.
        case codeShown(macName: String)
    }

    // MARK: - Keys and TLS

    static let sessionIdentity = "mango-remote-session"
    static let pairingIdentity = "mango-remote-pairing"

    /// TLS with a pre-shared key, over TCP with Nagle's delay off (pointer
    /// movement is many small messages that shouldn't wait for each other).
    static func parameters(key: SymmetricKey, identity: String) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let keyData = key.withUnsafeBytes { DispatchData(bytes: $0) }
        let identityData = Data(identity.utf8).withUnsafeBytes { DispatchData(bytes: $0) }
        sec_protocol_options_add_pre_shared_key(tls.securityProtocolOptions, keyData as __DispatchData,
                                                identityData as __DispatchData)
        sec_protocol_options_append_tls_ciphersuite(
            tls.securityProtocolOptions, tls_ciphersuite_t(rawValue: UInt16(TLS_PSK_WITH_AES_128_GCM_SHA256))!)
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

    /// Asking to link happens in the open (plain TCP): it only makes the Mac
    /// show a code, which the pairing then needs.
    static func linkParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.includePeerToPeer = false
        return parameters
    }

    static func newCode() -> String {
        String(format: "%06d", Int.random(in: 0..<1_000_000))
    }

    static func newSessionKey() -> SymmetricKey {
        SymmetricKey(size: .bits256)
    }

    /// The TLS key for pairing, from the code shown on the Mac.
    static func pairingKey(code: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(code.utf8)),
                               salt: Data("Mango Cast Remote pairing".utf8), info: Data("tls".utf8),
                               outputByteCount: 32)
    }

    /// The key that seals the session key during pairing: from the key
    /// agreement, bound to the code and both public keys.
    static func pairingSealKey(own: Curve25519.KeyAgreement.PrivateKey, peer: Data, code: String,
                               iPadKey: Data, macKey: Data) throws -> SymmetricKey {
        let peerKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer)
        let secret = try own.sharedSecretFromKeyAgreement(with: peerKey)
        return secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(code.utf8),
                                              sharedInfo: Data("Mango Cast Remote session key".utf8) + iPadKey + macKey,
                                              outputByteCount: 32)
    }

    /// Mac side of pairing: answers the iPad's public key with its own and
    /// the session key, sealed.
    static func answerPairing(iPadKey: Data, code: String, sessionKey: SymmetricKey,
                              macName: String) throws -> Message {
        let own = Curve25519.KeyAgreement.PrivateKey()
        let macKey = own.publicKey.rawRepresentation
        let sealKey = try pairingSealKey(own: own, peer: iPadKey, code: code, iPadKey: iPadKey, macKey: macKey)
        let sessionBytes = sessionKey.withUnsafeBytes { Data($0) }
        let sealed = try ChaChaPoly.seal(sessionBytes, using: sealKey).combined
        return .paired(publicKey: macKey, sealedKey: sealed, name: macName)
    }

    /// iPad side of pairing: opens the session key the Mac sent.
    static func openPairing(own: Curve25519.KeyAgreement.PrivateKey, macKey: Data, sealedKey: Data,
                            code: String) throws -> SymmetricKey {
        let sealKey = try pairingSealKey(own: own, peer: macKey, code: code,
                                         iPadKey: own.publicKey.rawRepresentation, macKey: macKey)
        let bytes = try ChaChaPoly.open(ChaChaPoly.SealedBox(combined: sealedKey), using: sealKey)
        return SymmetricKey(data: bytes)
    }
}

/// One connection, carrying RemoteProtocol messages as lines of JSON. All
/// callbacks arrive on the queue passed to start().
final class RemoteChannel {
    let connection: NWConnection
    var onMessage: ((RemoteProtocol.Message) -> Void)?
    var onState: ((NWConnection.State) -> Void)?

    private var buffer = Data()
    private static let maxLine = 1 << 20
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(connection: NWConnection) {
        self.connection = connection
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in self?.onState?(state) }
        connection.start(queue: queue)
        receive()
    }

    func send(_ message: RemoteProtocol.Message) {
        guard var data = try? encoder.encode(message) else { return }
        data.append(0x0A)
        connection.send(content: data, completion: .contentProcessed { _ in })
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
                    if let message = try? self.decoder.decode(RemoteProtocol.Message.self, from: line) {
                        self.onMessage?(message)
                    }
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
