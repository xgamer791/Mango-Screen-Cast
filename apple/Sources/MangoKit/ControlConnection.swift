import Foundation
import MangoProtocol
import Network

/// One control-channel connection (docs/PROTOCOL.md §3), either direction.
/// Callbacks run on `queue`. `send` may be called from any thread.
public final class ControlConnection {
    public let connection: NWConnection
    public let queue: DispatchQueue

    public var onReady: (() -> Void)?
    public var onMessage: ((ControlMessage) -> Void)?
    public var onClose: ((Error?) -> Void)?

    private var framer = ControlFramer()
    private let lock = NSLock()
    private var closed = false
    private var started = false
    private var lastReceiveNanos = DispatchTime.now().uptimeNanoseconds

    public init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    public convenience init(endpoint: NWEndpoint, queue: DispatchQueue) {
        self.init(connection: NWConnection(to: endpoint, using: Self.parameters()), queue: queue)
    }

    public static func parameters() -> NWParameters {
        let tcp = NWProtocolTCPOptions()
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 2
        tcp.keepaliveInterval = 1
        tcp.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.serviceClass = .responsiveData
        parameters.includePeerToPeer = false
        return parameters
    }

    public func start() {
        guard !started else { return }
        started = true
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onReady?()
            case .waiting(let error), .failed(let error):
                // "Waiting" means unreachable or refused; fail fast so the UI can say so.
                self.close(error)
            case .cancelled:
                self.close(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
        receiveLoop()
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.lastReceiveNanos = DispatchTime.now().uptimeNanoseconds
                do {
                    for message in try self.framer.append(data) { self.onMessage?(message) }
                } catch {
                    self.close(error)
                    return
                }
            }
            if isComplete || error != nil {
                self.close(error)
            } else if !self.isClosed {
                self.receiveLoop()
            }
        }
    }

    public func send(_ message: ControlMessage) {
        guard !isClosed, let data = try? message.framed() else { return }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error { self?.close(error) }
        })
    }

    /// Sends, then closes once the bytes are out (used for STOP).
    public func sendAndClose(_ message: ControlMessage) {
        guard !isClosed, let data = try? message.framed() else { return close(nil) }
        connection.send(content: data, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.queue.asyncAfter(deadline: .now() + 0.2) { self?.close(nil) }
        })
    }

    public var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    public func close(_ error: Error? = nil) {
        lock.lock()
        let wasClosed = closed
        closed = true
        lock.unlock()
        guard !wasClosed else { return }
        connection.cancel()
        queue.async { [weak self] in
            self?.onClose?(error)
            self?.onClose = nil
            self?.onMessage = nil
        }
    }

    /// Seconds since the peer last sent anything.
    public var idleSeconds: Double {
        Double(DispatchTime.now().uptimeNanoseconds - lastReceiveNanos) / 1e9
    }

    /// The peer's address, once connected. Media goes to this host.
    public var remoteHost: NWEndpoint.Host? {
        if case let .hostPort(host, _)? = connection.currentPath?.remoteEndpoint { return host }
        if case let .hostPort(host, _) = connection.endpoint { return host }
        return nil
    }
}

extension NWEndpoint.Host {
    /// Numeric form without interface suffix, for display and filtering.
    public var addressString: String {
        switch self {
        case .ipv4(let address): return "\(address)"
        case .ipv6(let address): return "\(address)"
        case .name(let name, _): return name
        @unknown default: return "\(self)"
        }
    }
}
