import Combine
import CryptoKit
import Foundation
import Network

/// Lets paired iPads running Mango Cast control this Mac by touching its
/// picture on their screen, and type on it (see RemoteProtocol).
///
/// Two listeners: the session one, always on, which only a device holding
/// the session key can connect to; and the pairing one, only while
/// "Pair an iPad" is open, keyed with the code on screen. Published state is
/// changed on the main thread.
final class RemoteServer: ObservableObject {
    @Published private(set) var connected: [String] = []
    @Published private(set) var canControl = InputInjector.isAllowed
    @Published private(set) var pairingCode: String?
    @Published private(set) var pairedNames: [String]
    @Published private(set) var message: String?

    let macName = Host.current().localizedName ?? "Mac"

    private let queue = DispatchQueue(label: "com.mangoscreencast.remote")
    private let injector = InputInjector()
    private var sessionKey: SymmetricKey
    private var sessionListener: NWListener?
    private var pairingListener: NWListener?
    private var pairingTimeout: DispatchWorkItem?
    /// Open sessions and the iPad's name (server queue).
    private var sessions: [ObjectIdentifier: (channel: RemoteChannel, name: String)] = [:]
    /// Pairing connections in progress (server queue).
    private var pairings: [ObjectIdentifier: RemoteChannel] = [:]
    private var pollTimer: Timer?

    private static let pairedKey = "pairedNames"
    private static let pairingWindow: TimeInterval = 5 * 60

    init() {
        sessionKey = KeyFile.load() ?? KeyFile.create()
        pairedNames = UserDefaults.standard.stringArray(forKey: Self.pairedKey) ?? []
        startSessionListener()
        // Accessibility can be switched on in System Settings at any time.
        pollTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.refreshAccess() }
    }

    // MARK: - Main thread actions

    func requestAccess() {
        InputInjector.requestAccess()
        refreshAccess()
    }

    func startPairing() {
        stopPairing()
        let code = RemoteProtocol.newCode()
        do {
            let listener = try NWListener(using: RemoteProtocol.parameters(key: RemoteProtocol.pairingKey(code: code),
                                                                           identity: RemoteProtocol.pairingIdentity))
            listener.service = NWListener.Service(name: macName, type: RemoteProtocol.pairingServiceType)
            listener.newConnectionHandler = { [weak self] connection in self?.acceptPairing(connection, code: code) }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed(let error) = state {
                    DispatchQueue.main.async { self?.message = "Pairing stopped: \(error.localizedDescription)" }
                    self?.queue.async { listener.cancel() }
                }
            }
            listener.start(queue: queue)
            pairingListener = listener
            pairingCode = code
            message = nil
            let timeout = DispatchWorkItem { [weak self] in self?.stopPairing() }
            pairingTimeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.pairingWindow, execute: timeout)
        } catch {
            message = "Couldn’t start pairing: \(error.localizedDescription)"
        }
    }

    func stopPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = nil
        pairingListener?.cancel()
        pairingListener = nil
        pairingCode = nil
    }

    /// A new session key: every paired iPad has to pair again.
    func forgetAll() {
        sessionKey = KeyFile.create()
        pairedNames = []
        UserDefaults.standard.removeObject(forKey: Self.pairedKey)
        queue.async {
            for session in self.sessions.values { session.channel.cancel() }
            self.sessions.removeAll()
            self.injector.releaseAll()
        }
        connected = []
        startSessionListener()
        message = "Forgot all iPads. Pair again to control this Mac."
    }

    private func refreshAccess() {
        let allowed = InputInjector.isAllowed
        guard allowed != canControl else { return }
        canControl = allowed
        queue.async {
            for session in self.sessions.values {
                session.channel.send(.status(name: self.macName, canControl: allowed))
            }
        }
    }

    // MARK: - Sessions

    private func startSessionListener() {
        sessionListener?.cancel()
        do {
            let listener = try NWListener(using: RemoteProtocol.parameters(key: sessionKey,
                                                                           identity: RemoteProtocol.sessionIdentity))
            listener.service = NWListener.Service(name: macName, type: RemoteProtocol.serviceType)
            listener.newConnectionHandler = { [weak self] connection in self?.acceptSession(connection) }
            listener.stateUpdateHandler = { [weak self] state in
                guard case .failed(let error) = state else { return }
                DispatchQueue.main.async {
                    self?.message = "Stopped listening for iPads (\(error.localizedDescription)); trying again."
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self?.startSessionListener() }
                }
            }
            listener.start(queue: queue)
            sessionListener = listener
        } catch {
            message = "Couldn’t listen for iPads: \(error.localizedDescription)"
        }
    }

    /// Server queue. Only a device with the session key gets past TLS, so
    /// everything that arrives is from a paired iPad.
    private func acceptSession(_ connection: NWConnection) {
        let channel = RemoteChannel(connection: connection)
        let id = ObjectIdentifier(channel)
        sessions[id] = (channel, "iPad")
        channel.onMessage = { [weak self, weak channel] message in
            guard let self, let channel else { return }
            switch message {
            case .hello(let name, _):
                self.sessions[id] = (channel, name)
                self.injector.handle(message)
                channel.send(.status(name: self.macName, canControl: InputInjector.isAllowed))
                self.publishConnected()
            default:
                self.injector.handle(message)
            }
        }
        channel.onState = { [weak self] state in
            guard let self else { return }
            switch state {
            case .failed, .cancelled:
                if self.sessions.removeValue(forKey: id) != nil {
                    if self.sessions.isEmpty { self.injector.releaseAll() }
                    self.publishConnected()
                }
            default:
                break
            }
        }
        channel.start(queue: queue)
    }

    private func publishConnected() {
        let names = sessions.values.map(\.name).sorted()
        DispatchQueue.main.async { self.connected = names }
    }

    // MARK: - Pairing

    /// Server queue. Getting past TLS here proves the iPad has the code.
    private func acceptPairing(_ connection: NWConnection, code: String) {
        let channel = RemoteChannel(connection: connection)
        let id = ObjectIdentifier(channel)
        pairings[id] = channel
        channel.onMessage = { [weak self, weak channel] message in
            guard let self, let channel, case .pair(let iPadKey, let name) = message else { return }
            do {
                let key = DispatchQueue.main.sync { self.sessionKey }
                channel.send(try RemoteProtocol.answerPairing(iPadKey: iPadKey, code: code, sessionKey: key,
                                                              macName: self.macName))
                DispatchQueue.main.async { self.finishPairing(with: name) }
            } catch {
                channel.send(.rejected("Pairing failed: \(error.localizedDescription)"))
            }
            // Let the reply go out, then close.
            self.queue.asyncAfter(deadline: .now() + 2) { channel.cancel() }
        }
        channel.onState = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.pairings.removeValue(forKey: id)?.cancel()
            default: break
            }
        }
        channel.start(queue: queue)
    }

    private func finishPairing(with name: String) {
        if !pairedNames.contains(name) {
            pairedNames.append(name)
            UserDefaults.standard.set(pairedNames, forKey: Self.pairedKey)
        }
        message = "Paired with \(name)."
        stopPairing()
    }
}

/// The session key, in a file only this user can read. (Not the keychain:
/// each new build of an app that isn't signed by a developer account would
/// ask for the keychain password.)
enum KeyFile {
    private static var url: URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Mango Cast Remote", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        return directory.appendingPathComponent("session.key")
    }

    static func load() -> SymmetricKey? {
        guard let data = try? Data(contentsOf: url), data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    static func create() -> SymmetricKey {
        let key = RemoteProtocol.newSessionKey()
        let data = key.withUnsafeBytes { Data($0) }
        FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600])
        return key
    }
}
