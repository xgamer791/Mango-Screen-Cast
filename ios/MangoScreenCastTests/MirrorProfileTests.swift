import CoreGraphics
import XCTest

final class MirrorProfileTests: XCTestCase {
    // nativeBounds are always portrait.
    private let air = CGSize(width: 1640, height: 2360)
    private let pro13 = CGSize(width: 2064, height: 2752)

    func testAutomaticOffersTheWholeScreenTheWayItIsHeld() {
        let landscape = MirrorProfile.make(screen: air, landscape: true, hevc: true, setting: .native)
        XCTAssertEqual([landscape.width, landscape.height], [2360, 1640])
        XCTAssertEqual([landscape.screenWidth, landscape.screenHeight], [2360, 1640])
        XCTAssertTrue(landscape.h265)
        XCTAssertEqual(landscape.maxFps, 60)

        let portrait = MirrorProfile.make(screen: air, landscape: false, hevc: true, setting: .native)
        XCTAssertEqual([portrait.width, portrait.height], [1640, 2360])
        XCTAssertEqual([portrait.screenWidth, portrait.screenHeight], [1640, 2360])

        let pro = MirrorProfile.make(screen: pro13, landscape: true, hevc: true, setting: .native)
        XCTAssertEqual([pro.width, pro.height], [2752, 2064])
    }

    func testSmallerChoicesKeepTheScreensShape() {
        let lighter = MirrorProfile.make(screen: air, landscape: true, hevc: true, setting: .hd1080)
        XCTAssertEqual([lighter.width, lighter.height], [1554, 1080])
        let lighterPortrait = MirrorProfile.make(screen: air, landscape: false, hevc: true, setting: .hd1080)
        XCTAssertEqual([lighterPortrait.width, lighterPortrait.height], [1080, 1554])

        let noHevc = MirrorProfile.make(screen: air, landscape: true, hevc: false, setting: .native)
        XCTAssertEqual([noHevc.width, noHevc.height], [1554, 1080])
        XCTAssertFalse(noHevc.h265)

        // A 4:3 iPad (10.2-inch).
        let classic = MirrorProfile.make(screen: CGSize(width: 1620, height: 2160), landscape: true, hevc: false,
                                         setting: .native)
        XCTAssertEqual([classic.width, classic.height], [1440, 1080])
    }

    func testFitKeepsTheShapeAndEvenSizes() {
        XCTAssertTrue(MirrorProfile.fit(4000, 3000, within: 3840, 2160) == (2880, 2160))
        XCTAssertTrue(MirrorProfile.fit(2361, 1641, within: 3840, 2160) == (2360, 1640))
        XCTAssertTrue(MirrorProfile.fit(0, 0, within: 3840, 2160) == (1920, 1080))
    }
}
