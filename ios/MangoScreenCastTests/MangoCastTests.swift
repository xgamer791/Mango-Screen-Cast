import XCTest

final class MangoCastTests: XCTestCase {
    private let offer = Offer(name: "Mango iPad", maxWidth: 2360, maxHeight: 1640, fps: 60, h265: true, audio: true)

    private func hello(_ json: String) -> Hello {
        Hello.parse(Data(json.utf8))!
    }

    private func object(_ data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    func testReadsHello() {
        let h = hello("""
            {"protocol": 1, "name": "DESKTOP-4F2K", "os": "Windows 11", "codecs": ["H265", "h264"],
             "width": 2560, "height": 1440, "fps": 60, "audio": true, "extra": "ignored"}
            """)
        XCTAssertEqual(h, Hello(protocolVersion: 1, name: "DESKTOP-4F2K", os: "Windows 11", codecs: ["h265", "h264"],
                                width: 2560, height: 1440, fps: 60, audio: true))
    }

    func testHelloFillsInWhatsMissing() {
        let h = hello(#"{"protocol": 1}"#)
        XCTAssertEqual(h.name, "Windows")
        XCTAssertEqual(h.codecs, [])
        XCTAssertFalse(h.audio)
        XCTAssertNil(Hello.parse(Data("not json".utf8)))
        XCTAssertNil(Hello.parse(Data("[1, 2]".utf8)))
    }

    func testPicksTheSendersFirstCodecThisDeviceDecodes() {
        XCTAssertEqual(offer.codec(for: hello(#"{"codecs": ["h265", "h264"]}"#)), "h265")
        XCTAssertEqual(offer.codec(for: hello(#"{"codecs": ["vp9", "h264", "h265"]}"#)), "h264")
        var noHevc = offer
        noHevc.h265 = false
        XCTAssertEqual(noHevc.codec(for: hello(#"{"codecs": ["h265", "h264"]}"#)), "h264")
        XCTAssertNil(noHevc.codec(for: hello(#"{"codecs": ["h265"]}"#)))
        XCTAssertNil(offer.codec(for: hello(#"{"codecs": []}"#)))
    }

    func testScalesThePictureToFitKeepingItsShape() {
        let hd = Offer(name: "x", maxWidth: 1920, maxHeight: 1080, fps: 60, h265: false, audio: false)
        XCTAssertTrue(hd.picture(for: hello(#"{"width": 2560, "height": 1440}"#)) == (1920, 1080))
        // An ultrawide screen: as wide as this one takes, less tall, both sides even.
        XCTAssertTrue(hd.picture(for: hello(#"{"width": 3440, "height": 1440}"#)) == (1920, 802))
        XCTAssertTrue(hd.picture(for: hello(#"{"width": 1366, "height": 768}"#)) == (1366, 768))
        // A 16:9 PC on an iPad's 2360 × 1640: as wide as the iPad, less tall.
        XCTAssertTrue(offer.picture(for: hello(#"{"width": 3840, "height": 2160}"#)) == (2360, 1326))
        XCTAssertTrue(offer.picture(for: hello(#"{"width": 1281, "height": 1023}"#)) == (1280, 1022))
        XCTAssertTrue(offer.picture(for: hello("{}")) == (2360, 1640))
    }

    func testWelcomeSaysWhatToSend() {
        let json = object(offer.welcome(codec: "h265", hello: hello(#"{"audio": true}"#)))
        XCTAssertEqual(json["protocol"] as? Int, 1)
        XCTAssertEqual(json["name"] as? String, "Mango iPad")
        XCTAssertEqual(json["codec"] as? String, "h265")
        XCTAssertEqual(json["maxWidth"] as? Int, 2360)
        XCTAssertEqual(json["maxHeight"] as? Int, 1640)
        XCTAssertEqual(json["fps"] as? Int, 60)
        let audio = json["audio"] as? [String: Any]
        XCTAssertEqual(audio?["format"] as? String, "pcm_s16le")
        XCTAssertEqual(audio?["rate"] as? Int, 48_000)
        XCTAssertEqual(audio?["channels"] as? Int, 2)
    }

    func testNoSoundWhenItsOffHereOrTheSenderHasNone() {
        var quiet = offer
        quiet.audio = false
        XCTAssertTrue(object(quiet.welcome(codec: "h264", hello: hello(#"{"audio": true}"#)))["audio"] is NSNull)
        XCTAssertTrue(object(offer.welcome(codec: "h264", hello: hello(#"{"audio": false}"#)))["audio"] is NSNull)
    }

    func testAnnouncesWhatItTakes() {
        XCTAssertEqual(offer.txtRecord(id: "02a1b2c3d4e5", model: "iPad14,1"),
                       ["v": "1", "id": "02a1b2c3d4e5", "model": "iPad14,1", "w": "2360", "h": "1640", "fps": "60",
                        "codecs": "h265,h264", "audio": "1"])
    }

    func testFraming() {
        let frame = MangoCast.frame(.video, Data([1, 2, 3]))
        XCTAssertEqual([UInt8](frame), [0x10, 0, 0, 0, 3, 1, 2, 3])
        let header = MangoCast.header(frame)
        XCTAssertEqual(header?.type, 0x10)
        XCTAssertEqual(header?.length, 3)
        XCTAssertEqual(MangoCast.header(MangoCast.frame(0x3F, Data(count: 70_000)))?.length, 70_000)
        XCTAssertNil(MangoCast.header(Data([1, 0, 0])))
    }

    func testRefusal() {
        let json = object(Decision.refusal(reason: "busy", message: "A Mac is mirroring."))
        XCTAssertEqual(json["reason"] as? String, "busy")
        XCTAssertEqual(json["message"] as? String, "A Mac is mirroring.")
    }
}
