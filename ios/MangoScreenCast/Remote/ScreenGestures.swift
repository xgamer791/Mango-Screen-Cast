import CoreGraphics
import Foundation

/// The touch controls on the mirrored picture, as Holli Bot's Screen has
/// them (src/ui/computer.js useZoom there): the math, kept apart from UIKit
/// so it can be tested.
///
///  - A pinch zooms the picture, up to 6×, about the fingers; under 1.05× it
///    goes back to the whole screen.
///  - Zoomed in, a finger moves the picture around.
///  - Not zoomed in, a finger scrolls the Mac where it is, up and down or
///    sideways (the way it went most), the page following the finger; let go
///    of while moving, it carries on a moment, slowing to a stop.
///  - A tap clicks there (and not the tap that ends a pinch, drag or swipe).
///  - A trackpad's pinch zooms too; zoomed in, a mouse drags the picture
///    around, or, working the Mac, moves it along near the frame's edges.
enum ScreenGestures {
    static let maxZoom: CGFloat = 6
    /// How far a finger goes before it's moving, not tapping (points).
    static let slop: CGFloat = 6
    /// After a pinch, a drag or a swipe, a tap this soon isn't a click (s).
    static let quietTime: TimeInterval = 0.4
    /// A swipe carries on at this speed or more (shares of the picture a ms),
    /// losing this share of its speed each ms.
    static let flingMin = 0.0004
    static let flingFall = 0.004
    /// Held still this long before it's let go: no carrying on (ms).
    static let flingHeld = 80.0
    /// Taps this close together, in time and place, are a double click.
    static let doubleTapTime: TimeInterval = 0.45
    static let doubleTapDistance: CGFloat = 24

    /// Zoomed in, the picture moves along when a mouse's pointer comes this
    /// close to the frame's edge (points), at up to this many frames a second.
    static let edgeReach: CGFloat = 56
    static let edgeSpeed: CGFloat = 1.2

    static func fitZoom(_ z: CGFloat) -> CGFloat { min(maxZoom, max(1, z)) }

    /// How the picture moves along with the pointer at `point` in a frame of
    /// `size` (−1 to 1 each way, harder the nearer the edge): toward the
    /// edge it's near, showing what's past it. Zero away from the edges.
    static func edgePush(at point: CGPoint, in size: CGSize) -> CGVector {
        let reach = min(edgeReach, size.width / 5, size.height / 5)
        guard reach > 0 else { return .zero }
        func push(_ gap: CGFloat) -> CGFloat { gap < reach ? 1 - max(0, gap) / reach : 0 }
        return CGVector(dx: push(point.x) - push(size.width - point.x), dy: push(point.y) - push(size.height - point.y))
    }

    /// The picture's place in its frame: zoom `z`, and where its top-left
    /// corner is, as shares of the frame's width and height (1 − z to 0).
    struct View: Equatable {
        var z: CGFloat = 1
        var x: CGFloat = 0
        var y: CGFloat = 0

        static let whole = View()

        var zoomedIn: Bool { z > 1.001 }

        /// Zoomed `z`, with the corner at (x, y), kept inside the frame.
        static func place(_ z: CGFloat, _ x: CGFloat, _ y: CGFloat) -> View {
            View(z: z, x: min(0, max(1 - z, x)), y: min(0, max(1 - z, y)))
        }

        /// Zoomed to `z`, keeping the point at (fx, fy) of the frame where it is.
        func zoomed(to z: CGFloat, at fx: CGFloat, _ fy: CGFloat) -> View {
            let to = fitZoom(z)
            return .place(to, fx - ((fx - x) / self.z) * to, fy - ((fy - y) / self.z) * to)
        }

        /// A pinch from `start` (fingers' middle at (sx, sy), `startDistance`
        /// apart) to now (middle at (x, y), `distance` apart): zoomed, and
        /// moved with the fingers.
        static func pinched(from start: View, startX sx: CGFloat, startY sy: CGFloat, startDistance: CGFloat,
                            toX x: CGFloat, y: CGFloat, distance: CGFloat) -> View {
            let z = fitZoom(start.z * (startDistance > 0 ? distance / startDistance : 1))
            return .place(z, x - ((sx - start.x) / start.z) * z, y - ((sy - start.y) / start.z) * z)
        }

        /// Moved by (dx, dy), shares of the frame.
        func panned(_ dx: CGFloat, _ dy: CGFloat) -> View {
            .place(z, x + dx, y + dy)
        }

        /// Where the gesture ends: back to the whole screen when barely zoomed.
        var settled: View { z < 1.05 ? .whole : self }

        /// The picture's rectangle in a frame of `size`.
        func rect(in size: CGSize) -> CGRect {
            CGRect(x: x * size.width, y: y * size.height, width: z * size.width, height: z * size.height)
        }
    }

    enum Axis { case horizontal, vertical }

    /// One finger scrolling the Mac: the way it goes, how fast, and where.
    struct Swipe {
        var axis: Axis?
        /// Speed in shares of the picture a ms, smoothed.
        var vx = 0.0
        var vy = 0.0
        var lastTime: Double?

        /// The finger moved from `last` to `now` (points, start `origin`) on
        /// a picture `size` big, at `time` (ms): how far the wheel turns, in
        /// shares of the picture (positive scrolls down and right).
        mutating func step(origin: CGPoint, last: CGPoint, now: CGPoint, size: CGSize, time: Double) -> (dx: Double, dy: Double) {
            if axis == nil {
                axis = abs(now.x - origin.x) >= abs(now.y - origin.y) ? .horizontal : .vertical
            }
            // The page follows the finger: a finger going up scrolls down.
            let dx = axis == .horizontal && size.width > 0 ? -Double((now.x - last.x) / size.width) : 0
            let dy = axis == .vertical && size.height > 0 ? -Double((now.y - last.y) / size.height) : 0
            let dt = max(1, time - (lastTime ?? (time - 16)))
            vx = vx * 0.5 + (dx / dt) * 0.5
            vy = vy * 0.5 + (dy / dt) * 0.5
            lastTime = time
            return (dx, dy)
        }

        /// Let go of at `time` (ms): whether it carries on.
        func flings(at time: Double) -> Bool {
            guard let lastTime, time - lastTime <= flingHeld else { return false }
            return hypot(vx, vy) >= flingMin
        }

        /// Carrying on for `dt` ms: how far the wheel turns, and slowing.
        /// Nil once it's stopped.
        mutating func fling(_ dt: Double) -> (dx: Double, dy: Double)? {
            let keep = exp(-flingFall * dt)
            vx *= keep
            vy *= keep
            guard hypot(vx, vy) >= flingMin else { return nil }
            return (vx * dt, vy * dt)
        }
    }
}
