import AVFoundation
import AudioToolbox
import QuartzCore

/// Plays AirPlay audio as it arrives, with only a little buffering.
///
/// Mirroring sends AAC-ELD; audio-only AirPlay may send AAC-LC or PCM.
/// ALAC (Apple Lossless, used for music-only AirPlay) isn't supported yet.
/// Frames are decoded on the AirPlay audio thread with Core Audio's AAC
/// decoder and scheduled on an AVAudioEngine player.
///
/// With `keepAlive` on, the output also runs with nothing to play (silence,
/// mixed with other apps' sound), which is what keeps iOS from suspending
/// the app in the background, and the AirPlay server with it.
final class AudioPlayer {
    enum Codec: Int {
        case pcm = 1, alac = 2, aacLc = 4, aacEld = 8
    }

    /// Receives one line per notable event (format, errors).
    var log: ((String) -> Void)?

    private let lock = NSLock()
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    /// Plays a second of silence over and over while `keepAlive` is on.
    private let keeper = AVAudioPlayerNode()
    private var keepAliveFlag = false
    private var observers: [NSObjectProtocol] = []
    private let output = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!
    private var converter: AudioConverterRef?
    private var codec: Codec?
    private var framesPerPacket: AVAudioFrameCount = 480
    private var enabledFlag = true
    private var gain: Float = 1
    private var lastStartAttempt: CFTimeInterval = 0
    private var warned = false
    private let packetDescription = UnsafeMutablePointer<AudioStreamPacketDescription>.allocate(capacity: 1)

    /// Frames scheduled but not yet played. Its own lock, because the player
    /// can report finished buffers from inside stop().
    private let queueLock = NSLock()
    private var queuedFrames = 0
    private var generation = 0
    /// About a quarter of a second: more than that means the sender is
    /// running ahead, and dropping a packet keeps the delay flat.
    private static let maxQueuedFrames = 11_025

    init() {
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: output)
        engine.attach(keeper)
        engine.connect(keeper, to: engine.mainMixerNode, format: output)
        // A call, Siri, an alarm or a change of speakers stops the output; it
        // comes back by itself, or the app would be suspended after them.
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                            queue: nil) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw.flatMap({ AVAudioSession.InterruptionType(rawValue: $0) }) == .ended { self?.resume(after: "an interruption") }
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine,
                                            queue: nil) { [weak self] _ in
            self?.resume(after: "a change of audio output")
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil,
                                            queue: nil) { [weak self] _ in
            self?.resume(after: "an audio system reset")
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        if let converter { AudioConverterDispose(converter) }
        packetDescription.deallocate()
    }

    /// Keeps the output running with nothing to play, so the app (and the
    /// AirPlay server) keeps running in the background. Off, the output
    /// stops when the sound does.
    var keepAlive: Bool {
        get { lock.withLock { keepAliveFlag } }
        set {
            lock.withLock {
                guard newValue != keepAliveFlag else { return }
                keepAliveFlag = newValue
                if newValue {
                    if ensureRunning(force: true) { log?("audio output on, to keep running in the background") }
                } else {
                    if keeper.engine != nil, engine.isRunning { keeper.stop() }
                    if codec == nil { stopOutput() }
                }
            }
        }
    }

    /// Whether the output is running (which keeps the app running in the background).
    var outputRunning: Bool { lock.withLock { engine.isRunning } }

    var enabled: Bool {
        get { lock.withLock { enabledFlag } }
        set {
            lock.withLock {
                enabledFlag = newValue
                if !newValue { resetPlayback() }
            }
        }
    }

    /// The sender announced its audio format.
    func configure(compressionType: Int, samplesPerFrame: Int) {
        lock.lock()
        defer { lock.unlock() }
        resetPlayback()
        disposeConverter()
        warned = false
        codec = Codec(rawValue: compressionType)
        switch codec {
        case .aacEld:
            makeConverter(kAudioFormatMPEG4AAC_ELD, framesPerPacket: samplesPerFrame > 0 ? samplesPerFrame : 480,
                          config: AudioCookie.aacEld, name: "AAC-ELD")
        case .aacLc:
            makeConverter(kAudioFormatMPEG4AAC, framesPerPacket: samplesPerFrame > 0 ? samplesPerFrame : 1024,
                          config: AudioCookie.aacLc, name: "AAC-LC")
        default:
            break
        }
    }

    /// One audio frame. Only valid during the call.
    func submit(_ data: UnsafeRawBufferPointer, compressionType: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard enabledFlag, let base = data.baseAddress, !data.isEmpty else { return }
        let buffer: AVAudioPCMBuffer?
        switch Codec(rawValue: compressionType) {
        case .pcm:
            buffer = convertPcm(data)
        case .aacEld, .aacLc:
            buffer = decode(base, length: data.count)
        default:
            if !warned {
                warned = true
                log?("audio codec \(compressionType) isn't supported; playing the picture without sound")
            }
            buffer = nil
        }
        if let buffer { play(buffer) }
    }

    func flush() {
        lock.lock()
        defer { lock.unlock() }
        resetPlayback()
        if let converter { AudioConverterReset(converter) }
    }

    func setVolume(decibels: Float) {
        lock.lock()
        defer { lock.unlock() }
        gain = AudioCookie.gain(decibels: decibels)
        player.volume = gain
    }

    /// Mirroring ended: stop the engine and hand the audio session back,
    /// unless it's keeping the app running.
    func stop() {
        lock.lock()
        defer { lock.unlock() }
        resetPlayback()
        disposeConverter()
        codec = nil
        if !keepAliveFlag { stopOutput() }
    }

    private func stopOutput() {
        if engine.isRunning { engine.stop() }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// The output stopped (see init): start it again if it's wanted.
    private func resume(after cause: String) {
        // Not from inside the notification: the engine may still be stopping.
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self else { return }
            self.lock.withLock {
                guard self.keepAliveFlag || self.codec != nil else { return }
                if self.engine.isRunning && (!self.keepAliveFlag || self.keeper.isPlaying) { return }
                if self.ensureRunning(force: true) { self.log?("audio output back after \(cause)") }
            }
        }
    }

    // MARK: - Decoding (lock held)

    private func makeConverter(_ formatID: AudioFormatID, framesPerPacket: Int, config: [UInt8], name: String) {
        var input = AudioStreamBasicDescription(
            mSampleRate: 44_100, mFormatID: formatID, mFormatFlags: 0, mBytesPerPacket: 0,
            mFramesPerPacket: UInt32(framesPerPacket), mBytesPerFrame: 0, mChannelsPerFrame: 2,
            mBitsPerChannel: 0, mReserved: 0)
        var pcm = output.streamDescription.pointee
        var created: AudioConverterRef?
        let status = AudioConverterNew(&input, &pcm, &created)
        guard status == noErr, let created else {
            log?("\(name) decoder unavailable (\(status)); playing the picture without sound")
            return
        }
        // The ES descriptor is Core Audio's own cookie format; a bare
        // AudioSpecificConfig is the fallback.
        var cookieStatus = setCookie(created, AudioCookie.esds(audioSpecificConfig: config))
        if cookieStatus != noErr { cookieStatus = setCookie(created, config) }
        if cookieStatus != noErr { log?("\(name) decoder didn't take its configuration (\(cookieStatus))") }
        converter = created
        self.framesPerPacket = AVAudioFrameCount(framesPerPacket)
        log?("audio \(name), \(framesPerPacket) samples per frame")
    }

    private func setCookie(_ converter: AudioConverterRef, _ cookie: [UInt8]) -> OSStatus {
        cookie.withUnsafeBytes {
            AudioConverterSetProperty(converter, kAudioConverterDecompressionMagicCookie, UInt32($0.count),
                                      $0.baseAddress!)
        }
    }

    private func disposeConverter() {
        if let converter { AudioConverterDispose(converter) }
        converter = nil
    }

    private struct PacketInput {
        var data: UnsafeRawPointer
        var length: UInt32
        var description: UnsafeMutablePointer<AudioStreamPacketDescription>
        var consumed: Bool
    }

    /// Returned once the single packet has been handed over; the decoder
    /// then gives back what it produced and waits for the next call.
    private static let noMoreInput: OSStatus = 0x6E6D6421 // 'nmd!'

    private static let inputProc: AudioConverterComplexInputDataProc = { _, packets, data, descriptions, user in
        let input = user!.assumingMemoryBound(to: PacketInput.self)
        guard !input.pointee.consumed else {
            packets.pointee = 0
            return AudioPlayer.noMoreInput
        }
        input.pointee.consumed = true
        packets.pointee = 1
        data.pointee.mNumberBuffers = 1
        data.pointee.mBuffers.mNumberChannels = 2
        data.pointee.mBuffers.mDataByteSize = input.pointee.length
        data.pointee.mBuffers.mData = UnsafeMutableRawPointer(mutating: input.pointee.data)
        input.pointee.description.pointee = AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: input.pointee.length)
        descriptions?.pointee = input.pointee.description
        return noErr
    }

    private func decode(_ data: UnsafeRawPointer, length: Int) -> AVAudioPCMBuffer? {
        guard let converter,
              let buffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: framesPerPacket * 2) else { return nil }
        buffer.frameLength = buffer.frameCapacity
        var frames = UInt32(buffer.frameCapacity)
        var input = PacketInput(data: data, length: UInt32(length), description: packetDescription, consumed: false)
        let status = withUnsafeMutablePointer(to: &input) {
            AudioConverterFillComplexBuffer(converter, Self.inputProc, $0, &frames, buffer.mutableAudioBufferList, nil)
        }
        if status != noErr && status != Self.noMoreInput {
            if !warned {
                warned = true
                log?("audio decode failed (\(status))")
            }
            return nil
        }
        buffer.frameLength = frames
        return frames > 0 ? buffer : nil
    }

    /// 16-bit little-endian interleaved stereo, as UxPlay passes PCM on.
    private func convertPcm(_ data: UnsafeRawBufferPointer) -> AVAudioPCMBuffer? {
        let frames = data.count / 4
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: AVAudioFrameCount(frames)),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(frames)
        let left = channels[0]
        let right = channels[1]
        for i in 0..<frames {
            left[i] = Float(Int16(littleEndian: data.loadUnaligned(fromByteOffset: i * 4, as: Int16.self))) / 32_768
            right[i] = Float(Int16(littleEndian: data.loadUnaligned(fromByteOffset: i * 4 + 2, as: Int16.self))) / 32_768
        }
        return buffer
    }

    // MARK: - Playback (lock held)

    private func play(_ buffer: AVAudioPCMBuffer) {
        guard ensureRunning() else { return }
        let frames = Int(buffer.frameLength)
        let current: Int? = queueLock.withLock {
            guard queuedFrames + frames <= Self.maxQueuedFrames else { return nil }
            queuedFrames += frames
            return generation
        }
        guard let current else { return }
        player.scheduleBuffer(buffer, completionCallbackType: .dataConsumed) { [weak self] _ in
            self?.consumed(frames, generation: current)
        }
        if !player.isPlaying { player.play() }
    }

    private func consumed(_ frames: Int, generation: Int) {
        queueLock.withLock {
            if generation == self.generation { queuedFrames = max(0, queuedFrames - frames) }
        }
    }

    private func resetPlayback() {
        queueLock.withLock {
            generation += 1
            queuedFrames = 0
        }
        if player.engine != nil, engine.isRunning { player.stop() }
    }

    /// Starts the engine on first use, and again after an interruption (a
    /// call, Siri) or an output change stopped it. `force` skips the wait
    /// between attempts.
    private func ensureRunning(force: Bool = false) -> Bool {
        if engine.isRunning {
            if keepAliveFlag { startKeeper() }
            return true
        }
        let now = CACurrentMediaTime()
        guard force || now - lastStartAttempt > 1 else { return false }
        lastStartAttempt = now
        do {
            let session = AVAudioSession.sharedInstance()
            // Mixed with other apps: the silence that keeps Mango Cast running
            // mustn't stop someone's music, and a mixable session may start
            // again from the background after an interruption.
            try session.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
            try? session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
            engine.prepare()
            try engine.start()
            player.volume = gain
            // Nodes don't carry on by themselves after the engine stopped.
            resetPlayback()
            if keepAliveFlag {
                keeper.stop()
                startKeeper()
            }
            return true
        } catch {
            log?("audio output unavailable: \(error.localizedDescription)")
            return false
        }
    }

    private lazy var silence: AVAudioPCMBuffer? = {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: 44_100),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = 44_100
        for channel in 0..<Int(output.channelCount) {
            channels[channel].initialize(repeating: 0, count: Int(buffer.frameLength))
        }
        return buffer
    }()

    private func startKeeper() {
        guard !keeper.isPlaying, let silence else { return }
        keeper.scheduleBuffer(silence, at: nil, options: .loops)
        keeper.play()
    }
}
