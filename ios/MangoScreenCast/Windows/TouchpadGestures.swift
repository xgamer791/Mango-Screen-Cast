import CoreGraphics
import Foundation

/// How touches on the touchpad become the PC's pointer and wheel.
enum TouchpadGestures {
    /// Travel this short (points) still counts as a tap.
    static let tapSlop: CGFloat = 10
    /// A tap is quicker than this (seconds).
    static let tapTime: TimeInterval = 0.3
    /// Held this long without moving, a finger presses the button (dragging).
    static let holdTime: TimeInterval = 0.45

    /// Pointer pixels (of a 1080-line screen) per point of finger travel at
    /// this speed (points a second): fine slowly, far quickly.
    static func gain(speed: CGFloat) -> CGFloat {
        1.4 + 2.6 * min(1, max(0, (speed - 100) / 1400))
    }

    /// What lifting the last finger means.
    enum Ending: Equatable { case nothing, click, rightClick, endDrag }

    static func ending(fingers: Int, duration: TimeInterval, travel: CGFloat, dragging: Bool) -> Ending {
        if dragging { return .endDrag }
        guard duration < tapTime, travel < tapSlop else { return .nothing }
        switch fingers {
        case 1: return .click
        case 2: return .rightClick
        default: return .nothing
        }
    }
}
