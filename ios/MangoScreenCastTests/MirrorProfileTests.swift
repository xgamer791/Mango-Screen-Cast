import CoreGraphics
import XCTest

final class MirrorProfileTests: XCTestCase {
    func testNativeOffersTheScreenInLandscape() {
        // iPad Air 11": nativeBounds are portrait.
        let air = MirrorProfile.make(screen: CGSize(width: 1640, height: 2360), hevc: true, setting: .native)
        XCTAssertEqual([air.width, air.height], [2360, 1640])
        XCTAssertTrue(air.h265)
        XCTAssertEqual([air.screenWidth, air.screenHeight], [2360, 1640])
        XCTAssertEqual(air.maxFps, 60)

        let pro = MirrorProfile.make(screen: CGSize(width: 2064, height: 2752), hevc: true, setting: .native)
        XCTAssertEqual([pro.width, pro.height], [2752, 2064])
    }

    func testFallsBackTo1080p() {
        let picked = MirrorProfile.make(screen: CGSize(width: 1640, height: 2360), hevc: true, setting: .hd1080)
        XCTAssertEqual([picked.width, picked.height], [1920, 1080])
        let noHevc = MirrorProfile.make(screen: CGSize(width: 1640, height: 2360), hevc: false, setting: .native)
        XCTAssertEqual([noHevc.width, noHevc.height], [1920, 1080])
        XCTAssertFalse(noHevc.h265)
    }

    func testFitKeepsTheShapeAndEvenSizes() {
        XCTAssertTrue(MirrorProfile.fit(4000, 3000, within: 3840, 2160) == (2880, 2160))
        XCTAssertTrue(MirrorProfile.fit(2361, 1641, within: 3840, 2160) == (2360, 1640))
        XCTAssertTrue(MirrorProfile.fit(0, 0, within: 3840, 2160) == (1920, 1080))
    }
}
