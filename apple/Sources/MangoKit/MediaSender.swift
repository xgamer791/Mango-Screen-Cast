import Darwin
import Foundation
import MangoProtocol
import Network

/// Sends video and audio frames to a receiver as FEC-protected UDP datagrams
/// (docs/PROTOCOL.md §4). Video and audio use separate sockets and queues so a
/// large keyframe never delays sound.
public final class MediaSender {
    public enum SenderError: Error {
        case badAddress
        case socket(Int32)
    }

    /// Called (on the send queue) when frames were dropped because the network
    /// fell behind. The encoder should produce a keyframe.
    public var onCongestion: (() -> Void)?

    private let videoSocket: Int32
    private let audioSocket: Int32
    private let videoQueue = DispatchQueue(label: "mango.send.video", qos: .userInteractive)
    private let audioQueue = DispatchQueue(label: "mango.send.audio", qos: .userInteractive)
    private let videoPacketizer = Packetizer()
    private let audioPacketizer = Packetizer()

    private let lock = NSLock()
    private var _session: UInt32 = 0
    private var _fecPercent = RateController.minimumFec
    private var _tcpFallback: ((Data) -> Void)?
    private var videoFrameNumber: UInt32 = 0
    private var audioFrameNumber: UInt32 = 0
    private var pendingVideoFrames = 0
    private var awaitingKeyframe = false
    private var pacer = Pacer(megabitsPerSecond: 200)
    private var closed = false

    public private(set) var packetsSent = 0
    public private(set) var bytesSent = 0
    public private(set) var framesDropped = 0

    public init(host: NWEndpoint.Host, port: UInt16) throws {
        guard let (address, length) = Self.socketAddress(host: host, port: port) else { throw SenderError.badAddress }
        videoSocket = try Self.makeSocket(address: address, length: length)
        do {
            audioSocket = try Self.makeSocket(address: address, length: length)
        } catch {
            Darwin.close(videoSocket)
            throw error
        }
    }

    deinit { close() }

    public var session: UInt32 {
        get { locked { _session } }
        set { locked { _session = newValue } }
    }

    public var fecPercent: Int {
        get { locked { _fecPercent } }
        set { locked { _fecPercent = max(0, min(100, newValue)) } }
    }

    /// Ceiling for how fast a large frame is pushed onto the network.
    public var pacingMegabitsPerSecond: Double {
        get { locked { pacer.megabitsPerSecond } }
        set { locked { pacer.megabitsPerSecond = max(20, newValue) } }
    }

    /// When set, packets go through this closure (the TCP control channel)
    /// instead of UDP.
    public var tcpFallback: ((Data) -> Void)? {
        get { locked { _tcpFallback } }
        set { locked { _tcpFallback = newValue } }
    }

    public func sendVideo(_ frame: Data, timestamp: UInt64, flags: FrameFlags) {
        let isKey = flags.contains(.keyframe)
        lock.lock()
        if closed { lock.unlock(); return }
        if isKey {
            awaitingKeyframe = false
        } else if awaitingKeyframe || pendingVideoFrames >= 3 {
            // Frames are piling up. Sending a frame whose reference was
            // dropped would corrupt the picture, so skip to the next keyframe.
            let first = !awaitingKeyframe
            awaitingKeyframe = true
            framesDropped += 1
            lock.unlock()
            if first { videoQueue.async { [weak self] in self?.onCongestion?() } }
            return
        }
        pendingVideoFrames += 1
        let number = videoFrameNumber
        videoFrameNumber &+= 1
        let session = _session, fec = _fecPercent, fallback = _tcpFallback
        lock.unlock()

        videoQueue.async { [weak self] in
            guard let self else { return }
            frame.withUnsafeBytes { buf in
                self.videoPacketizer.packetize(frame: buf, type: .video, session: session, frameNumber: number,
                                               timestamp: timestamp, flags: flags,
                                               fecPercent: fallback == nil ? fec : 0) { packet in
                    if let fallback {
                        fallback(Data(packet))
                    } else {
                        self.lock.lock()
                        let delay = self.pacer.reserve(bytes: packet.count)
                        self.lock.unlock()
                        if delay > 0 { Pacer.sleep(nanoseconds: delay) }
                        self.write(packet, to: self.videoSocket)
                    }
                }
            }
            self.locked { self.pendingVideoFrames -= 1 }
        }
    }

    /// `chunk` is one audio frame (10 ms of 48 kHz stereo s16le).
    public func sendAudio(_ chunk: Data, timestamp: UInt64) {
        lock.lock()
        if closed { lock.unlock(); return }
        let number = audioFrameNumber
        audioFrameNumber &+= 1
        let session = _session, fallback = _tcpFallback
        lock.unlock()
        audioQueue.async { [weak self] in
            guard let self else { return }
            chunk.withUnsafeBytes { buf in
                // Audio frames are tiny, so 50% parity costs almost nothing.
                self.audioPacketizer.packetize(frame: buf, type: .audio, session: session, frameNumber: number,
                                               timestamp: timestamp, flags: [], fecPercent: fallback == nil ? 50 : 0) { packet in
                    if let fallback { fallback(Data(packet)) } else { self.write(packet, to: self.audioSocket) }
                }
            }
        }
    }

    public func close() {
        lock.lock()
        let wasClosed = closed
        closed = true
        lock.unlock()
        guard !wasClosed else { return }
        // Let queued sends finish before the descriptors go away.
        let video = videoSocket, audio = audioSocket
        videoQueue.async { Darwin.close(video) }
        audioQueue.async { Darwin.close(audio) }
    }

    private func write(_ packet: UnsafeRawBufferPointer, to fd: Int32) {
        var attempts = 0
        while true {
            let n = Darwin.send(fd, packet.baseAddress, packet.count, 0)
            if n >= 0 {
                locked {
                    packetsSent += 1
                    bytesSent += packet.count
                }
                return
            }
            let err = errno
            // The interface queue is full: give it a moment, then give up on this packet.
            guard err == ENOBUFS || err == EAGAIN || err == EINTR, attempts < 40 else { return }
            attempts += 1
            usleep(250)
        }
    }

    @discardableResult
    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    // MARK: - Sockets

    private static func makeSocket(address: sockaddr_storage, length: socklen_t) throws -> Int32 {
        let family = Int32(address.ss_family)
        let fd = socket(family, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw SenderError.socket(errno) }
        var sendBuffer: Int32 = 4 << 20
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sendBuffer, socklen_t(MemoryLayout<Int32>.size))
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        // Ask Wi-Fi for the video access category (WMM AC_VI): DSCP CS5 plus
        // Darwin's network service type.
        var tos: Int32 = 0xA0
        if family == AF_INET {
            setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, socklen_t(MemoryLayout<Int32>.size))
        } else {
            setsockopt(fd, IPPROTO_IPV6, IPV6_TCLASS, &tos, socklen_t(MemoryLayout<Int32>.size))
        }
        var serviceType: Int32 = 3 // NET_SERVICE_TYPE_VI
        setsockopt(fd, SOL_SOCKET, 0x1116 /* SO_NET_SERVICE_TYPE */, &serviceType, socklen_t(MemoryLayout<Int32>.size))

        var target = address
        let result = withUnsafePointer(to: &target) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, length) }
        }
        guard result == 0 else {
            let err = errno
            Darwin.close(fd)
            throw SenderError.socket(err)
        }
        return fd
    }

    static func socketAddress(host: NWEndpoint.Host, port: UInt16) -> (sockaddr_storage, socklen_t)? {
        var storage = sockaddr_storage()
        switch host {
        case .ipv4(let address):
            return ipv4(address, port: port)
        case .ipv6(let address):
            if let mapped = address.asIPv4 { return ipv4(mapped, port: port) }
            var sin6 = sockaddr_in6()
            sin6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            sin6.sin6_family = sa_family_t(AF_INET6)
            sin6.sin6_port = port.bigEndian
            sin6.sin6_scope_id = UInt32(address.interface?.index ?? 0)
            address.rawValue.withUnsafeBytes { raw in
                withUnsafeMutableBytes(of: &sin6.sin6_addr) { $0.copyMemory(from: raw) }
            }
            withUnsafeMutableBytes(of: &storage) { dst in
                withUnsafeBytes(of: &sin6) { dst.copyMemory(from: $0) }
            }
            return (storage, socklen_t(MemoryLayout<sockaddr_in6>.size))
        case .name(let name, _):
            guard let address = IPv4Address(name) else {
                return IPv6Address(name).flatMap { socketAddress(host: .ipv6($0), port: port) }
            }
            return ipv4(address, port: port)
        @unknown default:
            return nil
        }
    }

    private static func ipv4(_ address: IPv4Address, port: UInt16) -> (sockaddr_storage, socklen_t) {
        var storage = sockaddr_storage()
        var sin = sockaddr_in()
        sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        sin.sin_family = sa_family_t(AF_INET)
        sin.sin_port = port.bigEndian
        address.rawValue.withUnsafeBytes { raw in
            withUnsafeMutableBytes(of: &sin.sin_addr) { $0.copyMemory(from: raw) }
        }
        withUnsafeMutableBytes(of: &storage) { dst in
            withUnsafeBytes(of: &sin) { dst.copyMemory(from: $0) }
        }
        return (storage, socklen_t(MemoryLayout<sockaddr_in>.size))
    }
}

/// Token bucket that spreads big frames out instead of bursting them into
/// the access point, which drops bursts.
struct Pacer {
    var megabitsPerSecond: Double
    private var tokens: Double = 0
    private var lastNanos: UInt64 = 0
    private let burstBytes: Double = 128 * 1024

    init(megabitsPerSecond: Double) {
        self.megabitsPerSecond = megabitsPerSecond
        tokens = burstBytes
    }

    /// Takes `bytes` from the bucket and returns how long to wait first, in ns.
    mutating func reserve(bytes: Int) -> UInt64 {
        let now = DispatchTime.now().uptimeNanoseconds
        let bytesPerNano = megabitsPerSecond * 1e6 / 8 / 1e9
        if lastNanos != 0 {
            tokens = min(burstBytes, tokens + Double(now - lastNanos) * bytesPerNano)
        }
        lastNanos = now
        tokens -= Double(bytes)
        guard tokens < 0 else { return 0 }
        // Sleeping in tiny slices costs more than it saves.
        let wait = -tokens / bytesPerNano
        return wait > 150_000 ? UInt64(wait) : 0
    }

    static func sleep(nanoseconds: UInt64) {
        var ts = timespec(tv_sec: Int(nanoseconds / 1_000_000_000), tv_nsec: Int(nanoseconds % 1_000_000_000))
        nanosleep(&ts, nil)
    }
}
