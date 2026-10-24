import CoreMedia
import CoreVideo
import Foundation
import MangoProtocol
import Network

public protocol SourceSessionDelegate: AnyObject {
    /// The receiver said what it can show and a mode was picked. Start
    /// capturing at (or, for odd shapes, within) the mode's size and feed
    /// frames to `submitVideo`.
    func sourceSession(_ session: SourceSession, didChoose mode: StreamMode, receiver: Hello)
    func sourceSessionDidStartStreaming(_ session: SourceSession)
    func sourceSession(_ session: SourceSession, didUpdate stats: SourceSession.LiveStats)
    func sourceSession(_ session: SourceSession, didEndWith reason: String)
}

/// The source side of one connection (docs/PROTOCOL.md §3.2): handshake,
/// mode choice, encoding, sending and rate control. Delegate calls arrive on
/// the main queue.
public final class SourceSession {
    public struct Options {
        public var quality: RateController.Quality
        public var codecs: [VideoCodec]
        public var maxFps: Int
        public var content: String
        public var audio: Bool

        public init(quality: RateController.Quality = .balanced, codecs: [VideoCodec] = [.hevc, .h264],
                    maxFps: Int = 60, content: String = "extend", audio: Bool = true) {
            self.quality = quality
            self.codecs = codecs
            self.maxFps = maxFps
            self.content = content
            self.audio = audio
        }
    }

    public struct LiveStats: Equatable {
        public var width = 0
        public var height = 0
        public var fps = 0
        public var codec = ""
        public var bitrateKbps = 0
        public var fecPercent = 0
        public var rttMs: Double?
        public var lossPercent = 0.0
        public var receiverFps = 0
        public var transport = "udp"
    }

    public let connection: ControlConnection
    public let options: Options
    public weak var delegate: SourceSessionDelegate?
    public private(set) var receiver: Hello?
    public private(set) var caps: Caps?
    public private(set) var mode: StreamMode?

    private let queue: DispatchQueue
    private let hello: Hello
    private var encoder: VideoEncoder?
    private var sender: MediaSender?
    private var rate: RateController?
    private var current: Start?
    private var audioChunker = AudioChunker()
    private let pcm = PCMConverter()
    private var timer: DispatchSourceTimer?
    private var stats = LiveStats()
    private var ended = false
    private var notifiedStreaming = false

    public init(connection: ControlConnection, options: Options,
                hello: Hello = DeviceIdentity.hello(role: "source")) {
        self.connection = connection
        self.options = options
        self.hello = hello
        queue = connection.queue
    }

    public var receiverName: String { receiver?.name ?? "TV" }

    public func start() {
        connection.onReady = { [weak self] in
            guard let self else { return }
            self.connection.send(.hello(self.hello))
        }
        connection.onMessage = { [weak self] in self?.handle($0) }
        connection.onClose = { [weak self] error in
            self?.end(reason: error == nil ? "disconnected" : "connection lost", notifyPeer: false)
        }
        connection.start()

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        self.timer = timer
    }

    /// Ends the session and tells the receiver why.
    public func stop(reason: String = "user") {
        queue.async { self.end(reason: reason, notifyPeer: true) }
    }

    // MARK: - Media in

    /// Any thread. The pixel buffer's size becomes the stream size; a change
    /// in size starts a new stream (a new START).
    public func submitVideo(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime) {
        queue.async { [weak self] in self?.encode(pixelBuffer, presentationTime) }
    }

    /// Any thread. Takes any linear PCM sample buffer.
    public func submitAudio(_ sampleBuffer: CMSampleBuffer) {
        queue.async { [weak self] in
            guard let self, self.current?.audio != nil, let data = self.pcm.convert(sampleBuffer) else { return }
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            let micros = pts.isValid ? UInt64(max(0, CMTimeGetSeconds(pts) * 1_000_000)) : MonotonicClock.micros
            self.pushAudio(data, timestamp: micros)
        }
    }

    /// Any thread. `pcm` is already 48 kHz stereo s16le.
    public func submitAudio(pcm data: Data, timestamp: UInt64) {
        queue.async { [weak self] in
            guard let self, self.current?.audio != nil else { return }
            self.pushAudio(data, timestamp: timestamp)
        }
    }

    private func pushAudio(_ data: Data, timestamp: UInt64) {
        guard let sender else { return }
        audioChunker.push(data, timestamp: timestamp) { chunk, ts in
            sender.sendAudio(chunk, timestamp: ts)
        }
    }

    private func encode(_ pixelBuffer: CVPixelBuffer, _ time: CMTime) {
        guard !ended, mode != nil, sender != nil else { return }
        let width = CVPixelBufferGetWidth(pixelBuffer) & ~1
        let height = CVPixelBufferGetHeight(pixelBuffer) & ~1
        if encoder == nil || encoder?.width != width || encoder?.height != height {
            guard reconfigure(width: width, height: height) else { return }
        }
        encoder?.encode(pixelBuffer, presentationTime: time)
    }

    private func reconfigure(width: Int, height: Int) -> Bool {
        guard let mode, let caps, let sender else { return false }
        encoder?.invalidate()
        encoder = nil

        // The chosen mode's codec first, then any other codec the receiver
        // listed at this size or larger.
        var codecs: [VideoCodec] = mode.videoCodec.map { [$0] } ?? []
        for m in caps.modes {
            if let c = m.videoCodec, options.codecs.contains(c), !codecs.contains(c),
               m.width >= width, m.height >= height { codecs.append(c) }
        }
        for codec in codecs {
            let target = RateController.targetBitrateKbps(
                width: width, height: height, fps: mode.fps, codec: codec,
                quality: options.quality, receiverMaxKbps: caps.maxBitrateKbps)
            guard let encoder = try? VideoEncoder(codec: codec, width: width, height: height,
                                                  fps: mode.fps, bitrateKbps: target) else { continue }
            encoder.onFrame = { frame in
                var flags: FrameFlags = []
                if frame.isKeyframe { flags = [.keyframe, .parameterSets] }
                sender.sendVideo(frame.data, timestamp: frame.timestampMicros, flags: flags)
            }
            sender.onCongestion = { [weak encoder] in encoder?.requestKeyframe() }
            let rate = RateController(targetKbps: target)
            self.encoder = encoder
            self.rate = rate

            let session = UInt32.random(in: 1...UInt32.max)
            sender.session = session
            sender.fecPercent = rate.fecPercent
            sender.pacingMegabitsPerSecond = Self.pacing(forKbps: target)
            let audio = options.audio && caps.audio != nil ? AudioFormat.pcm48kStereo : nil
            let start = Start(session: session,
                              video: VideoParams(codec: codec.rawValue, width: width, height: height,
                                                 fps: mode.fps, bitrateKbps: target),
                              audio: audio, transport: sender.tcpFallback == nil ? "udp" : "tcp",
                              content: options.content)
            current = start
            connection.send(.start(start))

            stats.width = width
            stats.height = height
            stats.fps = mode.fps
            stats.codec = codec.rawValue
            stats.bitrateKbps = target
            stats.fecPercent = rate.fecPercent
            if !notifiedStreaming {
                notifiedStreaming = true
                DispatchQueue.main.async { self.delegate?.sourceSessionDidStartStreaming(self) }
            }
            return true
        }
        end(reason: "this Mac can't encode video at \(width)×\(height)", notifyPeer: true)
        return false
    }

    /// Big frames go out at up to 4x the stream's average rate.
    private static func pacing(forKbps kbps: Int) -> Double {
        min(600, max(120, Double(kbps) / 1000 * 4))
    }

    // MARK: - Control

    private func handle(_ message: ControlMessage) {
        guard !ended else { return }
        switch message {
        case .hello(let hello):
            guard hello.role == "receiver" else {
                return end(reason: "\(hello.name) is not a TV", notifyPeer: true)
            }
            receiver = hello
        case .caps(let caps):
            handleCaps(caps)
        case .keyframe:
            encoder?.requestKeyframe()
        case .stats(let report):
            handleStats(report)
        case .ping(let ping):
            connection.send(.pong(ping))
        case .pong(let ping):
            let now = MonotonicClock.micros
            if now >= ping.t { stats.rttMs = Double(now - ping.t) / 1000 }
        case .transport(let request):
            if request.mode == "tcp", let sender, sender.tcpFallback == nil {
                let connection = self.connection
                sender.tcpFallback = { connection.send(.media($0)) }
                stats.transport = "tcp"
                encoder?.requestKeyframe()
            }
        case .stop(let stop):
            end(reason: stop.message ?? Self.describe(stop.reason), notifyPeer: false)
        default:
            break
        }
    }

    private func handleCaps(_ caps: Caps) {
        self.caps = caps
        guard let mode = ModeSelection.choose(from: caps.modes, codecs: options.codecs, maxFps: options.maxFps) else {
            return end(reason: "the TV can't decode any format this device sends", notifyPeer: true)
        }
        guard let host = connection.remoteHost,
              let sender = try? MediaSender(host: host, port: UInt16(clamping: caps.udpPort)) else {
            return end(reason: "couldn't open a media connection to the TV", notifyPeer: true)
        }
        self.sender?.close()
        self.sender = sender
        self.mode = mode
        // A later CAPS (the TV changed display mode) restarts the stream.
        encoder?.invalidate()
        encoder = nil
        let receiver = self.receiver ?? Hello(role: "receiver", id: "", name: "TV", os: "android")
        DispatchQueue.main.async { self.delegate?.sourceSession(self, didChoose: mode, receiver: receiver) }
    }

    private func handleStats(_ report: Stats) {
        guard var rate else { return }
        if rate.update(with: report) { encoder?.setBitrate(kbps: rate.bitrateKbps) }
        self.rate = rate
        sender?.fecPercent = rate.fecPercent
        let received = report.packets ?? 0, lost = report.lostPackets ?? 0
        stats.lossPercent = received + lost > 0 ? Double(lost) * 100 / Double(received + lost) : 0
        stats.bitrateKbps = rate.bitrateKbps
        stats.fecPercent = rate.fecPercent
        stats.receiverFps = report.frames ?? 0
        let snapshot = stats
        DispatchQueue.main.async { self.delegate?.sourceSession(self, didUpdate: snapshot) }
    }

    private func tick() {
        guard !ended else { return }
        if connection.idleSeconds > Mango.peerTimeout {
            return end(reason: "the TV stopped responding", notifyPeer: false)
        }
        connection.send(.ping(Ping(t: MonotonicClock.micros)))
    }

    private func end(reason: String, notifyPeer: Bool) {
        guard !ended else { return }
        ended = true
        timer?.cancel()
        timer = nil
        encoder?.invalidate()
        encoder = nil
        sender?.close()
        sender = nil
        if notifyPeer {
            connection.sendAndClose(.stop(Stop(reason: reason == "user" ? "user" : "error",
                                               message: reason == "user" ? nil : reason)))
        } else {
            connection.close()
        }
        DispatchQueue.main.async { self.delegate?.sourceSession(self, didEndWith: reason) }
    }

    private static func describe(_ reason: String) -> String {
        switch reason {
        case "user": return "stopped on the TV"
        case "busy": return "the TV is showing another device"
        case "replaced": return "another device took over the TV"
        case "unsupported": return "the TV can't show this stream"
        default: return "the TV ended the session"
        }
    }
}
