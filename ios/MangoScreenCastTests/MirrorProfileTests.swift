import CoreGraphics
import XCTest

final class MirrorProfileTests: XCTestCase {
    // nativeBounds are always portrait.
    private let air = CGSize(width: 1640, height: 2360)
    private let pro13 = CGSize(width: 2064, height: 2752)
    private let classic = CGSize(width: 1620, height: 2160) // 10.2-inch, 4:3
    private let mini = CGSize(width: 1488, height: 2266)

    func testFullSizeIsTheWholeScreenTheWayItIsHeld() {
        let landscape = MirrorProfile.make(screen: air, landscape: true, hevc: true)
        XCTAssertEqual([landscape.width, landscape.height], [2360, 1640])
        XCTAssertEqual([landscape.screenWidth, landscape.screenHeight], [2360, 1640])
        XCTAssertEqual(landscape.scale, 100)
        XCTAssertTrue(landscape.h265)
        XCTAssertEqual(landscape.maxFps, 60)

        let portrait = MirrorProfile.make(screen: air, landscape: false, hevc: true)
        XCTAssertEqual([portrait.width, portrait.height], [1640, 2360])

        let pro = MirrorProfile.make(screen: pro13, landscape: true, hevc: true)
        XCTAssertEqual([pro.width, pro.height], [2752, 2064])
    }

    func testSmallerSizesKeepTheScreensShape() {
        func size(_ screen: CGSize, landscape: Bool = true, hevc: Bool = true, _ scale: Int) -> [Int] {
            let p = MirrorProfile.make(screen: screen, landscape: landscape, hevc: hevc, scale: scale)
            return [p.width, p.height]
        }
        XCTAssertEqual(size(air, 90), [2124, 1476])
        XCTAssertEqual(size(air, 80), [1888, 1312])
        XCTAssertEqual(size(air, 75), [1770, 1230])
        XCTAssertEqual(size(air, 50), [1180, 820])
        XCTAssertEqual(size(air, landscape: false, 90), [1476, 2124])
        XCTAssertEqual(size(classic, 75), [1616, 1212])
        XCTAssertEqual(size(pro13, 50), [1376, 1032])
        // Out-of-range values are clamped.
        XCTAssertEqual(size(air, 30), size(air, 40))
        // Larger than the screen, up to 4K's height.
        XCTAssertEqual(size(air, 120), [2832, 1968])
        XCTAssertEqual(size(air, 1000), [3108, 2160])
        XCTAssertEqual(size(air, landscape: false, 1000), [2160, 3108])
        // Without HEVC the full size is about 1080p, same shape, and nothing is larger.
        XCTAssertEqual(size(air, hevc: false, 100), [1554, 1080])
        XCTAssertEqual(size(air, hevc: false, 120), [1554, 1080])
    }

    func testEveryChoiceFillsTheScreen() {
        for screen in [air, pro13, classic, mini, CGSize(width: 1179, height: 2556)] {
            for landscape in [true, false] {
                let choices = MirrorProfile.choices(screen: screen, landscape: landscape, hevc: true)
                XCTAssertGreaterThan(choices.first?.scale ?? 0, 100, "\(screen)")
                XCTAssertTrue(choices.contains { $0.scale == 100 })
                XCTAssertEqual(choices.map(\.scale), choices.map(\.scale).sorted(by: >))
                XCTAssertEqual(choices.map(\.height), choices.map(\.height).sorted(by: >))
                for choice in choices {
                    let shape = Double(choice.screenWidth) / Double(choice.screenHeight)
                    let picture = Double(choice.width) / Double(choice.height)
                    XCTAssertLessThan(abs(picture - shape) / shape, 0.001, "\(choice.sizeText) on \(screen)")
                    XCTAssertEqual(choice.width % 2, 0)
                    XCTAssertEqual(choice.height % 2, 0)
                    XCTAssertGreaterThanOrEqual(min(choice.width, choice.height), 480)
                    XCTAssertLessThanOrEqual(max(choice.width, choice.height), 3840)
                    XCTAssertLessThanOrEqual(min(choice.width, choice.height), 2160)
                }
            }
        }
        XCTAssertEqual(MirrorProfile.choices(screen: air, landscape: true, hevc: true).count,
                       MirrorProfile.largerScales(upTo: 131).count + MirrorProfile.scales.count)
        // Without HEVC, nothing larger than full.
        XCTAssertEqual(MirrorProfile.choices(screen: air, landscape: true, hevc: false).first?.scale, 100)
    }

    func testTheFullSizeIsNativeWithHEVC() {
        let choices = MirrorProfile.choices(screen: classic, landscape: true, hevc: true)
        let full = choices.first { $0.scale == 100 }
        XCTAssertEqual(full.map { [$0.width, $0.height] }, [2160, 1620])
        XCTAssertEqual(choices.filter(\.isNative).map(\.scale), [100])
        // Without HEVC, full is about 1080p: not the screen's own pixels.
        XCTAssertFalse(MirrorProfile.make(screen: classic, landscape: true, hevc: false).isNative)
        // An odd side, rounded down to even, still counts.
        XCTAssertTrue(MirrorProfile.make(screen: CGSize(width: 1179, height: 2556), landscape: true, hevc: true).isNative)
    }

    /// The 10.2-inch iPad (2160 x 1620, 4:3): seven larger sizes up to
    /// 2880 x 2160, its native size and twelve smaller ones, every one
    /// exactly 4:3.
    func testATenPointTwoInchIPadGetsLargerSizesNativeAndTwelveSmallerFourByThreeSizes() {
        let choices = MirrorProfile.choices(screen: classic, landscape: true, hevc: true)
        XCTAssertEqual(choices.map(\.scale), [133, 130, 125, 120, 115, 110, 105] + MirrorProfile.scales)
        let sizes = choices.map { [$0.width, $0.height] }
        XCTAssertEqual(sizes, [[2880, 2160], [2808, 2106], [2696, 2022], [2592, 1944], [2480, 1860], [2376, 1782],
                               [2264, 1698], [2160, 1620], [2048, 1536], [1944, 1458], [1832, 1374], [1728, 1296], [1616, 1212],
                               [1512, 1134], [1424, 1068], [1296, 972], [1184, 888], [1080, 810], [968, 726],
                               [864, 648]])
        for size in sizes {
            XCTAssertEqual(size[0] * 3, size[1] * 4, "\(size[0]) x \(size[1])")
        }
    }

    func testFitKeepsTheShapeAndEvenSizes() {
        XCTAssertTrue(MirrorProfile.fit(4000, 3000, within: 3840, 2160) == (2880, 2160))
        XCTAssertTrue(MirrorProfile.fit(2361, 1641, within: 3840, 2160) == (2360, 1640))
        XCTAssertTrue(MirrorProfile.fit(0, 0, within: 3840, 2160) == (1920, 1080))
        XCTAssertTrue(MirrorProfile.fit(2160, 1620, within: 3840, 2160, enlarging: true) == (2880, 2160))
    }

    func testLargerStepsAreEveryFivePercentBelowTheLargest() {
        XCTAssertEqual(MirrorProfile.largerScales(upTo: 133), [133, 130, 125, 120, 115, 110, 105])
        XCTAssertEqual(MirrorProfile.largerScales(upTo: 131), [131, 125, 120, 115, 110, 105])
        XCTAssertEqual(MirrorProfile.largerScales(upTo: 104), [104])
        XCTAssertEqual(MirrorProfile.largerScales(upTo: 100), [])
        // The 13-inch iPad Pro is almost 4K's height already: one larger size.
        let pro = MirrorProfile.choices(screen: pro13, landscape: true, hevc: true)
        XCTAssertEqual(pro.prefix(2).map { [$0.width, $0.height] }, [[2880, 2160], [2752, 2064]])
    }

    /// The iPad (9th generation) starts at half size: its screen in points.
    func testHalfOfANinthGenerationIPadIs810By1080() {
        let screen = CGSize(width: 1620, height: 2160)
        let portrait = MirrorProfile.make(screen: screen, landscape: false, hevc: true, scale: 50)
        XCTAssertEqual([portrait.width, portrait.height], [810, 1080])
        let landscape = MirrorProfile.make(screen: screen, landscape: true, hevc: true, scale: 50)
        XCTAssertEqual([landscape.width, landscape.height], [1080, 810])
        XCTAssertTrue(MirrorProfile.choices(screen: screen, landscape: false, hevc: true)
            .contains { $0.width == 810 && $0.height == 1080 })
    }
}
