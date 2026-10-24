import CoreMedia
import CoreVideo
import Foundation
import MangoProtocol
import VideoToolbox

public struct EncodedFrame {
    /// Annex B bitstream. Keyframes start with their parameter sets.
    public let data: Data
    public let isKeyframe: Bool
    public let timestampMicros: UInt64
}

/// Hardware HEVC/H.264 encoder tuned for latency: real-time mode, no frame
/// reordering, low-latency rate control where available, keyframes on demand.
public final class VideoEncoder {
    public enum EncoderError: Error {
        case cannotCreateSession(OSStatus)
    }

    public let codec: VideoCodec
    public let width: Int
    public let height: Int
    public let fps: Int
    public private(set) var bitrateKbps: Int

    /// Called on VideoToolbox's thread for each encoded frame.
    public var onFrame: ((EncodedFrame) -> Void)?

    private var session: VTCompressionSession?
    private let lock = NSLock()
    private var keyframeRequested = true
    private static let startCode: [UInt8] = [0, 0, 0, 1]

    public init(codec: VideoCodec, width: Int, height: Int, fps: Int, bitrateKbps: Int) throws {
        self.codec = codec
        self.width = width
        self.height = height
        self.fps = fps
        self.bitrateKbps = bitrateKbps
        session = try Self.makeSession(codec: codec, width: width, height: height)
        configure()
    }

    deinit { invalidate() }

    private static func makeSession(codec: VideoCodec, width: Int, height: Int) throws -> VTCompressionSession {
        let codecType = codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264
        var specifications: [[CFString: Any]] = []
        #if os(macOS)
        specifications.append([
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true,
            kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true,
        ])
        specifications.append([kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true])
        #else
        specifications.append([kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true])
        #endif
        specifications.append([:])

        var lastStatus: OSStatus = 0
        for specification in specifications {
            var session: VTCompressionSession?
            lastStatus = VTCompressionSessionCreate(
                allocator: nil, width: Int32(width), height: Int32(height), codecType: codecType,
                encoderSpecification: specification as CFDictionary, imageBufferAttributes: nil,
                compressedDataAllocator: nil, outputCallback: nil, refcon: nil,
                compressionSessionOut: &session)
            if lastStatus == noErr, let session { return session }
        }
        throw EncoderError.cannotCreateSession(lastStatus)
    }

    private func configure() {
        guard let session else { return }
        func set(_ key: CFString, _ value: CFTypeRef) {
            // Not every encoder supports every key; unsupported ones are skipped.
            _ = VTSessionSetProperty(session, key: key, value: value)
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_ProfileLevel,
            codec == .hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
        if codec == .h264 { set(kVTCompressionPropertyKey_H264EntropyMode, kVTH264EntropyMode_CABAC) }
        set(kVTCompressionPropertyKey_ExpectedFrameRate, fps as CFNumber)
        // Keyframes come on request (session start, packet loss). The long
        // interval is only a safety net.
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, (fps * 30) as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 30 as CFNumber)
        set(kVTCompressionPropertyKey_MaxFrameDelayCount, 0 as CFNumber)
        set(kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_ColorPrimaries, kCVImageBufferColorPrimaries_ITU_R_709_2)
        set(kVTCompressionPropertyKey_TransferFunction, kCVImageBufferTransferFunction_ITU_R_709_2)
        set(kVTCompressionPropertyKey_YCbCrMatrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
        applyBitrate()
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    private func applyBitrate() {
        guard let session else { return }
        let bitsPerSecond = bitrateKbps * 1000
        _ = VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate,
                                 value: bitsPerSecond as CFNumber)
        // Cap bursts at 1.5x the average over any one second.
        let limits = [NSNumber(value: Double(bitsPerSecond) / 8 * 1.5), NSNumber(value: 1.0)] as CFArray
        _ = VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: limits)
    }

    public func setBitrate(kbps: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard kbps != bitrateKbps else { return }
        bitrateKbps = kbps
        applyBitrate()
    }

    public func requestKeyframe() {
        lock.lock()
        keyframeRequested = true
        lock.unlock()
    }

    public func encode(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime) {
        lock.lock()
        guard let session else { lock.unlock(); return }
        let forceKeyframe = keyframeRequested
        keyframeRequested = false
        lock.unlock()

        let properties: CFDictionary? = forceKeyframe
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue!] as CFDictionary
            : nil
        let status = VTCompressionSessionEncodeFrame(
            session, imageBuffer: pixelBuffer, presentationTimeStamp: presentationTime,
            duration: .invalid, frameProperties: properties, infoFlagsOut: nil
        ) { [weak self] status, _, sampleBuffer in
            guard status == noErr, let sampleBuffer, let self else { return }
            self.emit(sampleBuffer)
        }
        if status != noErr {
            if forceKeyframe { requestKeyframe() }
            // The session dies after sleep or GPU resets; build a new one.
            if status == kVTInvalidSessionErr { rebuild() }
        }
    }

    private func rebuild() {
        lock.lock()
        defer { lock.unlock() }
        if let old = session { VTCompressionSessionInvalidate(old) }
        session = try? Self.makeSession(codec: codec, width: width, height: height)
        keyframeRequested = true
        configure()
    }

    public func invalidate() {
        lock.lock()
        let old = session
        session = nil
        lock.unlock()
        if let old {
            VTCompressionSessionCompleteFrames(old, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(old)
        }
    }

    // MARK: - Output

    private func emit(_ sampleBuffer: CMSampleBuffer) {
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer),
              let format = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        let isKeyframe = Self.isKeyframe(sampleBuffer)
        let (parameterSets, nalLengthSize) = Self.parameterSets(format, codec: codec)

        let total = CMBlockBufferGetDataLength(block)
        var out = Data()
        out.reserveCapacity(total + 512)
        if isKeyframe {
            for set in parameterSets {
                out.append(contentsOf: Self.startCode)
                out.append(set)
            }
        }

        var bytes = [UInt8](repeating: 0, count: total)
        guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: total, destination: &bytes) == noErr else { return }
        // Length-prefixed NAL units (AVCC/HVCC) to start codes (Annex B).
        var offset = 0
        while offset + nalLengthSize <= total {
            var length = 0
            for i in 0..<nalLengthSize { length = length << 8 | Int(bytes[offset + i]) }
            offset += nalLengthSize
            guard length > 0, offset + length <= total else { break }
            out.append(contentsOf: Self.startCode)
            out.append(contentsOf: bytes[offset..<(offset + length)])
            offset += length
        }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let micros = pts.isValid ? UInt64(max(0, CMTimeGetSeconds(pts) * 1_000_000)) : MonotonicClock.micros
        onFrame?(EncodedFrame(data: out, isKeyframe: isKeyframe, timestampMicros: micros))
    }

    private static func isKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[CFString: Any]], let first = attachments.first else { return true }
        return !((first[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)
    }

    private static func parameterSets(_ format: CMFormatDescription, codec: VideoCodec) -> ([Data], Int) {
        var count = 0
        var nalLength: Int32 = 4
        var sets: [Data] = []
        if codec == .hevc {
            CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLength)
            for i in 0..<count {
                var pointer: UnsafePointer<UInt8>?
                var size = 0
                if CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                    format, parameterSetIndex: i, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr, let pointer {
                    sets.append(Data(bytes: pointer, count: size))
                }
            }
        } else {
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLength)
            for i in 0..<count {
                var pointer: UnsafePointer<UInt8>?
                var size = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: i, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr, let pointer {
                    sets.append(Data(bytes: pointer, count: size))
                }
            }
        }
        return (sets, Int(nalLength))
    }
}
