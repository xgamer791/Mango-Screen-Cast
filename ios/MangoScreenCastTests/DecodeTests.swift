import CoreMedia
import VideoToolbox
import XCTest

/// Feeds real H.264 and HEVC frames, shaped the way the AirPlay server hands
/// them over, through the same conversion the player uses, and checks that
/// VideoToolbox decodes them.
final class DecodeTests: XCTestCase {
    /// Regroups an ffmpeg stream into AirPlay-style frames: every NAL unit
    /// after a 4-byte start code, parameter sets in front of the keyframe.
    private func airPlayFrames(_ stream: Data, h265: Bool) -> [[UInt8]] {
        let bytes = [UInt8](stream)
        var frames: [[UInt8]] = []
        var pending: [UInt8] = []
        bytes.withUnsafeBytes { data in
            for unit in NalUnits.units(in: data, h265: h265) {
                pending += [0, 0, 0, 1]
                pending += bytes[unit.header..<unit.end]
                if NalUnits.isPicture(unit.type, h265: h265) {
                    frames.append(pending)
                    pending = []
                }
            }
        }
        return frames
    }

    private func decode(_ stream: Data, h265: Bool) throws {
        let frames = airPlayFrames(stream, h265: h265)
        XCTAssertEqual(frames.count, 3)

        let first = frames[0].withUnsafeBytes { NalUnits.frame($0, h265: h265) }
        XCTAssertTrue(first.keyframe)
        XCTAssertEqual(first.parameterSets.count, h265 ? 3 : 2)
        let format = try XCTUnwrap(VideoRenderer.makeFormat(first.parameterSets, h265: h265))
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        XCTAssertEqual([dimensions.width, dimensions.height], [96, 64])

        var session: VTDecompressionSession?
        let created = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,
                                                   imageBufferAttributes: nil, outputCallback: nil,
                                                   decompressionSessionOut: &session)
        guard created == noErr, let session else {
            throw XCTSkip("no \(h265 ? "HEVC" : "H.264") decoder here (\(created))")
        }
        defer { VTDecompressionSessionInvalidate(session) }

        var decoded = 0
        for bytes in frames {
            let sample = try bytes.withUnsafeBytes { data -> CMSampleBuffer in
                let frame = NalUnits.frame(data, h265: h265)
                return try makeSample(frame, data: data, format: format)
            }
            var frameStatus: OSStatus = -1
            var image: CVImageBuffer?
            let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [],
                                                           infoFlagsOut: nil) { status, _, buffer, _, _ in
                frameStatus = status
                image = buffer
            }
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            XCTAssertEqual(status, noErr)
            XCTAssertEqual(frameStatus, noErr)
            if let image {
                XCTAssertEqual(CVPixelBufferGetWidth(image), 96)
                XCTAssertEqual(CVPixelBufferGetHeight(image), 64)
                decoded += 1
            }
        }
        XCTAssertEqual(decoded, 3)
    }

    private func makeSample(_ frame: NalUnits.Frame, data: UnsafeRawBufferPointer,
                            format: CMVideoFormatDescription) throws -> CMSampleBuffer {
        let length = frame.sampleLength
        var block: CMBlockBuffer?
        XCTAssertEqual(CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: length, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: length, flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block), noErr)
        let buffer = try XCTUnwrap(block)
        var pointer: UnsafeMutablePointer<CChar>?
        XCTAssertEqual(CMBlockBufferGetDataPointer(buffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: nil,
                                                   dataPointerOut: &pointer), noErr)
        let destination = try XCTUnwrap(pointer)
        NalUnits.writeLengthPrefixed(frame.payload, from: data, to: UnsafeMutableRawPointer(destination))
        var sample: CMSampleBuffer?
        var size = length
        XCTAssertEqual(CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: buffer, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &size,
            sampleBufferOut: &sample), noErr)
        return try XCTUnwrap(sample)
    }

    func testDecodesH264() throws {
        try decode(Fixtures.h264, h265: false)
    }

    func testDecodesHevc() throws {
        try decode(Fixtures.hevc, h265: true)
    }

    /// Frames arriving while the app is in the background are kept, not
    /// counted as failures, and the picture carries on when it's back.
    func testThePlayerKeepsGoingThroughTheBackground() {
        for (stream, h265) in [(Fixtures.h264, false), (Fixtures.hevc, true)] {
            let renderer = VideoRenderer()
            renderer.start(h265: h265)
            let frames = airPlayFrames(stream, h265: h265)
            frames[0].withUnsafeBytes { renderer.submit($0, h265: h265) }
            renderer.setInBackground(true)
            for frame in frames.dropFirst() {
                frame.withUnsafeBytes { renderer.submit($0, h265: h265) }
            }
            renderer.setInBackground(false)
            let stats = renderer.stats()
            XCTAssertEqual(stats.frames, frames.count)
            XCTAssertEqual(stats.dropped, 0)
            XCTAssertEqual(stats.recoveries, 0)
            renderer.stop()
        }
    }
}
