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
        XCTAssertEqual(size(air, 120), [2360, 1640])
        // Without HEVC the full size is about 1080p, same shape.
        XCTAssertEqual(size(air, hevc: false, 100), [1554, 1080])
    }

    func testEveryChoiceFillsTheScreen() {
        for screen in [air, pro13, classic, mini, CGSize(width: 1179, height: 2556)] {
            for landscape in [true, false] {
                let choices = MirrorProfile.choices(screen: screen, landscape: landscape, hevc: true)
                XCTAssertEqual(choices.first?.scale, 100)
                XCTAssertEqual(choices.map(\.scale), choices.map(\.scale).sorted(by: >))
                for choice in choices {
                    let shape = Double(choice.screenWidth) / Double(choice.screenHeight)
                    let picture = Double(choice.width) / Double(choice.height)
                    XCTAssertLessThan(abs(picture - shape) / shape, 0.001, "\(choice.sizeText) on \(screen)")
                    XCTAssertEqual(choice.width % 2, 0)
                    XCTAssertEqual(choice.height % 2, 0)
                    XCTAssertGreaterThanOrEqual(min(choice.width, choice.height), 480)
                }
            }
        }
        XCTAssertEqual(MirrorProfile.choices(screen: air, landscape: true, hevc: true).count, MirrorProfile.scales.count)
    }

    func testTheFullSizeIsNativeWithHEVC() {
        let choices = MirrorProfile.choices(screen: classic, landscape: true, hevc: true)
        XCTAssertEqual([choices[0].width, choices[0].height], [2160, 1620])
        XCTAssertTrue(choices[0].isNative)
        XCTAssertFalse(choices.dropFirst().contains(where: \.isNative))
        // Without HEVC, full is about 1080p: not the screen's own pixels.
        XCTAssertFalse(MirrorProfile.make(screen: classic, landscape: true, hevc: false).isNative)
        // An odd side, rounded down to even, still counts.
        XCTAssertTrue(MirrorProfile.make(screen: CGSize(width: 1179, height: 2556), landscape: true, hevc: true).isNative)
    }

    func testFitKeepsTheShapeAndEvenSizes() {
        XCTAssertTrue(MirrorProfile.fit(4000, 3000, within: 3840, 2160) == (2880, 2160))
        XCTAssertTrue(MirrorProfile.fit(2361, 1641, within: 3840, 2160) == (2360, 1640))
        XCTAssertTrue(MirrorProfile.fit(0, 0, within: 3840, 2160) == (1920, 1080))
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
