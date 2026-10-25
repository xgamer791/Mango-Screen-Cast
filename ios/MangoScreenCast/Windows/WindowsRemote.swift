import Combine
import CryptoKit
import Network
import Security
import UIKit

/// The iPhone's side of controlling a Windows PC (WindowsRemoteProtocol):
/// finds PCs running Mango Screen Cast For Windows, links with one once
/// using the code it pops up, and sends the touchpad's and keyboard's input.
///
/// Separate from RemoteControl (the Mac's): its own browsers, connections and
/// keychain items. Published state is changed on the main thread.
final class WindowsRemote: ObservableObject {
    enum State: Equatable {
        case off
        /// Looking for a linked PC (none linked: `linkedPCs` is empty).
        case searching
        case connecting(String)
        case connected(String)
        case failed(String)
    }

    @Published private(set) var state = State.off
    /// PCs on the network with Mango Screen Cast For Windows open, linked or not.
    @Published private(set) var pcsAround: [String] = []
    @Published private(set) var linkedPCs: [String] = WindowsRemoteKeys.names()
    /// Asking this PC to pop up a code.
    @Published private(set) var requestingCode: String?
    /// The PC showing a code for this iPhone to enter.
    @Published private(set) var codeShownOn: String?
    @Published private(set) var pairing = false
    @Published var message: String?

    var isConnected: Bool {
        if case .connected = state { return true }
        return false
    }

    private let queue = DispatchQueue(label: "com.mangoscreencast.windowsremote")
    private var sessionBrowser: NWBrowser?
    private var linkBrowser: NWBrowser?
    private var pairingBrowser: NWBrowser?
    private var sessionEndpoints: [String: NWEndpoint] = [:]
    private var linkEndpoints: [String: NWEndpoint] = [:]
    private var pairingEndpoints: [String: NWEndpoint] = [:]
    private var channel: WindowsRemoteChannel?
    private var linkChannel: WindowsRemoteChannel?
    private var pairingChannel: WindowsRemoteChannel?
    private var active = false
    /// The PC picked, when more than one linked PC is around.
    private var chosen: String?
    private var ownName = "iPhone"
    private var retry: DispatchWorkItem?

    // MARK: - Main thread

    /// The Windows PC screen opened: look for PCs, and connect to a linked one.
    func start(as name: String) {
        ownName = name
        active = true
        linkedPCs = WindowsRemoteKeys.names()
        sessionBrowser = browse(WindowsRemoteProtocol.serviceType) { [weak self] endpoints in
            self?.sessionEndpoints = endpoints
            self?.connectIfPossible()
        }
        linkBrowser = browse(WindowsRemoteProtocol.linkServiceType) { [weak self] endpoints in
            self?.linkEndpoints = endpoints
            self?.pcsAround = endpoints.keys.sorted()
        }
        pairingBrowser = browse(WindowsRemoteProtocol.pairingServiceType) { [weak self] endpoints in
            self?.pairingEndpoints = endpoints
        }
        state = .searching
        connectIfPossible()
    }

    func stop() {
        active = false
        retry?.cancel()
        for browser in [sessionBrowser, linkBrowser, pairingBrowser] { browser?.cancel() }
        sessionBrowser = nil
        linkBrowser = nil
        pairingBrowser = nil
        sessionEndpoints = [:]
        linkEndpoints = [:]
        pairingEndpoints = [:]
        pcsAround = []
        for c in [channel, linkChannel, pairingChannel] { c?.cancel() }
        channel = nil
        linkChannel = nil
        pairingChannel = nil
        requestingCode = nil
        codeShownOn = nil
        pairing = false
        state = .off
    }

    func send(_ message: WindowsRemoteProtocol.Message) {
        guard isConnected else { return }
        channel?.send(message)
    }

    /// Connect to this (linked) PC rather than another one.
    func choose(_ pc: String) {
        chosen = pc
        if case .connected(let current) = state, current == pc { return }
        channel?.cancel()
        channel = nil
        connectIfPossible()
    }

    private func browse(_ type: String, _ update: @escaping ([String: NWEndpoint]) -> Void) -> NWBrowser {
        let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { results, _ in
            var endpoints: [String: NWEndpoint] = [:]
            for result in results {
                if case .service(let name, _, _, _) = result.endpoint { endpoints[name] = result.endpoint }
            }
            DispatchQueue.main.async { update(endpoints) }
        }
        browser.start(queue: queue)
        return browser
    }

    /// The PC to control: the one picked, else the only linked PC around.
    private func target() -> (name: String, endpoint: NWEndpoint, key: SymmetricKey)? {
        let candidates = linkedPCs.filter { sessionEndpoints[$0] != nil }
        guard let name = candidates.first(where: { $0 == chosen }) ?? (candidates.count == 1 ? candidates[0] : nil),
              let endpoint = sessionEndpoints[name], let key = WindowsRemoteKeys.load(name) else { return nil }
        return (name, endpoint, key)
    }

    private func connectIfPossible() {
        guard active, channel == nil else { return }
        guard let target = target() else {
            if !isConnected { state = .searching }
            return
        }
        state = .connecting(target.name)
        let connection = NWConnection(to: target.endpoint, using: WindowsRemoteProtocol.parameters(
            key: target.key, identity: WindowsRemoteProtocol.sessionIdentity))
        let channel = WindowsRemoteChannel(connection: connection)
        self.channel = channel
        let name = target.name
        let ownName = self.ownName
        channel.onState = { [weak self, weak channel] state in
            DispatchQueue.main.async {
                guard let self, let channel, self.channel === channel else { return }
                switch state {
                case .ready:
                    channel.send(.hello(name: ownName, version: WindowsRemoteProtocol.version))
                    self.state = .connected(name)
                    self.message = nil
                case .waiting(let error), .failed(let error):
                    self.connectionLost(name, error: error)
                case .cancelled:
                    self.connectionLost(name, error: nil)
                default:
                    break
                }
            }
        }
        channel.start(queue: queue)
    }

    private func connectionLost(_ name: String, error: NWError?) {
        channel?.cancel()
        channel = nil
        guard active else { return }
        if case .tls = error {
            state = .failed("\(name) didn’t accept this iPhone (it was unlinked there). Link again.")
        } else {
            state = .searching
        }
        retry?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.connectIfPossible() }
        retry = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
    }

    // MARK: - Linking

    /// Link: `pc` pops up a code, to enter here (pair(with:code:)).
    func requestCode(from pc: String) {
        guard let endpoint = linkEndpoints[pc] else {
            message = "\(pc) isn’t answering. Check that Mango Screen Cast For Windows is open on it."
            return
        }
        linkChannel?.cancel()
        requestingCode = pc
        codeShownOn = nil
        message = nil
        let channel = WindowsRemoteChannel(connection: NWConnection(to: endpoint,
                                                                    using: WindowsRemoteProtocol.linkParameters()))
        linkChannel = channel
        let ownName = self.ownName
        channel.onState = { [weak self, weak channel] state in
            DispatchQueue.main.async {
                guard let self, let channel, self.linkChannel === channel else { return }
                switch state {
                case .ready:
                    channel.send(.linkRequest(name: ownName))
                case .waiting(let error), .failed(let error):
                    self.linkFailed("Couldn’t reach \(pc) (\(error.localizedDescription)). \(WindowsRemote.reachHint)")
                default:
                    break
                }
            }
        }
        channel.onMessage = { [weak self, weak channel] message in
            DispatchQueue.main.async {
                guard let self, let channel, self.linkChannel === channel else { return }
                switch message {
                case .codeShown:
                    self.requestingCode = nil
                    self.codeShownOn = pc
                    self.linkChannel = nil
                    channel.cancel()
                case .rejected(let reason):
                    self.linkFailed(reason)
                default:
                    break
                }
            }
        }
        channel.start(queue: queue)
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self, weak channel] in
            guard let self, let channel, self.linkChannel === channel else { return }
            self.linkFailed("\(pc) didn’t answer. \(WindowsRemote.reachHint)")
        }
    }

    /// Why a PC that's open might not answer.
    static let reachHint = "Check that it’s on the same Wi-Fi as this iPhone. If Windows Firewall is in the way, "
        + "the PC app’s iPhone control row says so: click it to allow."

    private func linkFailed(_ text: String) {
        linkChannel?.cancel()
        linkChannel = nil
        requestingCode = nil
        message = text
    }

    /// Pairs with `pc` using the code it shows. Getting past TLS at all proves
    /// the code; the session key then comes sealed by a key agreement.
    func pair(with pc: String, code: String, attempt: Int = 0) {
        let digits = code.filter(\.isNumber)
        guard digits.count == 6 else {
            message = "Enter the 6 digits the PC shows."
            return
        }
        guard let endpoint = pairingEndpoints[pc] else {
            // The PC has just popped the code up: give the network a moment to tell.
            if attempt < 12 {
                pairing = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                    self?.pair(with: pc, code: code, attempt: attempt + 1)
                }
                return
            }
            pairing = false
            message = "\(pc) isn’t showing a code any more. Tap Link to get a new one."
            return
        }
        pairingChannel?.cancel()
        pairing = true
        message = nil
        let own = Curve25519.KeyAgreement.PrivateKey()
        let connection = NWConnection(to: endpoint, using: WindowsRemoteProtocol.parameters(
            key: WindowsRemoteProtocol.pairingKey(code: digits), identity: WindowsRemoteProtocol.pairingIdentity))
        let channel = WindowsRemoteChannel(connection: connection)
        pairingChannel = channel
        let ownName = self.ownName
        channel.onState = { [weak self, weak channel] state in
            DispatchQueue.main.async {
                guard let self, let channel, self.pairingChannel === channel else { return }
                switch state {
                case .ready:
                    channel.send(.pair(publicKey: own.publicKey.rawRepresentation, name: ownName))
                case .waiting(let error), .failed(let error):
                    if case .tls = error {
                        self.pairingFailed("That code didn’t work. Check the code on the PC and try again.")
                    } else {
                        self.pairingFailed("Couldn’t reach \(pc): \(error.localizedDescription)")
                    }
                default:
                    break
                }
            }
        }
        channel.onMessage = { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                switch message {
                case .paired(let pcKey, let sealed, let pcName):
                    let name = pcName.isEmpty ? pc : pcName
                    do {
                        let key = try WindowsRemoteProtocol.openPairing(own: own, pcKey: pcKey, sealedKey: sealed,
                                                                        code: digits)
                        WindowsRemoteKeys.save(key, for: name)
                        self.linkedPCs = WindowsRemoteKeys.names()
                        self.message = "Linked with \(name). It stays linked until you unlink it."
                        self.codeShownOn = nil
                        self.pairing = false
                        self.pairingChannel?.cancel()
                        self.pairingChannel = nil
                        self.chosen = name
                        self.connectIfPossible()
                    } catch {
                        self.pairingFailed("Linking failed: the PC’s answer didn’t check out.")
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
            self.pairingFailed("\(pc) didn’t answer. Check that both are on the same Wi-Fi network.")
        }
    }

    private func pairingFailed(_ text: String) {
        pairingChannel?.cancel()
        pairingChannel = nil
        pairing = false
        message = text
    }

    func cancelLinking() {
        linkChannel?.cancel()
        linkChannel = nil
        pairingChannel?.cancel()
        pairingChannel = nil
        requestingCode = nil
        codeShownOn = nil
        pairing = false
    }

    func unlink(_ pc: String) {
        WindowsRemoteKeys.delete(pc)
        linkedPCs = WindowsRemoteKeys.names()
        if case .connected(pc) = state {
            channel?.cancel()
            channel = nil
        }
        if chosen == pc { chosen = nil }
        state = .searching
        connectIfPossible()
    }
}

/// Session keys for linked PCs, in the keychain (this device only), apart
/// from the Mac's (RemoteKeys). iOS keeps keychain items when an app is
/// deleted, so a reinstall is still linked.
enum WindowsRemoteKeys {
    private static let service = "com.mangoscreencast.windowsremote"

    static func names() -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
    }

    static func save(_ key: SymmetricKey, for pc: String) {
        delete(pc)
        let data = key.withUnsafeBytes { Data($0) }
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: pc, kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(item as CFDictionary, nil)
    }

    static func load(_ pc: String) -> SymmetricKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: pc, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data,
              data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    static func delete(_ pc: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: pc,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
