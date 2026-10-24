import XCTest

final class AudioCookieTests: XCTestCase {
    func testEsdsWrapsTheAudioSpecificConfig() {
        XCTAssertEqual(AudioCookie.esds(audioSpecificConfig: AudioCookie.aacEld), [
            0x03, 0x1B, 0x00, 0x00, 0x00,
            0x04, 0x13, 0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x05, 0x04, 0xF8, 0xE8, 0x50, 0x00,
            0x06, 0x01, 0x02,
        ])
    }

    func testVolume() {
        XCTAssertEqual(AudioCookie.gain(decibels: 0), 1)
        XCTAssertEqual(AudioCookie.gain(decibels: -144), 0)
        XCTAssertEqual(AudioCookie.gain(decibels: -20), 0.1, accuracy: 0.0001)
        XCTAssertEqual(AudioCookie.gain(decibels: -60), AudioCookie.gain(decibels: -30))
    }
}
