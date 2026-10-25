import Combine
import UIKit
import VideoToolbox

/// Runs the AirPlay server while the app is open, on screen or not, and
/// routes what it receives to the video and audio players.
///
/// iOS suspends apps in the background unless they play sound, so with
/// Settings › Keep running in the background on (the default), the audio
/// output runs (silently when there's nothing to play) for as long as the
/// server does. A device mirroring stays connected while Mango Cast is in
/// the background or the screen is locked; closing the app ends it.
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

    /// A picture size being tried. While mirroring with it, the person is
    /// asked to keep it before `deadline`; otherwise it goes back.
    struct ResolutionCheck: Equatable {
        let trying: MirrorProfile
        let previous: MirrorProfile
        /// Set once the picture is up; nil while waiting for it.
        var deadline: Date?
    }

    @Published private(set) var resolutionCheck: ResolutionCheck?
    /// Something to tell the person on the home screen (e.g. a size went back).
    @Published var notice: String?

    let settings: Settings
    let video = VideoRenderer()
    let audio = AudioPlayer()
    private(set) lazy var pictureInPicture = PictureInPicture(layer: video.layer)

    private let advertiser = ServiceAdvertiser()
    private let control = DispatchQueue(label: "com.mangoscreencast.control")
    /// The server's threads read the state through this copy.
    private let stateLock = NSLock()
    private var current = Status()
    private var running = false
    private var shapeChange: DispatchWorkItem?
    private var checkTimer: Timer?
    private var checkStarted = Date()
    /// How long the picture may take to appear at a new size, and how long
    /// the person has to keep it once it's up.
    private static let pictureTimeout: TimeInterval = 15
    private static let keepTimeout: TimeInterval = 20
    private let logLock = NSLock()
    private var recentLog: [String] = []
    private var statsTimer: DispatchSourceTimer?
    /// Ticks while the server runs; a long gap means iOS suspended the app.
    private var heartbeat: Timer?
    private var lastBeat = Date()
    private var inBackground = false
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
        pictureInPicture.log = { [weak self] in self?.log($0) }
        pictureInPicture.startsWhenLeaving = settings.runInBackground && settings.pictureInPicture
        if settings.resolutionOnTrial && settings.resolutionTrialInUse {
            // The app stopped while mirroring at a size on trial: don't
            // start with it again. (No window yet, so no sizes in the text.)
            for landscape in [false, true] {
                settings.setResolutionScale(settings.confirmedResolutionScale(landscape: landscape), landscape: landscape)
            }
            settings.resolutionTrialInUse = false
            notice = "Mango Cast closed while trying a new picture size, so it went back to the last one that worked."
            log(notice!)
        }
    }

    // MARK: - Control (main thread)

    /// Starts the server unless it's running. Called when the app comes on screen.
    func start() {
        guard !running else { return }
        running = true
        startHeartbeat()
        let name = settings.receiverName
        let profile = currentProfile()
        let deviceId = settings.deviceId
        let keepAlive = settings.runInBackground
        control.async { self.startServer(name: name, profile: profile, deviceId: deviceId, keepAlive: keepAlive) }
    }

    // MARK: - In the background (main thread)

    /// The app left the screen (true) or came back (false). The server keeps
    /// running in the background unless Settings says otherwise.
    func setInBackground(_ background: Bool) {
        guard background != inBackground else { return }
        inBackground = background
        if background {
            guard running else { return }
            if settings.runInBackground {
                log(audio.outputRunning ? "in the background; still receiving"
                                        : "in the background, but the audio output isn't running: iOS may pause Mango Cast")
                video.setInBackground(true)
            } else {
                stop()
            }
        } else {
            pictureInPicture.stop()
            video.setInBackground(false)
            if running, Date().timeIntervalSince(lastBeat) > 15 {
                // Suspended anyway (a call, or the audio output wouldn't
                // start): whatever was connected is gone, so start afresh.
                log("iOS paused Mango Cast in the background; restarting AirPlay")
                restart()
            } else {
                start()
            }
        }
    }

    /// Settings › Keep running in the background (and Picture in Picture) changed.
    func backgroundSettingsChanged() {
        let keepAlive = settings.runInBackground
        pictureInPicture.startsWhenLeaving = keepAlive && settings.pictureInPicture
        guard running else { return }
        control.async { self.audio.keepAlive = keepAlive }
    }

    private func startHeartbeat() {
        heartbeat?.invalidate()
        lastBeat = Date()
        let timer = Timer(timeInterval: 5, repeats: true) { [weak self] _ in self?.lastBeat = Date() }
        RunLoop.main.add(timer, forMode: .common)
        heartbeat = timer
    }

    /// What's offered with the device held upright or sideways (by default,
    /// the way it's held now), at the size picked for that way round.
    func currentProfile(scale: Int? = nil, landscape: Bool? = nil) -> MirrorProfile {
        let sideways = landscape ?? Self.isLandscape()
        return MirrorProfile.make(screen: Self.screenPixels(), landscape: sideways, hevc: Self.hevcSupported,
                                  scale: scale ?? settings.resolutionScale(landscape: sideways))
    }

    /// Every size on offer held upright or sideways, largest first.
    func resolutionChoices(landscape: Bool) -> [MirrorProfile] {
        MirrorProfile.choices(screen: Self.screenPixels(), landscape: landscape, hevc: Self.hevcSupported)
    }

    /// Which way round the size being offered is (and so the shape of a Mac's
    /// display connected now).
    var offeredLandscape: Bool {
        guard let profile = status.profile else { return Self.isLandscape() }
        return profile.screenWidth > profile.screenHeight
    }

    // MARK: - Trying a picture size (main thread)

    /// Switches to another picture size. Anything but full size is a trial:
    /// the next time a device mirrors, the person is asked to keep it, and
    /// if they don't, or the picture doesn't work, it goes back to the last
    /// size that did. Full size is always safe to go back to.
    func setResolution(scale: Int, landscape: Bool) {
        let current = settings.resolutionScale(landscape: landscape)
        guard scale != current else { return }
        if !settings.resolutionOnTrial(landscape: landscape) {
            settings.setConfirmedResolutionScale(current, landscape: landscape)
        }
        settings.setResolutionScale(scale, landscape: landscape)
        // Full size and this device's default are always safe to go back to.
        if scale == 100 || scale == Settings.defaultResolutionScale {
            settings.setConfirmedResolutionScale(scale, landscape: landscape)
        }
        settings.resolutionTrialInUse = false
        endCheck()
        notice = nil
        let way = landscape ? "sideways" : "upright"
        if settings.resolutionOnTrial(landscape: landscape) {
            let kept = settings.confirmedResolutionScale(landscape: landscape)
            log("trying \(currentProfile(landscape: landscape).sizeText) \(way); last kept size \(currentProfile(scale: kept, landscape: landscape).sizeText)")
        }
        // The other way round is offered the next time the device is held so.
        if landscape == offeredLandscape { restart() }
    }

    func keepResolution() {
        let landscape = offeredLandscape
        guard settings.resolutionOnTrial(landscape: landscape) else { return }
        settings.setConfirmedResolutionScale(settings.resolutionScale(landscape: landscape), landscape: landscape)
        settings.resolutionTrialInUse = false
        log("kept \(currentProfile(landscape: landscape).sizeText)")
        endCheck()
    }

    /// Goes back to the last size that worked held that way round (by
    /// default, the way of the size being offered). `reason` starts the
    /// notice shown on the home screen.
    func revertResolution(because reason: String? = nil, landscape: Bool? = nil) {
        let sideways = landscape ?? offeredLandscape
        guard settings.resolutionOnTrial(landscape: sideways) else { return }
        let tried = currentProfile(landscape: sideways)
        settings.setResolutionScale(settings.confirmedResolutionScale(landscape: sideways), landscape: sideways)
        settings.resolutionTrialInUse = false
        endCheck()
        let back = currentProfile(landscape: sideways)
        let text = "\(reason ?? "Didn’t keep \(tried.sizeText)"), so Mango Cast went back to \(back.sizeText)."
        notice = text
        log(text)
        if sideways == offeredLandscape { restart() }
    }

    /// Mirroring started: if the size is on trial, watch it.
    private func beginCheck() {
        let landscape = offeredLandscape
        guard settings.resolutionOnTrial(landscape: landscape), resolutionCheck == nil else { return }
        settings.resolutionTrialInUse = true
        resolutionCheck = ResolutionCheck(
            trying: status.profile ?? currentProfile(landscape: landscape),
            previous: currentProfile(scale: settings.confirmedResolutionScale(landscape: landscape), landscape: landscape))
        checkStarted = Date()
        checkTimer?.invalidate()
        checkTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tickCheck() }
    }

    private func tickCheck() {
        guard var check = resolutionCheck else { return endCheck() }
        let stats = video.stats()
        let size = check.trying.sizeText
        if stats.recoveries >= 3 {
            revertResolution(because: "The picture kept failing at \(size)")
        } else if let deadline = check.deadline {
            if Date() >= deadline { revertResolution(because: "\(size) wasn’t kept in time") }
        } else if stats.frames > 0 {
            check.deadline = Date().addingTimeInterval(Self.keepTimeout)
            resolutionCheck = check
        } else if Date().timeIntervalSince(checkStarted) > Self.pictureTimeout {
            revertResolution(because: "No picture arrived at \(size)")
        }
    }

    /// Mirroring ended. A size on trial that wasn't kept goes back, and if
    /// the device was turned meanwhile, the next Mac gets the new shape.
    private func sessionEnded() {
        screenShapeChanged()
        guard let check = resolutionCheck else { return }
        revertResolution(because: "Mirroring stopped before \(check.trying.sizeText) was kept")
    }

    private func endCheck() {
        checkTimer?.invalidate()
        checkTimer = nil
        resolutionCheck = nil
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

    /// Stops the server, and with it whatever is connected. Without running
    /// in the background, iOS would suspend the app there and leave Macs
    /// listing a receiver that can't answer, so it stops with the app then.
    func stop() {
        guard running else { return }
        running = false
        heartbeat?.invalidate()
        heartbeat = nil
        pictureInPicture.stop()
        control.async {
            self.stopServer()
            self.audio.keepAlive = false
        }
    }

    /// Applies a changed name or resolution. The audio output carries on
    /// meanwhile, so this works in the background too.
    func restart() {
        guard running else { return }
        startHeartbeat()
        let name = settings.receiverName
        let profile = currentProfile()
        let deviceId = settings.deviceId
        let keepAlive = settings.runInBackground
        control.async {
            self.stopServer()
            self.startServer(name: name, profile: profile, deviceId: deviceId, keepAlive: keepAlive)
        }
    }

    /// Drops the connected device and returns to the home screen.
    func disconnect() {
        sessionEnded()
        control.async {
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

    private func startServer(name: String, profile: MirrorProfile, deviceId: [UInt8], keepAlive: Bool) {
        update { $0 = Status(state: .starting, receiverName: name, profile: profile) }
        audio.enabled = settings.soundEnabled
        audio.keepAlive = keepAlive
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
        callbacks.audio_format = { context, type, samplesPerFrame in
            Receiver.from(context).audio.configure(compressionType: Int(type), samplesPerFrame: Int(samplesPerFrame))
        }
        callbacks.audio_frame = { context, data, length, type in
            guard let data else { return }
            Receiver.from(context).audio.submit(UnsafeRawBufferPointer(start: data, count: Int(length)),
                                                compressionType: Int(type))
        }
        callbacks.audio_flush = { context in
            Receiver.from(context).audio.flush()
        }
        callbacks.volume = { context, db in
            Receiver.from(context).audio.setVolume(decibels: db)
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
    }

    private func stopServer() {
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
        update {
            if $0.state != .mirroring { $0.state = .connected }
            $0.clientName = name.isEmpty ? model : name
            $0.message = nil
        }
        return true
    }

    private func connectionsChanged(_ open: Int) {
        guard open == 0 else { return }
        DispatchQueue.main.async {
            self.sessionEnded()
            self.pictureInPicture.stop()
        }
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
        DispatchQueue.main.async { self.sessionEnded() }
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
        video.start(h265: h265)
        startStatsTimer()
        DispatchQueue.main.async { self.beginCheck() }
        update {
            $0.state = .mirroring
            $0.paused = false
        }
        return 0
    }

    private func videoStop(reason: Int) {
        // The sender stopped mirroring, or another device took over.
        log("mirroring stopped (reset type \(reason))")
        DispatchQueue.main.async {
            self.sessionEnded()
            self.pictureInPicture.stop()
        }
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
        lines.append("Device: \(Device.model), \(device.systemName) \(device.systemVersion)")
        let info = Bundle.main.infoDictionary
        lines.append("App: \(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))")
        lines.append("Hardware HEVC decoding: \(Self.hevcSupported ? "yes" : "no")")
        lines.append("Keeps running in the background: \(settings.runInBackground ? "yes" : "no")"
                     + (settings.runInBackground ? " (audio output \(audio.outputRunning ? "running" : "not running"))" : ""))
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

    /// Whether the app is sideways, which it always is: it only runs in
    /// landscape (Info.plist). Main thread.
    static func isLandscape() -> Bool {
        windowScene?.effectiveGeometry.interfaceOrientation.isLandscape ?? true
    }

    private static func supportDirectory() -> URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
