import XCTest

final class NalUnitsTests: XCTestCase {
    private func units(_ bytes: [UInt8], h265: Bool = false) -> [NalUnits.Unit] {
        bytes.withUnsafeBytes { NalUnits.units(in: $0, h265: h265) }
    }

    func testSplitsOnFourAndThreeByteStartCodes() {
        let frame: [UInt8] = [0, 0, 0, 1, 0x67, 0xAA, 0xBB,
                              0, 0, 1, 0x68, 0xCC,
                              0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33]
        XCTAssertEqual(units(frame), [
            NalUnits.Unit(header: 4, end: 7, type: 7),
            NalUnits.Unit(header: 10, end: 12, type: 8),
            NalUnits.Unit(header: 16, end: 20, type: 5),
        ])
    }

    func testIgnoresBytesBeforeTheFirstStartCode() {
        XCTAssertEqual(units([0x12, 0x34, 0, 0, 0, 1, 0x41, 0x9A]), [NalUnits.Unit(header: 6, end: 8, type: 1)])
        XCTAssertEqual(units([0x12, 0x34, 0x56, 0x01, 0x02]), [])
    }

    func testKeyframeLosesItsParameterSetsAndGetsLengthPrefixes() {
        let bytes: [UInt8] = [0, 0, 0, 1, 0x67, 0xAA, 0xBB,
                              0, 0, 0, 1, 0x68, 0xCC,
                              0, 0, 0, 1, 0x06, 0x05,
                              0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33]
        bytes.withUnsafeBytes { data in
            let frame = NalUnits.frame(data, h265: false)
            XCTAssertEqual(frame.parameterSets, [Data([0x67, 0xAA, 0xBB]), Data([0x68, 0xCC])])
            XCTAssertTrue(frame.keyframe)
            XCTAssertTrue(frame.hasPicture)
            XCTAssertEqual(frame.sampleLength, 4 + 2 + 4 + 4)

            var out = [UInt8](repeating: 0xFF, count: frame.sampleLength)
            out.withUnsafeMutableBytes { NalUnits.writeLengthPrefixed(frame.payload, from: data, to: $0.baseAddress!) }
            XCTAssertEqual(out, [0, 0, 0, 2, 0x06, 0x05, 0, 0, 0, 4, 0x65, 0x11, 0x22, 0x33])
        }
    }

    func testPFrameIsNotAKeyframe() {
        [UInt8]([0, 0, 0, 1, 0x41, 0x9A, 0x02]).withUnsafeBytes { data in
            let frame = NalUnits.frame(data, h265: false)
            XCTAssertFalse(frame.keyframe)
            XCTAssertTrue(frame.hasPicture)
            XCTAssertTrue(frame.parameterSets.isEmpty)
        }
    }

    func testHevcNalTypes() {
        // VPS, SPS, PPS, IDR_W_RADL, then a trailing picture.
        let bytes: [UInt8] = [0, 0, 0, 1, 0x40, 0x01, 0x0C,
                              0, 0, 0, 1, 0x42, 0x01, 0x01,
                              0, 0, 0, 1, 0x44, 0x01, 0xC1,
                              0, 0, 0, 1, 0x26, 0x01, 0xAF]
        XCTAssertEqual(units(bytes, h265: true).map(\.type), [32, 33, 34, 19])
        bytes.withUnsafeBytes { data in
            let frame = NalUnits.frame(data, h265: true)
            XCTAssertEqual(frame.parameterSets.count, 3)
            XCTAssertTrue(frame.keyframe)
        }
        XCTAssertFalse(NalUnits.isKeyframe(NalUnits.type(0x02, h265: true), h265: true))
    }
}
