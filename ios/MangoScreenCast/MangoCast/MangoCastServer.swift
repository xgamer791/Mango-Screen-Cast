import Foundation
import Network

/// Where a Mango Cast session's pictures and sound go. Called on the server's queue.
protocol MangoCastSink: AnyObject {
    /// A sender said HELLO: welcome it with an offer, or refuse it.
    func mangoCastHello(_ hello: Hello) -> Decision
    /// WELCOME went out; pictures and sound follow.
    func mangoCastStarted(_ session: MangoCastServer.Session)
    /// One access unit (Annex B), without the timestamp. Only valid during the call.
    func mangoCastVideo(_ session: MangoCastServer.Session, _ frame: UnsafeRawBufferPointer)
    /// PCM, without the timestamp. Only valid during the call.
    func mangoCastAudio(_ session: MangoCastServer.Session, _ pcm: UnsafeRawBufferPointer)
    /// The session that `mangoCastStarted` began is over; `lost` when the
    /// connection dropped rather than either side ending it. Once per session.
    func mangoCastEnded(_ session: MangoCastServer.Session, reason: String, lost: Bool)
}

/// Listens for Mango Cast senders (Windows computers) on the local network
/// and hands what they send to its sink. One sender at a time; others are
/// refused as busy. Everything runs on one serial queue.
final class MangoCastServer {
    weak var sink: MangoCastSink?
    var log: ((String) -> Void)?

    private let queue = DispatchQueue(label: "com.mangoscreencast.mangocast")
    private let queueKey = DispatchSpecificKey<Void>()
    private var listener: NWListener?
    private var current: Session?
    private var listening: UInt16?
    /// Every open connection, held here until it ends: their callbacks only hold them weakly.
    private var sessions: [ObjectIdentifier: Session] = [:]

    init() {
        queue.setSpecific(key: queueKey, value: ())
    }

    /// The port it listens on, once it does.
    var port: UInt16? { onQueue { listening } }

    /// The sender whose pictures are showing, if one is.
    var session: Session? { onQueue { current } }

    /// Listens on `preferredPort` if it's free, or any port, and announces
    /// `_mangocast._tcp` as `name` with `txt` (nil: not announced, for tests).
    /// `ready` gets the port, or nil, on the server's queue.
    func start(name: String?, txt: [String: String] = [:], preferredPort: UInt16 = MangoCast.defaultPort,
               ready: ((UInt16?) -> Void)? = nil) {
        queue.async {
            guard self.listener == nil else {
                ready?(self.listening)
                return
            }
            self.listen(port: preferredPort, name: name, txt: txt, ready: ready)
        }
    }

    /// Stops listening and ends every connection, the connected sender's first.
    func stop(reason: String) {
        onQueue {
            listener?.cancel()
            listener = nil
            listening = nil
            current?.end(reason, sendBye: true)
            Array(sessions.values).forEach { $0.end(reason, sendBye: false) }
        }
    }

    /// Ends the connected sender's session, if there is one, telling it why.
    func disconnect(_ reason: String) {
        onQueue { current?.end(reason, sendBye: true) }
    }

    private func onQueue<T>(_ work: () -> T) -> T {
        DispatchQueue.getSpecific(key: queueKey) != nil ? work() : queue.sync(execute: work)
    }

    private func listen(port: UInt16, name: String?, txt: [String: String], ready: ((UInt16?) -> Void)?) {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener: NWListener
        do {
            listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port) ?? .any)
        } catch {
            if port != 0 {
                log?("Windows: port \(port) unavailable (\(error)); using another")
                listen(port: 0, name: name, txt: txt, ready: ready)
            } else {
                log?("Windows: couldn't listen: \(error)")
                ready?(nil)
            }
            return
        }
        if let name {
            listener.service = NWListener.Service(name: name, type: MangoCast.serviceType, domain: nil,
                                                  txtRecord: NWTXTRecord(txt))
            listener.serviceRegistrationUpdateHandler = { [weak self] change in
                if case .add(let endpoint) = change { self?.log?("advertising \(MangoCast.serviceType) as \(endpoint)") }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return connection.cancel() }
            let session = Session(server: self, connection: connection)
            self.sessions[ObjectIdentifier(session)] = session
            session.begin()
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener, listener === self.listener else { return }
            switch state {
            case .ready:
                self.listening = listener.port?.rawValue
                ready?(self.listening)
            case .failed(let error):
                listener.cancel()
                self.listener = nil
                if port != 0 {
                    self.log?("Windows: port \(port) is taken (\(error)); using another")
                    self.listen(port: 0, name: name, txt: txt, ready: ready)
                } else {
                    self.log?("Windows: listening failed: \(error)")
                    ready?(nil)
                }
            default:
                break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    /// One sender's connection. Its state lives on the server's queue.
    final class Session {
        /// What it said about itself. Set before the sink hears of it.
        private(set) var hello = Hello(protocolVersion: 0, name: "Windows", os: "Windows", codecs: [], width: 0,
                                       height: 0, fps: 60, audio: false)
        /// `h265` or `h264`, as WELCOME told it.
        private(set) var codec = ""
        /// The size it will send, from its screen and the offer (the stream says for sure).
        private(set) var width = 0
        private(set) var height = 0
        /// Whether it was asked to send sound.
        private(set) var audio = false

        var h265: Bool { codec == MangoCast.h265 }
        let address: String

        private weak var server: MangoCastServer?
        private let connection: NWConnection
        private var started = false
        private var ended = false
        private var lastReceived = Date()
        private var lastSent = Date()
        private var lastKeyframeRequest = Date.distantPast
        private var timer: DispatchSourceTimer?

        fileprivate init(server: MangoCastServer, connection: NWConnection) {
            self.server = server
            self.connection = connection
            if case .hostPort(let host, _) = connection.endpoint { address = "\(host)" } else { address = "?" }
        }

        private var queue: DispatchQueue? { server?.queue }

        /// Asks the sender for a keyframe (the picture needs one). At most twice a second; any thread.
        func requestKeyframe() {
            queue?.async {
                guard !self.ended, Date().timeIntervalSince(self.lastKeyframeRequest) >= 0.5 else { return }
                self.lastKeyframeRequest = Date()
                self.send(.keyframe)
            }
        }

        fileprivate func begin() {
            guard let queue else { return connection.cancel() }
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .failed, .cancelled:
                    self.end(self.started ? "Lost the connection to \(self.hello.name)." : "The connection closed.",
                             sendBye: false, lost: true)
                default:
                    break
                }
            }
            connection.start(queue: queue)
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in self?.tick() }
            timer.resume()
            self.timer = timer
            receive { [weak self] type, payload in self?.handshake(type, payload) }
        }

        /// Silence and keepalive, once a second.
        private func tick() {
            let quiet = Date().timeIntervalSince(lastReceived)
            if !started && quiet > MangoCast.helloTimeout {
                end("No HELLO.", sendBye: false, lost: true)
            } else if started && quiet > MangoCast.silenceTimeout {
                end("No word from \(hello.name) for \(Int(MangoCast.silenceTimeout)) seconds.", sendBye: true, lost: true)
            } else if started && Date().timeIntervalSince(lastSent) >= MangoCast.keepAlive {
                var now = UInt64(Date().timeIntervalSince1970 * 1_000_000).bigEndian
                send(.ping, Data(bytes: &now, count: 8))
            }
        }

        /// Reads one message, then calls `handle`.
        private func receive(_ handle: @escaping (UInt8, Data) -> Void) {
            connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { [weak self] data, _, complete, error in
                guard let self, !self.ended else { return }
                guard let data, let header = MangoCast.header(data) else {
                    if error != nil || complete { self.connectionClosed() }
                    return
                }
                guard header.length <= MangoCast.maxPayload else {
                    self.protocolError("a \(header.length)-byte message")
                    return
                }
                guard header.length > 0 else {
                    self.lastReceived = Date()
                    handle(header.type, Data())
                    return
                }
                self.connection.receive(minimumIncompleteLength: header.length, maximumLength: header.length) {
                    [weak self] payload, _, complete, error in
                    guard let self, !self.ended else { return }
                    guard let payload, payload.count == header.length else {
                        if error != nil || complete { self.connectionClosed() }
                        return
                    }
                    self.lastReceived = Date()
                    handle(header.type, payload)
                }
            }
        }

        private func connectionClosed() {
            end(started ? "Lost the connection to \(hello.name)." : "The connection closed.", sendBye: false, lost: true)
        }

        private func protocolError(_ what: String) {
            server?.log?("Windows: \(address) broke the protocol: \(what)")
            end("Mango Cast protocol error: \(what)", sendBye: true, lost: true)
        }

        /// HELLO in, WELCOME or REFUSE out.
        private func handshake(_ type: UInt8, _ payload: Data) {
            guard let server else { return end("The server stopped.", sendBye: false) }
            guard type == MangoCast.Message.hello.rawValue else { return protocolError("expected HELLO, got type \(type)") }
            guard let hello = Hello.parse(payload) else { return protocolError("HELLO isn't JSON") }
            self.hello = hello
            server.log?("Windows: HELLO from \(hello.name) (\(address), \(hello.os)): \(hello.width)x\(hello.height) "
                + "@ \(hello.fps) fps, codecs \(hello.codecs), audio \(hello.audio)")

            let decision: Decision
            if hello.protocolVersion != MangoCast.version {
                decision = .refuse(reason: "protocol",
                                   message: "This device speaks Mango Cast \(MangoCast.version); update Mango Cast for Windows or Mango.")
            } else if let busy = server.current {
                decision = .refuse(reason: "busy", message: "\(busy.hello.name) is showing here right now.")
            } else {
                decision = server.sink?.mangoCastHello(hello)
                    ?? .refuse(reason: "off", message: "This device isn't ready yet. Try again in a moment.")
            }
            // An iPad decodes H.264 as big as its screen, so both codecs get the same offer.
            guard case .accept(let offer) = decision else {
                if case .refuse(let reason, let message) = decision { refuse(reason, message) }
                return
            }
            guard let codec = offer.codec(for: hello) else {
                return refuse("protocol", "This device can't decode \(hello.codecs.joined(separator: " or ")).")
            }
            server.current = self
            self.codec = codec
            let picture = offer.picture(for: hello)
            width = picture.width
            height = picture.height
            audio = offer.audio && hello.audio
            send(.welcome, offer.welcome(codec: codec, hello: hello))
            started = true
            server.log?("Windows: welcomed \(hello.name): \(codec) up to \(offer.maxWidth)x\(offer.maxHeight) "
                + "@ \(offer.fps) fps, sound \(audio ? "on" : "off")")
            server.sink?.mangoCastStarted(self)
            receive { [weak self] type, payload in self?.handle(type, payload) }
        }

        private func refuse(_ reason: String, _ message: String) {
            server?.log?("Windows: refused \(hello.name) (\(reason)): \(message)")
            send(.refuse, Decision.refusal(reason: reason, message: message))
            end(message, sendBye: false)
        }

        private func handle(_ type: UInt8, _ payload: Data) {
            switch MangoCast.Message(rawValue: type) {
            case .video where payload.count > MangoCast.timestampBytes:
                payload.withUnsafeBytes { raw in
                    server?.sink?.mangoCastVideo(self, UnsafeRawBufferPointer(rebasing: raw[MangoCast.timestampBytes...]))
                }
            case .audio where audio && payload.count > MangoCast.timestampBytes:
                payload.withUnsafeBytes { raw in
                    server?.sink?.mangoCastAudio(self, UnsafeRawBufferPointer(rebasing: raw[MangoCast.timestampBytes...]))
                }
            case .ping:
                send(.pong, payload)
            case .bye:
                let why = String(decoding: payload, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                end(why.isEmpty ? "\(hello.name) stopped." : "\(hello.name) stopped: \(why)", sendBye: false)
                return
            default:
                // PONG only shows it's there; anything else is from a newer version.
                break
            }
            if !ended { receive { [weak self] type, payload in self?.handle(type, payload) } }
        }

        private func send(_ type: MangoCast.Message, _ payload: Data = Data(), then: (() -> Void)? = nil) {
            lastSent = Date()
            connection.send(content: MangoCast.frame(type, payload), completion: .contentProcessed { _ in then?() })
        }

        /// Ends this session: BYE with `reason` (if asked), then the connection closes. Server's queue.
        fileprivate func end(_ reason: String, sendBye: Bool, lost: Bool = false) {
            guard !ended else { return }
            ended = true
            timer?.cancel()
            timer = nil
            let connection = self.connection
            connection.stateUpdateHandler = nil
            if sendBye {
                send(.bye, Data(reason.utf8)) { connection.cancel() }
                // If the BYE can't go out, don't wait for it.
                queue?.asyncAfter(deadline: .now() + 0.5) { connection.cancel() }
            } else {
                // Let a REFUSE already queued go out first.
                connection.send(content: nil, contentContext: .finalMessage, isComplete: true,
                                completion: .contentProcessed { _ in connection.cancel() })
            }
            guard let server else { return }
            server.sessions[ObjectIdentifier(self)] = nil
            guard server.current === self else { return }
            server.current = nil
            if started {
                server.log?("Windows: \(hello.name) ended: \(reason)")
                server.sink?.mangoCastEnded(self, reason: reason, lost: lost)
            }
        }
    }
}
