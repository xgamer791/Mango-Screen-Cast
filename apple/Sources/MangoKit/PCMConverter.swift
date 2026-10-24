import AudioToolbox
import CoreMedia
import Foundation
import MangoProtocol

/// Converts any linear PCM (float or integer, any rate, either endianness,
/// interleaved or not) to the wire format: 48 kHz, stereo, s16le, interleaved.
public final class PCMConverter {
    public static let outputFormat: AudioStreamBasicDescription = {
        var f = AudioStreamBasicDescription()
        f.mSampleRate = 48_000
        f.mFormatID = kAudioFormatLinearPCM
        f.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked
        f.mChannelsPerFrame = 2
        f.mBitsPerChannel = 16
        f.mBytesPerFrame = 4
        f.mFramesPerPacket = 1
        f.mBytesPerPacket = 4
        return f
    }()

    private var converter: AudioConverterRef?
    private var inputFormat = AudioStreamBasicDescription()

    public init() {}

    deinit {
        if let converter { AudioConverterDispose(converter) }
    }

    public func convert(_ sampleBuffer: CMSampleBuffer) -> Data? {
        guard let description = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee else { return nil }
        let frames = CMSampleBufferGetNumSamples(sampleBuffer)
        guard frames > 0 else { return nil }

        var sizeNeeded = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer, bufferListSizeNeededOut: &sizeNeeded, bufferListOut: nil, bufferListSize: 0,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        guard sizeNeeded > 0 else { return nil }
        let storage = UnsafeMutableRawPointer.allocate(byteCount: sizeNeeded, alignment: 16)
        defer { storage.deallocate() }
        let list = storage.bindMemory(to: AudioBufferList.self, capacity: 1)
        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer, bufferListSizeNeededOut: nil, bufferListOut: list, bufferListSize: sizeNeeded,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &blockBuffer)
        guard status == noErr else { return nil }
        return withExtendedLifetime(blockBuffer) {
            convert(bufferList: list, frameCount: frames, format: asbd)
        }
    }

    public func convert(bufferList: UnsafePointer<AudioBufferList>, frameCount: Int,
                        format: AudioStreamBasicDescription) -> Data? {
        guard frameCount > 0, format.mFormatID == kAudioFormatLinearPCM else { return nil }
        if Self.isOutputFormat(format) {
            let buffer = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: bufferList))[0]
            guard let data = buffer.mData else { return nil }
            return Data(bytes: data, count: min(Int(buffer.mDataByteSize), frameCount * 4))
        }
        if converter == nil || !Self.same(format, inputFormat) {
            if let converter { AudioConverterDispose(converter) }
            converter = nil
            inputFormat = format
            var input = format
            var output = Self.outputFormat
            var created: AudioConverterRef?
            guard AudioConverterNew(&input, &output, &created) == noErr, let created else { return nil }
            var quality = UInt32(kAudioConverterQuality_High)
            AudioConverterSetProperty(created, kAudioConverterSampleRateConverterQuality,
                                      UInt32(MemoryLayout<UInt32>.size), &quality)
            converter = created
        }
        guard let converter else { return nil }

        let capacity = Int((Double(frameCount) * 48_000 / max(format.mSampleRate, 1)).rounded(.up)) + 64
        var output = Data(count: capacity * 4)
        var context = InputContext(list: bufferList, frames: UInt32(frameCount), consumed: false)
        var produced = UInt32(capacity)
        let status: OSStatus = output.withUnsafeMutableBytes { raw in
            var outList = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(mNumberChannels: 2, mDataByteSize: UInt32(raw.count), mData: raw.baseAddress))
            return withUnsafeMutablePointer(to: &context) { contextPointer in
                AudioConverterFillComplexBuffer(converter, inputProc, contextPointer, &produced, &outList, nil)
            }
        }
        guard status == noErr || status == Self.noMoreInput else { return nil }
        output.count = Int(produced) * 4
        return output
    }

    private static func isOutputFormat(_ f: AudioStreamBasicDescription) -> Bool {
        f.mSampleRate == 48_000 && f.mChannelsPerFrame == 2 && f.mBitsPerChannel == 16
            && f.mFormatFlags & kAudioFormatFlagIsSignedInteger != 0
            && f.mFormatFlags & kAudioFormatFlagIsBigEndian == 0
            && f.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0
            && f.mFormatFlags & kAudioFormatFlagIsFloat == 0
    }

    private static func same(_ a: AudioStreamBasicDescription, _ b: AudioStreamBasicDescription) -> Bool {
        a.mSampleRate == b.mSampleRate && a.mFormatFlags == b.mFormatFlags
            && a.mChannelsPerFrame == b.mChannelsPerFrame && a.mBitsPerChannel == b.mBitsPerChannel
            && a.mBytesPerFrame == b.mBytesPerFrame
    }

    /// Returned by the input callback when this call's samples are used up.
    /// Not an error: the converter keeps its state for the next call.
    fileprivate static let noMoreInput: OSStatus = 0x6E6F6D6F // 'nomo'
}

private struct InputContext {
    var list: UnsafePointer<AudioBufferList>
    var frames: UInt32
    var consumed: Bool
}

private let inputProc: AudioConverterComplexInputDataProc = { _, ioPackets, ioData, _, userData in
    let context = userData!.assumingMemoryBound(to: InputContext.self)
    if context.pointee.consumed {
        ioPackets.pointee = 0
        return PCMConverter.noMoreInput
    }
    let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: context.pointee.list))
    let destination = UnsafeMutableAudioBufferListPointer(ioData)
    for i in 0..<min(source.count, destination.count) { destination[i] = source[i] }
    ioPackets.pointee = context.pointee.frames
    context.pointee.consumed = true
    return noErr
}

/// Cuts a PCM stream into fixed 10 ms frames for the wire.
public struct AudioChunker {
    public static let frameBytes = 1920 // 480 frames x 2 channels x 2 bytes
    private var buffer = Data()
    private var nextTimestamp: UInt64 = 0

    public init() {}

    /// `timestamp` is the capture time of the first sample in `pcm`.
    public mutating func push(_ pcm: Data, timestamp: UInt64, emit: (Data, UInt64) -> Void) {
        if buffer.isEmpty { nextTimestamp = timestamp }
        buffer.append(pcm)
        // Don't let a stalled consumer grow this without bound (200 ms).
        if buffer.count > Self.frameBytes * 20 {
            buffer.removeFirst(buffer.count - Self.frameBytes * 20)
        }
        var start = buffer.startIndex
        while buffer.endIndex - start >= Self.frameBytes {
            emit(Data(buffer[start..<(start + Self.frameBytes)]), nextTimestamp)
            start += Self.frameBytes
            nextTimestamp += 10_000
        }
        buffer = Data(buffer[start...])
    }
}
