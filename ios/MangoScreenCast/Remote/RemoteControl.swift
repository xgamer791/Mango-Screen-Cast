import Combine
import CryptoKit
import Network
import Security
import UIKit

/// The iPad's side of Mango Cast Remote: finds the Mac helper on the
/// network, pairs with it once using the code it shows, and while mirroring
/// sends touches on the picture and typing to it (see RemoteProtocol).
/// Published state is changed on the main thread.
final class RemoteControl: ObservableObject {
    enum State: Equatable {
        case off
        /// No Mac paired yet.
        case notPaired
        case searching
        case connecting(String)
        case connected(String)
        case failed(String)
    }

    @Published private(set) var state = State.off
    /// Whether the connected Mac has allowed the helper under Accessibility.
    @Published private(set) var canControl = true
    @Published private(set) var pairedMacs: [String] = RemoteKeys.names()
    /// Macs showing a pairing code right now.
    @Published private(set) var macsPairing: [String] = []
    @Published private(set) var pairing = false
    @Published var pairingMessage: String?
    /// In the controls demo (UI tests), what would have gone to the Mac.
    @Published private(set) var demoLog: [String] = []

    var isConnected: Bool {
        if case .connected = state { return true }
        return false
    }

    private let queue = DispatchQueue(label: "com.mangoscreencast.remote")
    private var browser: NWBrowser?
    private var pairingBrowser: NWBrowser?
    private var found: [String: NWEndpoint] = [:]
    private var pairingEndpoints: [String: NWEndpoint] = [:]
    private var channel: RemoteChannel?
    private var pairingChannel: RemoteChannel?
    private var active = false
    private var preferred: String?
    private var ownName = "iPad"
    private var retry: DispatchWorkItem?
    private var demo = false
    /// The picture's size, sent again to each Mac connected.
    private var picture: RemoteProtocol.Message?

    /// The controls demo for UI tests: connected to nothing, and what's sent
    /// is listed in `demoLog`.
    func startDemo() {
        demo = true
        state = .connected("Demo Mac")
        canControl = true
    }

    // MARK: - While mirroring (main thread)

    /// Mirroring started: look for a paired Mac, preferring the one that's
    /// mirroring (AirPlay gives its computer name, which the helper uses).
    func activate(preferring mac: String?, as name: String) {
        guard !demo else { return }
        preferred = mac
        ownName = name
        active = true
        guard !pairedMacs.isEmpty else {
            state = .notPaired
            return
        }
        if channel == nil { state = .searching }
        startBrowsing()
        connectIfPossible()
    }

    func deactivate() {
        guard !demo else { return }
        active = false
        retry?.cancel()
        browser?.cancel()
        browser = nil
        found = [:]
        channel?.cancel()
        channel = nil
        state = .off
    }

    func send(_ message: RemoteProtocol.Message) {
        if case .picture = message { picture = message }
        if demo {
            // Sent from view updates too, which mustn't publish changes themselves.
            let line = message.summary
            DispatchQueue.main.async { self.demoLog = Array((self.demoLog + [line]).suffix(500)) }
            return
        }
        guard isConnected else { return }
        channel?.send(message)
    }

    private func startBrowsing() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjour(type: RemoteProtocol.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            var endpoints: [String: NWEndpoint] = [:]
            for result in results {
                if case .service(let name, _, _, _) = result.endpoint { endpoints[name] = result.endpoint }
            }
            DispatchQueue.main.async {
                self?.found = endpoints
                self?.connectIfPossible()
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    /// The Mac to control: the mirroring one if it's paired and found, else
    /// the only paired Mac that's around.
    private func target() -> (name: String, endpoint: NWEndpoint, key: SymmetricKey)? {
        let candidates = pairedMacs.filter { found[$0] != nil }
        let name = candidates.first { $0 == preferred } ?? (candidates.count == 1 ? candidates[0] : nil)
        guard let name, let endpoint = found[name], let key = RemoteKeys.load(name) else { return nil }
        return (name, endpoint, key)
    }

    private func connectIfPossible() {
        guard active, channel == nil else { return }
        guard let target = target() else {
            if case .connected = state {} else { state = pairedMacs.isEmpty ? .notPaired : .searching }
            return
        }
        state = .connecting(target.name)
        let connection = NWConnection(to: target.endpoint,
                                      using: RemoteProtocol.parameters(key: target.key,
                                                                       identity: RemoteProtocol.sessionIdentity))
        let channel = RemoteChannel(connection: connection)
        self.channel = channel
        let name = target.name
        let ownName = self.ownName
        channel.onState = { [weak self, weak channel] state in
            DispatchQueue.main.async {
                guard let self, let channel, self.channel === channel else { return }
                switch state {
                case .ready:
                    channel.send(.hello(name: ownName, version: RemoteProtocol.version))
                    if let picture = self.picture { channel.send(picture) }
                    self.state = .connected(name)
                case .waiting(let error), .failed(let error):
                    self.connectionLost(name, error: error)
                case .cancelled:
                    self.connectionLost(name, error: nil)
                default:
                    break
                }
            }
        }
        channel.onMessage = { [weak self] message in
            guard case .status(_, let canControl) = message else { return }
            DispatchQueue.main.async { self?.canControl = canControl }
        }
        channel.start(queue: queue)
    }

    private func connectionLost(_ name: String, error: NWError?) {
        channel?.cancel()
        channel = nil
        guard active else { return }
        if case .tls = error {
            state = .failed("\(name) didn’t accept this iPad. On the Mac, choose Pair an iPad, then pair again here.")
        } else {
            state = .searching
        }
        retry?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.connectIfPossible() }
        retry = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
    }

    // MARK: - Pairing (main thread)

    func startLookingForPairing() {
        guard pairingBrowser == nil else { return }
        let browser = NWBrowser(for: .bonjour(type: RemoteProtocol.pairingServiceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            var endpoints: [String: NWEndpoint] = [:]
            for result in results {
                if case .service(let name, _, _, _) = result.endpoint { endpoints[name] = result.endpoint }
            }
            DispatchQueue.main.async {
                self?.pairingEndpoints = endpoints
                self?.macsPairing = endpoints.keys.sorted()
            }
        }
        browser.start(queue: queue)
        pairingBrowser = browser
    }

    func stopLookingForPairing() {
        pairingBrowser?.cancel()
        pairingBrowser = nil
        pairingChannel?.cancel()
        pairingChannel = nil
        macsPairing = []
        pairing = false
    }

    /// Pairs with `mac` using the code it shows. Getting past TLS at all
    /// proves the code; the session key then comes sealed by a key agreement.
    func pair(with mac: String, code: String, as name: String) {
        let digits = code.filter(\.isNumber)
        guard digits.count == 6 else {
            pairingMessage = "Enter the 6 digits the Mac shows."
            return
        }
        guard let endpoint = pairingEndpoints[mac] else {
            pairingMessage = "\(mac) isn’t pairing any more. Choose Pair an iPad on it again."
            return
        }
        pairingChannel?.cancel()
        pairing = true
        pairingMessage = nil
        let own = Curve25519.KeyAgreement.PrivateKey()
        let connection = NWConnection(to: endpoint, using: RemoteProtocol.parameters(
            key: RemoteProtocol.pairingKey(code: digits), identity: RemoteProtocol.pairingIdentity))
        let channel = RemoteChannel(connection: connection)
        pairingChannel = channel
        channel.onState = { [weak self, weak channel] state in
            DispatchQueue.main.async {
                guard let self, let channel, self.pairingChannel === channel else { return }
                switch state {
                case .ready:
                    channel.send(.pair(publicKey: own.publicKey.rawRepresentation, name: name))
                case .waiting(let error), .failed(let error):
                    self.pairingFailed(error.tlsFailure ? "That code didn’t work. Check the code on the Mac and try again."
                                                        : "Couldn’t reach \(mac): \(error.localizedDescription)")
                default:
                    break
                }
            }
        }
        channel.onMessage = { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                switch message {
                case .paired(let macKey, let sealed, let macName):
                    do {
                        let key = try RemoteProtocol.openPairing(own: own, macKey: macKey, sealedKey: sealed, code: digits)
                        RemoteKeys.save(key, for: macName.isEmpty ? mac : macName)
                        self.pairedMacs = RemoteKeys.names()
                        self.pairingMessage = "Paired with \(macName.isEmpty ? mac : macName)."
                        self.pairing = false
                        self.pairingChannel?.cancel()
                        self.pairingChannel = nil
                        if self.active { self.activate(preferring: self.preferred, as: self.ownName) }
                    } catch {
                        self.pairingFailed("Pairing failed: the Mac’s answer didn’t check out.")
                    }
                case .rejected(let reason):
                    self.pairingFailed(reason)
                default:
                    break
                }
            }
        }
        channel.start(queue: queue)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self, weak channel] in
            guard let self, let channel, self.pairingChannel === channel, self.pairing else { return }
            self.pairingFailed("\(mac) didn’t answer. Check that both are on the same Wi-Fi network.")
        }
    }

    private func pairingFailed(_ message: String) {
        pairingChannel?.cancel()
        pairingChannel = nil
        pairing = false
        pairingMessage = message
    }

    func forget(_ mac: String) {
        RemoteKeys.delete(mac)
        pairedMacs = RemoteKeys.names()
        if case .connected(mac) = state {
            channel?.cancel()
            channel = nil
        }
        if active { activate(preferring: preferred, as: ownName) }
    }
}

private extension NWError {
    var tlsFailure: Bool {
        if case .tls = self { return true }
        return false
    }
}

/// Session keys for paired Macs, in the keychain (this device only).
enum RemoteKeys {
    private static let service = "com.mangoscreencast.remote"
    private static let namesKey = "remotePairedMacs"

    static func names() -> [String] {
        UserDefaults.standard.stringArray(forKey: namesKey) ?? []
    }

    static func save(_ key: SymmetricKey, for mac: String) {
        delete(mac, keepName: true)
        let data = key.withUnsafeBytes { Data($0) }
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: mac, kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(item as CFDictionary, nil)
        var list = names()
        if !list.contains(mac) { list.append(mac) }
        UserDefaults.standard.set(list, forKey: namesKey)
    }

    static func load(_ mac: String) -> SymmetricKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: mac, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data,
              data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    static func delete(_ mac: String, keepName: Bool = false) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: mac,
        ]
        SecItemDelete(query as CFDictionary)
        if !keepName {
            UserDefaults.standard.set(names().filter { $0 != mac }, forKey: namesKey)
        }
    }
}

extension RemoteProtocol.Message {
    /// A short line for the controls demo's log.
    var summary: String {
        func f(_ value: Double) -> String { String(format: "%.2f", value) }
        func d(_ value: Double) -> String { String(format: "%+.4f", value) }
        switch self {
        case .hello(let name, _): return "hello \(name)"
        case .picture(let width, let height): return "picture \(width)x\(height)"
        case .point(let fx, let fy): return "point \(f(fx)) \(f(fy))"
        case .click(let fx, let fy, let button, let count): return "click \(f(fx)) \(f(fy)) \(button.rawValue) \(count)"
        case .press(let fx, let fy, let button, let down, let count):
            return "press \(f(fx)) \(f(fy)) \(button.rawValue) \(down ? "down" : "up") \(count)"
        case .wheel(let fx, let fy, let fdx, let fdy): return "wheel \(f(fx)) \(f(fy)) \(d(fdx)) \(d(fdy))"
        case .text(let text): return "text \(text)"
        case .key(let code, let modifiers): return "key \(code)\(modifiers.isEmpty ? "" : " mods \(modifiers.rawValue)")"
        case .pair: return "pair"
        case .status(let name, let canControl): return "status \(name) \(canControl)"
        case .paired: return "paired"
        case .rejected(let reason): return "rejected \(reason)"
        }
    }
}
