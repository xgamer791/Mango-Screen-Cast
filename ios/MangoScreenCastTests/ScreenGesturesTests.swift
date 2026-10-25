import CoreGraphics
import XCTest

final class ScreenGesturesTests: XCTestCase {
    typealias View = ScreenGestures.View

    func testPlaceKeepsThePictureInsideItsFrame() {
        XCTAssertEqual(View.place(2, 0.3, -1.5), View(z: 2, x: 0, y: -1))
        XCTAssertEqual(View.place(1, -0.2, 0.2), View.whole)
    }

    func testZoomingKeepsThePointUnderTheFingers() {
        // Zoom 2× about the middle: the middle stays in the middle.
        let zoomed = View.whole.zoomed(to: 2, at: 0.5, 0.5)
        XCTAssertEqual(zoomed, View(z: 2, x: -0.5, y: -0.5))
        // About the top-left corner: the corner stays put.
        XCTAssertEqual(View.whole.zoomed(to: 3, at: 0, 0), View(z: 3, x: 0, y: 0))
        // Never past 6×, nor under 1×.
        XCTAssertEqual(View.whole.zoomed(to: 20, at: 0.5, 0.5).z, 6)
        XCTAssertEqual(zoomed.zoomed(to: 0.2, at: 0.5, 0.5), View.whole)
    }

    func testPinchZoomsAndMovesWithTheFingers() {
        // Fingers 100 apart → 200 apart, staying in the middle: 2×, centred.
        let pinched = View.pinched(from: .whole, startX: 0.5, startY: 0.5, startDistance: 100,
                                   toX: 0.5, y: 0.5, distance: 200)
        XCTAssertEqual(pinched, View(z: 2, x: -0.5, y: -0.5))
        // Same pinch, fingers moving right by a tenth: the picture goes with them.
        let moved = View.pinched(from: .whole, startX: 0.5, startY: 0.5, startDistance: 100,
                                 toX: 0.6, y: 0.5, distance: 200)
        XCTAssertEqual(moved.x, -0.4, accuracy: 1e-9)
    }

    func testBarelyZoomedGoesBackToTheWholeScreen() {
        XCTAssertEqual(View(z: 1.04, x: -0.02, y: 0).settled, .whole)
        XCTAssertEqual(View(z: 1.5, x: -0.2, y: 0).settled, View(z: 1.5, x: -0.2, y: 0))
        XCTAssertFalse(View.whole.zoomedIn)
        XCTAssertTrue(View(z: 1.5, x: 0, y: 0).zoomedIn)
    }

    func testPanningAndTheRectangle() {
        let view = View(z: 2, x: -0.5, y: -0.5).panned(0.2, -0.7)
        XCTAssertEqual(view, View(z: 2, x: -0.3, y: -1))
        XCTAssertEqual(view.rect(in: CGSize(width: 1000, height: 800)),
                       CGRect(x: -300, y: -800, width: 2000, height: 1600))
    }

    func testSwipeLocksToTheWayItWentMostAndThePageFollowsTheFinger() {
        var swipe = ScreenGestures.Swipe()
        let size = CGSize(width: 1000, height: 500)
        let origin = CGPoint(x: 500, y: 400)
        // Mostly upward: vertical, and a finger going up scrolls down (positive).
        let first = swipe.step(origin: origin, last: origin, now: CGPoint(x: 503, y: 350), size: size, time: 1000)
        XCTAssertEqual(swipe.axis, .vertical)
        XCTAssertEqual(first.dx, 0)
        XCTAssertEqual(first.dy, 0.1, accuracy: 1e-9)
        // Sideways drift afterwards doesn't scroll sideways.
        let second = swipe.step(origin: origin, last: CGPoint(x: 503, y: 350), now: CGPoint(x: 560, y: 300),
                                size: size, time: 1016)
        XCTAssertEqual(second.dx, 0)
        XCTAssertEqual(second.dy, 0.1, accuracy: 1e-9)
    }

    func testFlingCarriesOnAndSlowsToAStop() {
        var swipe = ScreenGestures.Swipe()
        let size = CGSize(width: 1000, height: 1000)
        var y: CGFloat = 900
        var time = 0.0
        for _ in 0..<6 {
            _ = swipe.step(origin: CGPoint(x: 500, y: 900), last: CGPoint(x: 500, y: y),
                           now: CGPoint(x: 500, y: y - 40), size: size, time: time)
            y -= 40
            time += 16
        }
        XCTAssertTrue(swipe.flings(at: time))
        // Held still before letting go: no carrying on.
        XCTAssertFalse(swipe.flings(at: time + 200))
        var total = 0.0
        var steps = 0
        while let step = swipe.fling(16) {
            XCTAssertGreaterThan(step.dy, 0)
            total += step.dy
            steps += 1
            XCTAssertLessThan(steps, 1000)
        }
        XCTAssertGreaterThan(total, 0.1)
        XCTAssertGreaterThan(steps, 5)
    }
}
