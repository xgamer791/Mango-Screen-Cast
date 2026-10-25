import Combine
import CryptoKit
import Foundation
import Network
import ServiceManagement

/// Lets paired iPads running Mango Cast control this Mac by touching its
/// picture on their screen, and type on it (see RemoteProtocol).
///
/// Three listeners: the session one, always on, which only a device holding
/// the session key can connect to; the link one, always on, where an iPad's
/// Link makes a code pop up here; and the pairing one, only while a code is
/// showing, keyed with it. Published state is changed on the main thread.
final class RemoteServer: ObservableObject {
    @Published private(set) var connected: [String] = []
    @Published private(set) var canControl = InputInjector.isAllowed
    @Published private(set) var pairingCode: String?
    /// The iPad that asked for the code showing, if one did.
    @Published private(set) var pairingFor: String?
    @Published private(set) var pairedNames: [String]
    @Published private(set) var message: String?

    let macName = Host.current().localizedName ?? "Mac"

    private let queue = DispatchQueue(label: "com.mangoscreencast.remote")
    private let injector = InputInjector()
    private var sessionKey: SymmetricKey
    private var sessionListener: NWListener?
    private var pairingListener: NWListener?
    private var linkListener: NWListener?
    /// Link requests being answered (server queue).
    private var linkRequests: [ObjectIdentifier: RemoteChannel] = [:]
    private var pairingTimeout: DispatchWorkItem?
    /// Open sessions and the iPad's name (server queue).
    private var sessions: [ObjectIdentifier: (channel: RemoteChannel, name: String)] = [:]
    /// Pairing connections in progress (server queue).
    private var pairings: [ObjectIdentifier: RemoteChannel] = [:]
    private var pollTimer: Timer?

    private static let pairedKey = "pairedNames"
    private static let setUpKey = "setUpOnFirstLaunch"
    private static let pairingWindow: TimeInterval = 5 * 60

    init() {
        sessionKey = KeyFile.load() ?? KeyFile.create()
        pairedNames = UserDefaults.standard.stringArray(forKey: Self.pairedKey) ?? []
        startSessionListener()
        startLinkListener()
        // The first time: ask for Accessibility (what lets it click and type)
        // and open at login, so it's simply there from then on.
        if !UserDefaults.standard.bool(forKey: Self.setUpKey) {
            UserDefaults.standard.set(true, forKey: Self.setUpKey)
            DispatchQueue.main.async {
                if !InputInjector.isAllowed { InputInjector.requestAccess() }
                try? SMAppService.mainApp.register()
            }
        }
        // Accessibility can be switched on in System Settings at any time.
        pollTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.refreshAccess() }
    }

    // MARK: - Main thread actions

    func requestAccess() {
        InputInjector.requestAccess()
        refreshAccess()
    }

    /// Shows a code for an iPad to enter (`name`: the iPad that asked, if one
    /// did). An iPad asking again while a code shows gets the same one.
    func startPairing(for name: String? = nil) {
        if pairingCode != nil, let name, pairingFor == name {
            CodeWindow.shared.show(server: self)
            return
        }
        stopPairing()
        pairingFor = name
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
            CodeWindow.shared.show(server: self)
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
        pairingFor = nil
        CodeWindow.shared.close()
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
        message = "Unlinked all iPads. Link again to control this Mac from one."
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

    // MARK: - Linking

    private func startLinkListener() {
        linkListener?.cancel()
        do {
            let listener = try NWListener(using: RemoteProtocol.linkParameters())
            listener.service = NWListener.Service(name: macName, type: RemoteProtocol.linkServiceType)
            listener.newConnectionHandler = { [weak self] connection in self?.acceptLinkRequest(connection) }
            listener.stateUpdateHandler = { [weak self] state in
                guard case .failed = state else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self?.startLinkListener() }
            }
            listener.start(queue: queue)
            linkListener = listener
        } catch {
            message = "Couldn’t listen for iPads asking to link: \(error.localizedDescription)"
        }
    }

    /// Server queue. Link on an iPad: a code pops up here to enter there.
    /// Nothing secret goes this way; the code proves it in pairing.
    private func acceptLinkRequest(_ connection: NWConnection) {
        let channel = RemoteChannel(connection: connection)
        let id = ObjectIdentifier(channel)
        linkRequests[id] = channel
        channel.onMessage = { [weak self, weak channel] message in
            guard let self, let channel, case .linkRequest(let name) = message else { return }
            DispatchQueue.main.async {
                self.startPairing(for: name)
                let shown = self.pairingCode != nil
                self.queue.async {
                    channel.send(shown ? .codeShown(macName: self.macName)
                                       : .rejected("\(self.macName) couldn’t show a code. Open Mango Cast Remote on it and try again."))
                    self.queue.asyncAfter(deadline: .now() + 2) { channel.cancel() }
                }
            }
        }
        channel.onState = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.linkRequests.removeValue(forKey: id)?.cancel()
            default: break
            }
        }
        channel.start(queue: queue)
        // Whatever happens, a request doesn't hang around.
        queue.asyncAfter(deadline: .now() + 30) { [weak self] in self?.linkRequests.removeValue(forKey: id)?.cancel() }
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
        message = "Linked with \(name). It stays linked until you choose Unlink all iPads."
        stopPairing()
        if !canControl { requestAccess() }
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
