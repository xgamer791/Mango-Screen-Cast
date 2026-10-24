import Darwin
import Foundation
import MangoProtocol
import Network

/// A Mango TV found on the network, by Bonjour, beacon, or both.
public struct DiscoveredReceiver: Identifiable, Hashable {
    public let id: String
    public var name: String
    public var model: String?
    public var width: Int?
    public var height: Int?
    /// Bonjour service endpoint, when Bonjour found it.
    public var service: NWEndpoint?
    /// Address and port from a beacon, when a beacon found it.
    public var host: String?
    public var port: UInt16?
    var lastBeacon: Date?

    public var endpoint: NWEndpoint? {
        if let host, let port, let nwPort = NWEndpoint.Port(rawValue: port) {
            return .hostPort(host: NWEndpoint.Host(host), port: nwPort)
        }
        return service
    }

    /// "4K", "1440p", ... for the UI.
    public var resolutionLabel: String? {
        guard let width, let height else { return nil }
        switch max(width, height) {
        case 3800...: return "4K"
        case 2500..<3800: return "1440p"
        case 1900..<2500: return "1080p"
        default: return "\(height)p"
        }
    }
}

/// Finds Mango TVs. Bonjour results come from NWBrowser; macOS also feeds in
/// UDP beacons through `ingest`. Callbacks run on the main queue.
public final class ReceiverBrowser {
    public var onChange: (([DiscoveredReceiver]) -> Void)?
    public private(set) var receivers: [DiscoveredReceiver] = []

    private var browser: NWBrowser?
    private var bonjour: [String: DiscoveredReceiver] = [:]
    private var beacons: [String: DiscoveredReceiver] = [:]
    private var expiryTimer: Timer?

    public init() {}

    public func start() {
        guard browser == nil else { return }
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Mango.receiverServiceType, domain: nil),
                                using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.update(results)
        }
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                // Typically the network changed; start over shortly.
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    self?.stop()
                    self?.start()
                }
            }
        }
        browser.start(queue: .main)
        self.browser = browser
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.expireBeacons()
        }
    }

    public func stop() {
        browser?.cancel()
        browser = nil
        expiryTimer?.invalidate()
        expiryTimer = nil
        bonjour = [:]
        beacons = [:]
        publish()
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var found: [String: DiscoveredReceiver] = [:]
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            var txt: [String: String] = [:]
            if case let .bonjour(record) = result.metadata { txt = record.dictionary }
            let id = txt["id"] ?? name
            var receiver = found[id] ?? DiscoveredReceiver(id: id, name: name)
            receiver.name = name
            receiver.model = txt["model"]
            receiver.width = txt["w"].flatMap(Int.init)
            receiver.height = txt["h"].flatMap(Int.init)
            // Several interfaces can report the same TV; prefer the one without
            // an interface binding (the system picks the best route).
            if receiver.service == nil || result.interfaces.isEmpty { receiver.service = result.endpoint }
            found[id] = receiver
        }
        bonjour = found
        publish()
    }

    /// Adds a TV seen through a UDP beacon (macOS).
    public func ingest(_ beacon: Beacon, from host: String) {
        guard beacon.role == "receiver", beacon.id != DeviceIdentity.id else { return }
        var receiver = beacons[beacon.id] ?? DiscoveredReceiver(id: beacon.id, name: beacon.name)
        receiver.name = beacon.name
        receiver.model = beacon.model
        receiver.width = beacon.w
        receiver.height = beacon.h
        receiver.host = host
        receiver.port = UInt16(clamping: beacon.port)
        receiver.lastBeacon = Date()
        let changed = beacons[beacon.id] == nil
        beacons[beacon.id] = receiver
        if changed { publish() }
    }

    private func expireBeacons() {
        let cutoff = Date().addingTimeInterval(-7)
        let before = beacons.count
        beacons = beacons.filter { ($0.value.lastBeacon ?? .distantPast) > cutoff }
        if beacons.count != before { publish() }
    }

    private func publish() {
        var merged = bonjour
        for (id, beacon) in beacons {
            if var existing = merged[id] {
                existing.host = beacon.host
                existing.port = beacon.port
                existing.width = existing.width ?? beacon.width
                existing.height = existing.height ?? beacon.height
                merged[id] = existing
            } else {
                merged[id] = beacon
            }
        }
        let list = merged.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        guard list != receivers else { return }
        receivers = list
        onChange?(list)
    }
}

/// Makes this Mac discoverable: listens for TV-initiated control connections
/// and advertises `_mangocast._tcp`.
public final class SourceAdvertiser {
    public var onConnection: ((NWConnection) -> Void)?
    public var onPortChange: ((UInt16) -> Void)?
    public private(set) var port: UInt16 = Mango.sourceControlPort

    private var listener: NWListener?
    private let queue: DispatchQueue

    public init(queue: DispatchQueue) {
        self.queue = queue
    }

    public func start() throws {
        guard listener == nil else { return }
        let parameters = ControlConnection.parameters()
        parameters.allowLocalEndpointReuse = true
        let listener: NWListener
        if let fixed = NWEndpoint.Port(rawValue: Mango.sourceControlPort),
           let preferred = try? NWListener(using: parameters, on: fixed) {
            listener = preferred
        } else {
            listener = try NWListener(using: parameters)
        }
        let txt = NWTXTRecord([
            "v": "\(Mango.protocolVersion)", "id": DeviceIdentity.id, "role": "source",
            "os": DeviceIdentity.os, "model": DeviceIdentity.model,
        ])
        listener.service = NWListener.Service(name: DeviceIdentity.name, type: Mango.sourceServiceType,
                                              domain: nil, txtRecord: txt)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return connection.cancel() }
            if case let .hostPort(host, _) = connection.endpoint, !LocalNetwork.isLocal(host.addressString) {
                return connection.cancel()
            }
            self.onConnection?(connection)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                if let port = listener?.port?.rawValue {
                    self.port = port
                    self.onPortChange?(port)
                }
            case .failed:
                self.stop()
                self.queue.asyncAfter(deadline: .now() + 2) { try? self.start() }
            default:
                break
            }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }
}

/// UDP broadcast discovery (docs/PROTOCOL.md §2.2), for TVs whose firmware
/// breaks multicast DNS. macOS only: iOS needs a special entitlement for
/// broadcast sockets.
public final class BeaconService {
    public var onBeacon: ((Beacon, String) -> Void)?
    /// What to announce. Read each time a beacon goes out.
    public var beacon: () -> Beacon

    private var fd: Int32 = -1
    private var readSource: DispatchSourceRead?
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "mango.beacon")

    public init(beacon: @escaping () -> Beacon) {
        self.beacon = beacon
    }

    public func start() {
        queue.async { self.open() }
    }

    public func stop() {
        queue.async { self.shutdown() }
    }

    private func open() {
        guard fd < 0 else { return }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return }
        var one: Int32 = 1
        let size = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, size)
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &one, size)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = Mango.beaconPort.bigEndian
        address.sin_addr = in_addr(s_addr: 0)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            Darwin.close(fd)
            return
        }
        self.fd = fd

        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.read() }
        source.resume()
        readSource = source

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 2)
        var first = true
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            var message = self.beacon()
            // The first beacon doubles as a probe so TVs answer right away.
            if first { message.probe = true; first = false }
            self.broadcast(message)
        }
        timer.resume()
        self.timer = timer
    }

    private func shutdown() {
        timer?.cancel()
        timer = nil
        readSource?.cancel()
        readSource = nil
        if fd >= 0 { Darwin.close(fd) }
        fd = -1
    }

    private func read() {
        var buffer = [UInt8](repeating: 0, count: 2048)
        var from = sockaddr_in()
        var fromLength = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = withUnsafeMutablePointer(to: &from) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                recvfrom(fd, &buffer, buffer.count, 0, $0, &fromLength)
            }
        }
        guard n > 0, let message = try? JSONDecoder().decode(Beacon.self, from: Data(buffer[0..<n])),
              message.mango == Mango.protocolVersion, message.id != DeviceIdentity.id else { return }
        var addressBytes = from.sin_addr
        var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &addressBytes, &text, socklen_t(INET_ADDRSTRLEN))
        let host = String(cString: text)
        guard LocalNetwork.isLocal(host) else { return }
        if message.probe == true { send(beacon(), to: from.sin_addr.s_addr) }
        let handler = onBeacon
        DispatchQueue.main.async { handler?(message, host) }
    }

    private func broadcast(_ message: Beacon) {
        var targets: Set<in_addr_t> = [0xFFFF_FFFF] // 255.255.255.255
        // Directed broadcasts reach interfaces other than the primary one.
        var list: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&list) == 0, let first = list {
            var cursor: UnsafeMutablePointer<ifaddrs>? = first
            while let entry = cursor?.pointee {
                let flags = Int32(entry.ifa_flags)
                if flags & IFF_UP != 0, flags & IFF_BROADCAST != 0, flags & IFF_LOOPBACK == 0,
                   let address = entry.ifa_dstaddr, address.pointee.sa_family == sa_family_t(AF_INET) {
                    _ = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                        targets.insert($0.pointee.sin_addr.s_addr)
                    }
                }
                cursor = entry.ifa_next
            }
            freeifaddrs(list)
        }
        for target in targets { send(message, to: target) }
    }

    private func send(_ message: Beacon, to address: in_addr_t) {
        guard fd >= 0, let data = try? JSONEncoder().encode(message) else { return }
        var target = sockaddr_in()
        target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = Mango.beaconPort.bigEndian
        target.sin_addr = in_addr(s_addr: address)
        _ = data.withUnsafeBytes { bytes in
            withUnsafePointer(to: &target) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }
}
