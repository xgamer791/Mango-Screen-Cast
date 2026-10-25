import Combine
import UIKit
import VideoToolbox

/// Runs the AirPlay server while the app is on screen, and routes what it
/// receives to the video and audio players.
///
/// Next to it, Windows computers (Mango Cast, protocol/mango-cast.md) can
/// show their screen through the same players. One device has the screen at
/// a time: a Windows computer is refused while an Apple device mirrors, and
/// gives way when one starts. While no Windows computer is connected,
/// everything here works exactly as it did without them.
///
/// The server calls back on its own threads; `status` is only changed on
/// the main thread, where SwiftUI reads it.
final class Receiver: ObservableObject {
    enum State: Equatable {
        case stopped, starting, ready, connected, mirroring, failed
    }

    struct Status: Equatable {
        var state = State.stopped
        var receiverName = ""
        var profile: MirrorProfile?
        var clientName: String?
        var paused = false
        var message: String?
    }

    @Published private(set) var status = Status()

    let settings: Settings
    let video = VideoRenderer()
    let audio = AudioPlayer()

    private let advertiser = ServiceAdvertiser()

    /// Windows computers: their own listener and announcement, so neither can upset AirPlay's.
    private let windows = MangoCastServer()
    private let windowsLock = NSLock()
    /// A Windows computer has the screen (and which). Under `windowsLock`.
    private var windowsActive = false
    private weak var windowsSession: MangoCastServer.Session?
    /// The Apple device connected over AirPlay, for when a Windows computer is done. Under `stateLock`.
    private var appleClient: String?
    private let control = DispatchQueue(label: "com.mangoscreencast.control")
    /// The server's threads read the state through this copy.
    private let stateLock = NSLock()
    private var current = Status()
    private var running = false
    private var shapeChange: DispatchWorkItem?
    private let logLock = NSLock()
    private var recentLog: [String] = []
    private var statsTimer: DispatchSourceTimer?
    private let timeFormat: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    init(settings: Settings) {
        self.settings = settings
        advertiser.onFailure = { [weak self] reason in
            self?.log("advertising failed: \(reason)")
            self?.update {
                $0.state = .failed
                $0.message = "Couldn’t announce this device on the network: \(reason)."
            }
        }
        advertiser.onPublished = { [weak self] type, name in self?.log("advertising \(type) as “\(name)”") }
        video.log = { [weak self] in self?.log($0) }
        audio.log = { [weak self] in self?.log($0) }
        windows.sink = self
        windows.log = { [weak self] in self?.log($0) }
    }

    /// Whether a Windows computer has the screen. Any thread.
    private var windowsHasScreen: Bool { windowsLock.withLock { windowsActive } }

    // MARK: - Control (main thread)

    /// Starts the server unless it's running. Called when the app comes on screen.
    func start() {
        guard !running else { return }
        running = true
        let name = settings.receiverName
        let profile = currentProfile()
        let deviceId = settings.deviceId
        control.async { self.startServer(name: name, profile: profile, deviceId: deviceId) }
    }

    /// The most this device can show, in the shape of its screen as it's
    /// held right now.
    func currentProfile() -> MirrorProfile {
        MirrorProfile.make(screen: Self.screenPixels(), landscape: Self.isLandscape(), hevc: Self.hevcSupported,
                           setting: settings.resolution)
    }

    /// Called when the window changes size. After the device is turned
    /// (and settles), the server is restarted with the new shape so the next
    /// Mac to connect fills the screen, but only while nobody is connected.
    func screenShapeChanged() {
        shapeChange?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.running, self.status.state == .ready else { return }
            let profile = self.currentProfile()
            guard profile != self.status.profile else { return }
            self.log("screen turned; offering \(profile.width)x\(profile.height)")
            self.restart()
        }
        shapeChange = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    /// iOS suspends apps in the background, which would leave Macs listing a
    /// receiver that can't answer, so the server stops with the app.
    func stop() {
        guard running else { return }
        running = false
        control.async { self.stopServer() }
    }

    /// Applies a changed name or resolution.
    func restart() {
        guard running else { return }
        stop()
        start()
    }

    /// Applies the Windows computers setting, leaving AirPlay as it is.
    func applyWindowsSetting() {
        guard running else { return }
        control.async {
            self.windows.stop(reason: "Windows computers were turned off on \(self.settings.receiverName).")
            if let profile = self.snapshot().profile {
                self.startWindows(name: self.settings.receiverName, profile: profile, deviceId: self.settings.deviceId)
            }
        }
    }

    /// Drops the connected device and returns to the home screen.
    func disconnect() {
        control.async {
            self.windows.disconnect("Disconnected on \(self.settings.receiverName).")
            mango_bridge_disconnect_clients()
            self.endMirroring()
            self.update {
                $0.state = .ready
                $0.clientName = nil
                $0.paused = false
            }
        }
    }

    func setSoundEnabled(_ enabled: Bool) {
        audio.enabled = enabled
    }

    // MARK: - Server lifecycle (control queue)

    private func startServer(name: String, profile: MirrorProfile, deviceId: [UInt8]) {
        update { $0 = Status(state: .starting, receiverName: name, profile: profile) }
        audio.enabled = settings.soundEnabled
        let keyfile = Self.supportDirectory().appendingPathComponent("airplay.key").path

        var callbacks = mango_bridge_callbacks_t()
        callbacks.context = Unmanaged.passUnretained(self).toOpaque()
        callbacks.register_service = { context, type, name, port, txt, length in
            let txtData = txt.map { Data(bytes: $0, count: Int(length)) } ?? Data()
            return Receiver.from(context).registerService(type: String(cString: type!), name: String(cString: name!),
                                                         port: Int(port), txt: txtData)
        }
        callbacks.unregister_service = { context, type in
            Receiver.from(context).unregisterService(type: String(cString: type!))
        }
        callbacks.client_request = { context, _, model, name in
            Receiver.from(context).clientRequest(model: String(cString: model!), name: String(cString: name!))
        }
        callbacks.connections_changed = { context, open in
            Receiver.from(context).connectionsChanged(Int(open))
        }
        callbacks.connection_reset = { context, reason in
            Receiver.from(context).connectionReset(Int(reason))
        }
        callbacks.video_start = { context, h265, sourceWidth, sourceHeight, _, _ in
            Receiver.from(context).videoStart(h265: h265, width: Int(sourceWidth), height: Int(sourceHeight))
        }
        callbacks.video_frame = { context, data, length, h265 in
            guard let data else { return }
            Receiver.from(context).video.submit(UnsafeRawBufferPointer(start: data, count: Int(length)), h265: h265)
        }
        callbacks.video_paused = { context, paused in
            Receiver.from(context).update { $0.paused = paused }
        }
        callbacks.video_stop = { context, reason in
            Receiver.from(context).videoStop(reason: Int(reason))
        }
        // While a Windows computer has the screen, its sound plays, not an Apple device's.
        callbacks.audio_format = { context, type, samplesPerFrame in
            let receiver = Receiver.from(context)
            guard !receiver.windowsHasScreen else { return }
            receiver.audio.configure(compressionType: Int(type), samplesPerFrame: Int(samplesPerFrame))
        }
        callbacks.audio_frame = { context, data, length, type in
            let receiver = Receiver.from(context)
            guard let data, !receiver.windowsHasScreen else { return }
            receiver.audio.submit(UnsafeRawBufferPointer(start: data, count: Int(length)), compressionType: Int(type))
        }
        callbacks.audio_flush = { context in
            let receiver = Receiver.from(context)
            guard !receiver.windowsHasScreen else { return }
            receiver.audio.flush()
        }
        callbacks.volume = { context, db in
            let receiver = Receiver.from(context)
            guard !receiver.windowsHasScreen else { return }
            receiver.audio.setVolume(decibels: db)
        }
        callbacks.log = { context, level, message in
            guard level <= Int32(MANGO_LOG_INFO), let message else { return }
            // Skip the server's multi-line dumps; their first line says enough.
            let line = String(cString: message).split(separator: "\n").first.map(String.init) ?? ""
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty { Receiver.from(context).log(trimmed) }
        }

        var config = mango_bridge_config_t()
        config.device_id = (deviceId[0], deviceId[1], deviceId[2], deviceId[3], deviceId[4], deviceId[5])
        config.width = Int32(profile.width)
        config.height = Int32(profile.height)
        config.refresh_rate = Int32(profile.refreshRate)
        config.max_fps = Int32(profile.maxFps)
        config.h265 = profile.h265
        config.log_level = Int32(MANGO_LOG_INFO)
        let result = name.withCString { namePointer in
            keyfile.withCString { keyfilePointer in
                config.name = namePointer
                config.keyfile = keyfilePointer
                return mango_bridge_start(&config, &callbacks)
            }
        }

        if result > 0 {
            log("server ready on port \(result): \(profile.width)x\(profile.height) @ \(profile.maxFps) fps, \(profile.codecs)")
            update {
                $0.state = .ready
                $0.message = nil
            }
        } else {
            log("server failed to start: \(result)")
            update {
                $0.state = .failed
                $0.message = Self.describeError(Int(result))
            }
        }
        startWindows(name: name, profile: profile, deviceId: deviceId)
    }

    /// Listens for Windows computers and announces this device to them. Never affects AirPlay.
    private func startWindows(name: String, profile: MirrorProfile, deviceId: [UInt8]) {
        guard settings.windowsEnabled else { return }
        let id = deviceId.map { String(format: "%02x", $0) }.joined()
        windows.start(name: name, txt: offer(profile).txtRecord(id: id, model: Self.modelIdentifier())) { [weak self] port in
            self?.log(port.map { "Windows: listening on port \($0)" } ?? "Windows: couldn't listen for Windows computers")
        }
    }

    /// What this device takes from a Windows computer: what it offers Apple
    /// devices, always landscape, as a PC's screen is, so turning the iPad to
    /// portrait doesn't squeeze it.
    private func offer(_ profile: MirrorProfile) -> Offer {
        Offer(name: settings.receiverName, maxWidth: max(profile.width, profile.height),
              maxHeight: min(profile.width, profile.height), fps: profile.maxFps, h265: profile.h265,
              audio: settings.soundEnabled)
    }

    private func stopServer() {
        windows.stop(reason: "Mango stopped receiving.")
        mango_bridge_stop()
        endMirroring()
        DispatchQueue.main.async { self.advertiser.unregisterAll() }
        update {
            $0.state = .stopped
            $0.clientName = nil
            $0.paused = false
        }
    }

    private func endMirroring() {
        stopStatsTimer()
        video.stop()
        audio.stop()
    }

    private static func describeError(_ code: Int) -> String {
        switch code {
        case -5: return "Couldn’t open a network port for AirPlay. Close Mango and open it again."
        case -6: return "Couldn’t announce this device on the network. Check that Wi-Fi is on."
        default: return "AirPlay couldn’t start (error \(code))."
        }
    }

    // MARK: - Server callbacks (server threads)

    private static func from(_ context: UnsafeMutableRawPointer?) -> Receiver {
        Unmanaged<Receiver>.fromOpaque(context!).takeUnretainedValue()
    }

    private func registerService(type: String, name: String, port: Int, txt: Data) -> Int32 {
        DispatchQueue.main.async { self.advertiser.register(type: type, name: name, port: port, txt: txt) }
        return 0
    }

    private func unregisterService(type: String) {
        DispatchQueue.main.async { self.advertiser.unregister(type: type) }
    }

    private func clientRequest(model: String, name: String) -> Bool {
        log("connection from \(name.isEmpty ? model : name) (\(model))")
        stateLock.withLock { appleClient = name.isEmpty ? model : name }
        // A Windows computer has the screen until this device starts mirroring.
        if windowsHasScreen { return true }
        update {
            if $0.state != .mirroring { $0.state = .connected }
            $0.clientName = name.isEmpty ? model : name
            $0.message = nil
        }
        return true
    }

    private func connectionsChanged(_ open: Int) {
        guard open == 0 else { return }
        stateLock.withLock { appleClient = nil }
        if windowsHasScreen { return }
        endMirroring()
        update {
            if $0.state != .stopped { $0.state = .ready }
            $0.clientName = nil
            $0.paused = false
        }
    }

    private func connectionReset(_ reason: Int) {
        let who = snapshot().clientName
        log("connection reset (reason \(reason))")
        stateLock.withLock { appleClient = nil }
        if windowsHasScreen { return }
        endMirroring()
        update {
            if $0.state != .stopped { $0.state = .ready }
            $0.clientName = nil
            $0.paused = false
            $0.message = reason == 1 && who != nil ? "Lost the connection to \(who!)." : nil
        }
    }

    private func videoStart(h265: Bool, width: Int, height: Int) -> Int32 {
        log("mirroring starts: \(h265 ? "HEVC" : "H.264") \(width)x\(height)")
        if h265 && snapshot().profile?.h265 == false { return -1 }
        // The Apple device takes the screen from a Windows computer.
        if windowsHasScreen { windows.disconnect("An Apple device started mirroring to \(settings.receiverName).") }
        video.start(h265: h265)
        startStatsTimer()
        update {
            $0.state = .mirroring
            $0.paused = false
        }
        return 0
    }

    private func videoStop(reason: Int) {
        // The sender stopped mirroring, or another device took over.
        log("mirroring stopped (reset type \(reason))")
        if windowsHasScreen { return }
        stopStatsTimer()
        video.stop()
        update {
            if $0.state == .mirroring { $0.state = .connected }
            $0.paused = false
        }
    }

    // MARK: - Status and log

    private func snapshot() -> Status {
        stateLock.withLock { current }
    }

    private func update(_ change: (inout Status) -> Void) {
        let next: Status = stateLock.withLock {
            change(&current)
            return current
        }
        DispatchQueue.main.async { self.status = next }
    }

    /// Adds a line to the log shown under Settings › Diagnostics.
    func log(_ line: String) {
        let stamped = "\(timeFormat.string(from: Date())) \(line)"
        logLock.withLock {
            recentLog.append(stamped)
            if recentLog.count > 150 { recentLog.removeFirst(recentLog.count - 150) }
        }
        NSLog("MangoAirPlay: %@", line)
    }

    /// While mirroring, logs what the decoder is doing every 10 seconds.
    private func startStatsTimer() {
        stopStatsTimer()
        let timer = DispatchSource.makeTimerSource(queue: control)
        timer.schedule(deadline: .now() + 10, repeating: 10)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let v = self.video.stats()
            self.log(String(format: "stats: %ldx%ld %@, %.1f fps, %.1f Mbit/s, %ld dropped, %ld recoveries",
                            v.width, v.height, v.codec, v.fps, v.megabitsPerSecond, v.dropped, v.recoveries))
        }
        timer.resume()
        stateLock.withLock { statsTimer = timer }
    }

    private func stopStatsTimer() {
        let timer: DispatchSourceTimer? = stateLock.withLock {
            defer { statsTimer = nil }
            return statsTimer
        }
        timer?.cancel()
    }

    /// Plain-text report for troubleshooting.
    func diagnostics() -> String {
        let s = snapshot()
        var lines: [String] = []
        lines.append("State: \(s.state)\(s.message.map { " (\($0))" } ?? "")")
        lines.append("Name: \(s.receiverName)")
        if let p = s.profile {
            lines.append("Offering: \(p.width)x\(p.height) @ \(p.maxFps) fps, \(p.codecs)")
            lines.append("Screen: \(p.screenWidth)x\(p.screenHeight)")
        }
        let device = UIDevice.current
        lines.append("Device: \(Self.modelIdentifier()), \(device.systemName) \(device.systemVersion)")
        let info = Bundle.main.infoDictionary
        lines.append("App: \(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))")
        lines.append("Hardware HEVC decoding: \(Self.hevcSupported ? "yes" : "no")")
        let windowsSession = windows.session
        lines.append("Windows computers: " + (!settings.windowsEnabled ? "off"
            : windows.port.map { "listening on port \($0)" + (windowsSession.map { ", \($0.hello.name) (\($0.address)) connected" } ?? "") }
            ?? "not listening"))
        if s.state == .mirroring {
            let v = video.stats()
            lines.append(String(format: "Video: %ldx%ld %@, %.1f fps, %.1f Mbit/s, %ld frames, %ld dropped, %ld recoveries",
                                v.width, v.height, v.codec, v.fps, v.megabitsPerSecond, v.frames, v.dropped,
                                v.recoveries))
        }
        lines.append("")
        lines.append("Recent log:")
        lines.append(contentsOf: logLock.withLock { recentLog })
        return lines.joined(separator: "\n")
    }

    // MARK: - Device

    static let hevcSupported = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)

    private static var windowScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    /// The screen in pixels (portrait). Main thread.
    static func screenPixels() -> CGSize {
        windowScene?.screen.nativeBounds.size ?? CGSize(width: 1920, height: 1080)
    }

    /// Whether the device is held in landscape. Main thread.
    static func isLandscape() -> Bool {
        windowScene?.effectiveGeometry.interfaceOrientation.isLandscape ?? true
    }

    private static func supportDirectory() -> URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private static func modelIdentifier() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

// MARK: - Windows computers (Mango Cast queue)

extension Receiver: MangoCastSink {
    func mangoCastHello(_ hello: Hello) -> Decision {
        let s = snapshot()
        guard settings.windowsEnabled else {
            return .refuse(reason: "off", message: "Windows computers are turned off in Mango’s settings on \(s.receiverName).")
        }
        guard let profile = s.profile, s.state != .stopped, s.state != .starting else {
            return .refuse(reason: "off", message: "\(s.receiverName) is starting. Try again in a moment.")
        }
        if s.state == .mirroring {
            return .refuse(reason: "busy",
                           message: "\(s.clientName ?? "An Apple device") is mirroring to \(s.receiverName) right now.")
        }
        return .accept(offer(profile))
    }

    func mangoCastStarted(_ session: MangoCastServer.Session) {
        windowsLock.withLock {
            windowsActive = true
            windowsSession = session
        }
        log("Windows: \(session.hello.name) is showing its screen: \(session.h265 ? "HEVC" : "H.264") "
            + "\(session.width)x\(session.height), sound \(session.audio ? "on" : "off")")
        video.onKeyframeNeeded = { [weak session] in session?.requestKeyframe() }
        video.start(h265: session.h265)
        if session.audio {
            audio.enabled = settings.soundEnabled
            audio.configure(compressionType: AudioPlayer.Codec.pcm.rawValue, samplesPerFrame: 0,
                            sampleRate: Double(MangoCast.audioRate))
            // An Apple device's volume doesn't carry over.
            audio.setVolume(decibels: 0)
        } else {
            audio.stop()
        }
        startStatsTimer()
        update {
            $0.state = .mirroring
            $0.clientName = session.hello.name
            $0.paused = false
            $0.message = nil
        }
    }

    func mangoCastVideo(_ session: MangoCastServer.Session, _ frame: UnsafeRawBufferPointer) {
        // Under the lock, so no Windows picture lands in an Apple device's stream as it takes over.
        windowsLock.withLock {
            if windowsActive && windowsSession === session { video.submit(frame, h265: session.h265) }
        }
    }

    func mangoCastAudio(_ session: MangoCastServer.Session, _ pcm: UnsafeRawBufferPointer) {
        windowsLock.withLock {
            if windowsActive && windowsSession === session {
                audio.submit(pcm, compressionType: AudioPlayer.Codec.pcm.rawValue)
            }
        }
    }

    func mangoCastEnded(_ session: MangoCastServer.Session, reason: String, lost: Bool) {
        let was: Bool = windowsLock.withLock {
            defer {
                windowsActive = false
                windowsSession = nil
            }
            return windowsActive
        }
        guard was else { return }
        video.onKeyframeNeeded = nil
        endMirroring()
        let apple = stateLock.withLock { appleClient }
        update {
            if let apple {
                $0.state = .connected
                $0.clientName = apple
                $0.message = nil
            } else {
                if $0.state != .stopped { $0.state = .ready }
                $0.clientName = nil
                $0.message = lost ? reason : nil
            }
            $0.paused = false
        }
    }
}
