import AVFoundation
import CoreMedia
import QuartzCore

/// Hardware H.264/HEVC decoding onto an AVSampleBufferDisplayLayer, each
/// frame shown the moment it's decoded.
///
/// Frames arrive on the AirPlay mirroring thread and go straight to the
/// layer, which decodes asynchronously, so the network is never kept
/// waiting (Apple devices lower their bitrate when a receiver reads slowly).
///
/// AirPlay senders can't be asked for a keyframe and send few of them, so
/// the frames since the last one are kept. If the layer fails (a decode
/// error, or iOS interrupting it), it's flushed and fed those frames again
/// without showing them, and the picture comes back at once instead of
/// staying frozen until the next keyframe.
///
/// In the background iOS stops decoding (unless the picture is in Picture in
/// Picture). Frames are then only kept, more of them than usual, and played
/// through to the latest one, unseen, when the app comes back.
final class VideoRenderer {
    struct Stats {
        var width = 0
        var height = 0
        var codec = ""
        var fps = 0.0
        var megabitsPerSecond = 0.0
        var frames = 0
        var dropped = 0
        var recoveries = 0
    }

    let layer = AVSampleBufferDisplayLayer()

    /// Zoom the picture to cover the whole screen, cutting off what doesn't
    /// fit, instead of showing all of it. Main thread.
    var fillsScreen = false {
        didSet { updateGravity() }
    }

    /// The size of the view showing the layer. Main thread.
    var viewSize = CGSize.zero {
        didSet { if viewSize != oldValue { updateGravity() } }
    }

    /// The picture's size, kept on the main thread for updateGravity().
    private var pictureSize = CGSize.zero

    /// The picture's size, once a stream has given it. Main thread.
    var shownPictureSize: CGSize? { pictureSize.width > 0 ? pictureSize : nil }

    /// Told on the main thread when the picture's size changes.
    var onPictureSize: ((CGSize) -> Void)?

    /// Receives one line per notable event (stream start, format change, recovery).
    var log: ((String) -> Void)?

    private struct Stored {
        let block: CMBlockBuffer
        let length: Int
        let keyframe: Bool
    }

    private static let maxGopBytes = 64 << 20
    private static let maxGopFrames = 600
    /// In the background, where nothing is decoded until the app returns.
    private static let maxBackgroundGopBytes = 128 << 20
    private static let maxBackgroundGopFrames = 3600

    private let lock = NSLock()
    private var active = false
    private var h265 = false
    private var format: CMVideoFormatDescription?
    private var parameterSets: [Data] = []
    private var waitingForKeyframe = true
    private var gop: [Stored] = []
    private var gopBytes = 0
    /// False once the frames since the last keyframe outgrew the cache.
    private var gopComplete = false
    private var inBackground = false
    /// Frames kept in the background but not decoded yet.
    private var behind = false

    private var size = (width: 0, height: 0)
    private var frames = 0
    private var dropped = 0
    private var recoveries = 0
    private var windowStart = CACurrentMediaTime()
    private var windowFrames = 0
    private var windowBytes = 0
    private var fps = 0.0
    private var megabitsPerSecond = 0.0

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor(gray: 0, alpha: 1)
    }

    /// A new mirroring stream begins.
    func start(h265: Bool) {
        lock.lock()
        defer { lock.unlock() }
        reset()
        self.h265 = h265
        active = true
        flushRenderer(removeImage: true)
    }

    /// The stream ended: clear the picture and let go of the cached frames.
    func stop() {
        lock.lock()
        defer { lock.unlock() }
        guard active else { return }
        active = false
        reset()
        flushRenderer(removeImage: true)
    }

    /// One frame from the AirPlay server (see mango_bridge.h). Only valid
    /// during the call; everything needed is copied out.
    func submit(_ data: UnsafeRawBufferPointer, h265: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard active else { return }
        rollWindow(now: CACurrentMediaTime(), bytes: data.count)

        let frame = NalUnits.frame(data, h265: h265)
        if !frame.parameterSets.isEmpty, frame.parameterSets != parameterSets || h265 != self.h265 {
            updateFormat(frame.parameterSets, h265: h265)
        }
        guard format != nil, frame.hasPicture else { return }
        if waitingForKeyframe && !frame.keyframe {
            dropped += 1
            return
        }
        guard let stored = makeBlock(frame, from: data) else {
            dropped += 1
            return
        }
        waitingForKeyframe = false
        frames += 1
        windowFrames += 1
        keep(stored)

        if inBackground && (behind || rendererFailed || requiresFlush) {
            // iOS isn't decoding for the app now: catch up when it's back.
            behind = true
        } else if rendererFailed {
            recover()
        } else if let sample = makeSample(stored, display: true) {
            enqueue(sample)
        }
    }

    /// The app went to the background, or came back. Coming back, frames
    /// that came in meanwhile are decoded through to the latest one.
    func setInBackground(_ background: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard background != inBackground else { return }
        inBackground = background
        guard !background, active, behind || rendererFailed || requiresFlush else { return }
        behind = false
        flushRenderer(removeImage: false)
        guard gopComplete, !gop.isEmpty else {
            log?("back in the foreground; the picture returns with the next keyframe")
            waitingForKeyframe = true
            return
        }
        log?("back in the foreground; catching up \(gop.count) frames")
        replayGop()
    }

    func stats() -> Stats {
        lock.lock()
        defer { lock.unlock() }
        rollWindow(now: CACurrentMediaTime(), bytes: 0)
        return Stats(width: size.width, height: size.height, codec: h265 ? "HEVC" : "H.264", fps: fps,
                     megabitsPerSecond: megabitsPerSecond, frames: frames, dropped: dropped, recoveries: recoveries)
    }

    // MARK: - Stream state (lock held)

    private func reset() {
        format = nil
        parameterSets = []
        waitingForKeyframe = true
        gop = []
        gopBytes = 0
        gopComplete = false
        behind = false
        size = (0, 0)
        frames = 0
        dropped = 0
        recoveries = 0
        windowStart = CACurrentMediaTime()
        windowFrames = 0
        windowBytes = 0
        fps = 0
        megabitsPerSecond = 0
    }

    /// Frame rate and bitrate over the last second or so. Macs only send a
    /// frame when the screen changes, so a still screen reads as a low rate.
    private func rollWindow(now: CFTimeInterval, bytes: Int) {
        windowBytes += bytes
        let elapsed = now - windowStart
        guard elapsed >= 1 else { return }
        fps = Double(windowFrames) / elapsed
        megabitsPerSecond = Double(windowBytes) * 8 / elapsed / 1_000_000
        windowStart = now
        windowFrames = 0
        windowBytes = 0
    }

    private func keep(_ stored: Stored) {
        if stored.keyframe {
            gop = []
            gopBytes = 0
            gopComplete = true
        }
        guard gopComplete else { return }
        let maxFrames = inBackground ? Self.maxBackgroundGopFrames : Self.maxGopFrames
        let maxBytes = inBackground ? Self.maxBackgroundGopBytes : Self.maxGopBytes
        if gop.count >= maxFrames || gopBytes + stored.length > maxBytes {
            gop = []
            gopBytes = 0
            gopComplete = false
            return
        }
        gop.append(stored)
        gopBytes += stored.length
    }

    private func updateFormat(_ sets: [Data], h265: Bool) {
        guard let next = Self.makeFormat(sets, h265: h265) else {
            log?("couldn't read the stream's parameter sets (\(h265 ? "HEVC" : "H.264"))")
            return
        }
        format = next
        parameterSets = sets
        self.h265 = h265
        let dimensions = CMVideoFormatDescriptionGetPresentationDimensions(next, usePixelAspectRatio: false,
                                                                           useCleanAperture: true)
        let width = Int(dimensions.width.rounded())
        let height = Int(dimensions.height.rounded())
        if width != size.width || height != size.height {
            size = (width, height)
            log?("video \(width)x\(height) \(h265 ? "HEVC" : "H.264")")
            DispatchQueue.main.async {
                self.pictureSize = CGSize(width: width, height: height)
                self.updateGravity()
                self.onPictureSize?(self.pictureSize)
            }
        }
    }

    private func recover() {
        recoveries += 1
        let reason = rendererError.map { " (\($0.localizedDescription))" } ?? ""
        flushRenderer(removeImage: false)
        guard gopComplete, !gop.isEmpty else {
            log?("display failed\(reason); waiting for the next keyframe")
            waitingForKeyframe = true
            return
        }
        log?("display failed\(reason); restarting from the last keyframe (\(gop.count) frames)")
        replayGop()
    }

    /// Decodes the frames since the last keyframe, showing only the latest.
    private func replayGop() {
        for (index, stored) in gop.enumerated() {
            if let sample = makeSample(stored, display: index == gop.count - 1) {
                enqueue(sample)
            }
        }
    }

    // MARK: - Core Media

    /// Parameter sets in the order Core Media wants: VPS, SPS, PPS.
    static func makeFormat(_ sets: [Data], h265: Bool) -> CMVideoFormatDescription? {
        let ordered = sets.enumerated().sorted { a, b in
            let rankA = a.element.first.map { NalUnits.type($0, h265: h265) } ?? 0
            let rankB = b.element.first.map { NalUnits.type($0, h265: h265) } ?? 0
            return rankA != rankB ? rankA < rankB : a.offset < b.offset
        }.map(\.element)
        let total = ordered.reduce(0) { $0 + $1.count }
        guard total > 0 else { return nil }
        let storage = UnsafeMutablePointer<UInt8>.allocate(capacity: total)
        defer { storage.deallocate() }
        var pointers: [UnsafePointer<UInt8>] = []
        var sizes: [Int] = []
        var offset = 0
        for set in ordered {
            set.copyBytes(to: storage + offset, count: set.count)
            pointers.append(UnsafePointer(storage + offset))
            sizes.append(set.count)
            offset += set.count
        }
        var description: CMFormatDescription?
        let status: OSStatus
        if h265 {
            status = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &description)
        } else {
            status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: kCFAllocatorDefault, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description)
        }
        return status == noErr ? description : nil
    }

    private func makeBlock(_ frame: NalUnits.Frame, from data: UnsafeRawBufferPointer) -> Stored? {
        let length = frame.sampleLength
        guard length > 0 else { return nil }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: length, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: length, flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block) == noErr, let block else { return nil }
        var pointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: nil,
                                          dataPointerOut: &pointer) == noErr, let pointer else { return nil }
        NalUnits.writeLengthPrefixed(frame.payload, from: data, to: UnsafeMutableRawPointer(pointer))
        return Stored(block: block, length: length, keyframe: frame.keyframe)
    }

    private func makeSample(_ stored: Stored, display: Bool) -> CMSampleBuffer? {
        guard let format else { return nil }
        var sample: CMSampleBuffer?
        var length = stored.length
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: stored.block, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &length,
            sampleBufferOut: &sample) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            Self.set(dictionary, kCMSampleAttachmentKey_DisplayImmediately, true)
            if !display { Self.set(dictionary, kCMSampleAttachmentKey_DoNotDisplay, true) }
            if !stored.keyframe {
                Self.set(dictionary, kCMSampleAttachmentKey_NotSync, true)
                Self.set(dictionary, kCMSampleAttachmentKey_DependsOnOthers, true)
            }
        }
        return sample
    }

    private static func set(_ dictionary: CFMutableDictionary, _ key: CFString, _ value: Bool) {
        let boolean: CFBoolean = value ? kCFBooleanTrue! : kCFBooleanFalse!
        CFDictionarySetValue(dictionary, Unmanaged.passUnretained(key).toOpaque(),
                             Unmanaged.passUnretained(boolean).toOpaque())
    }

    /// A picture of the screen's own shape fills it exactly. The sizes on
    /// offer can be a pixel or two off, which would leave a hairline bar, so
    /// within 1% it's filled (losing at most that pixel). Other shapes are
    /// shown whole unless `fillsScreen` (and never cropped when it's the
    /// other way round from the screen).
    private func updateGravity() {
        var sameShape = false
        var turned = false
        if pictureSize.width > 0, pictureSize.height > 0, viewSize.width > 0, viewSize.height > 0 {
            let picture = pictureSize.width / pictureSize.height
            let view = viewSize.width / viewSize.height
            sameShape = abs(picture - view) / view < 0.01
            // An upright picture on a screen turned sideways (or the other
            // way): filling would cut off most of it, so it's shown whole.
            turned = (picture > 1) != (view > 1)
        }
        let gravity: AVLayerVideoGravity = (fillsScreen && !turned) || sameShape ? .resizeAspectFill : .resizeAspect
        if layer.videoGravity != gravity { layer.videoGravity = gravity }
    }

    // MARK: - Renderer (sampleBufferRenderer from iOS 17)

    private var rendererFailed: Bool {
        if #available(iOS 17.0, *) { return layer.sampleBufferRenderer.status == .failed }
        return layer.status == .failed
    }

    private var rendererError: Error? {
        if #available(iOS 17.0, *) { return layer.sampleBufferRenderer.error }
        return layer.error
    }

    /// iOS interrupted decoding (the app went to the background).
    private var requiresFlush: Bool {
        if #available(iOS 17.0, *) { return layer.sampleBufferRenderer.requiresFlushToResumeDecoding }
        return layer.requiresFlushToResumeDecoding
    }

    private func enqueue(_ sample: CMSampleBuffer) {
        if #available(iOS 17.0, *) {
            layer.sampleBufferRenderer.enqueue(sample)
        } else {
            layer.enqueue(sample)
        }
    }

    private func flushRenderer(removeImage: Bool) {
        if #available(iOS 17.0, *) {
            if removeImage {
                layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
            } else {
                layer.sampleBufferRenderer.flush()
            }
        } else if removeImage {
            layer.flushAndRemoveImage()
        } else {
            layer.flush()
        }
    }
}
