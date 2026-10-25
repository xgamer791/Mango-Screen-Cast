import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

/// Plays an iPad's touches on its mirrored picture, and its typing, as this
/// Mac's own pointer and key events, on the display that iPad shows. macOS
/// only allows that for apps turned on under System Settings → Privacy &
/// Security → Accessibility; without it, events are silently dropped.
/// Called on the server's queue.
final class InputInjector {
    static var isAllowed: Bool { CGPreflightPostEventAccess() }

    /// Asks macOS to list the app under Accessibility (with a prompt the
    /// first time).
    static func requestAccess() {
        _ = CGRequestPostEventAccess()
    }

    private let source = CGEventSource(stateID: .hidSystemState)
    private var pressed: CGMouseButton?
    private var scrollRemainder = CGPoint.zero
    private var screen = ShownScreen()

    func handle(_ message: RemoteProtocol.Message) {
        switch message {
        case .hello(let name, _): screen.receiverName = name
        case .picture(let width, let height): screen.pictureSize = CGSize(width: width, height: height)
        case .point(let fx, let fy): point(at(fx, fy))
        case .click(let fx, let fy, let button, let count): click(at(fx, fy), mouse(button), count: count)
        case .press(let fx, let fy, let button, let down, let count): press(at(fx, fy), mouse(button), down: down, count: count)
        case .wheel(let fx, let fy, let fdx, let fdy): wheel(at(fx, fy), fdx: fdx, fdy: fdy)
        case .text(let text): type(text)
        case .key(let code, let modifiers): key(code, modifiers: modifiers)
        default: break
        }
    }

    /// Lets go of anything held when the iPad goes away, so a drag doesn't
    /// stay stuck.
    func releaseAll() {
        if let pressed { post(mouse: pressed, down: false, at: location, clicks: 1) }
        pressed = nil
    }

    // MARK: - Pointer

    private var location: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func mouse(_ button: RemoteProtocol.MouseButton) -> CGMouseButton {
        button == .left ? .left : .right
    }

    /// A share of the picture, as a point on the display it shows.
    private func at(_ fx: Double, _ fy: Double) -> CGPoint {
        let bounds = screen.bounds()
        let x = min(max(fx, 0), 1) * bounds.width
        let y = min(max(fy, 0), 1) * bounds.height
        return CGPoint(x: bounds.minX + min(x, bounds.width - 1), y: bounds.minY + min(y, bounds.height - 1))
    }

    /// Moves the pointer there, dragging if a button is held.
    private func point(_ target: CGPoint) {
        let type: CGEventType
        switch pressed {
        case .left: type = .leftMouseDragged
        case .right: type = .rightMouseDragged
        default: type = .mouseMoved
        }
        let from = location
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: target,
                                  mouseButton: pressed ?? .left) else { return }
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64((target.x - from.x).rounded()))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64((target.y - from.y).rounded()))
        event.post(tap: .cghidEventTap)
    }

    /// One press and release there; `count` 2 or 3 makes it the second or
    /// third click of a double or triple click.
    private func click(_ target: CGPoint, _ button: CGMouseButton, count: Int) {
        if pressed == nil { point(target) }
        let clicks = Int64(min(max(count, 1), 3))
        post(mouse: button, down: true, at: target, clicks: clicks)
        post(mouse: button, down: false, at: target, clicks: clicks)
    }

    private func press(_ target: CGPoint, _ button: CGMouseButton, down: Bool, count: Int) {
        if down {
            if let pressed, pressed != button { post(mouse: pressed, down: false, at: target, clicks: 1) }
            point(target)
            pressed = button
        } else {
            guard pressed == button else { return }
            pressed = nil
        }
        post(mouse: button, down: down, at: target, clicks: Int64(min(max(count, 1), 3)))
    }

    private func post(mouse button: CGMouseButton, down: Bool, at point: CGPoint, clicks: Int64) {
        let type: CGEventType
        switch (button, down) {
        case (.left, true): type = .leftMouseDown
        case (.left, false): type = .leftMouseUp
        case (_, true): type = .rightMouseDown
        case (_, false): type = .rightMouseUp
        }
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point,
                                  mouseButton: button) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: clicks)
        event.post(tap: .cghidEventTap)
    }

    /// Scrolls with the pointer there, as far as the finger went: shares of
    /// the picture, so the page follows the finger at any zoom.
    private func wheel(_ target: CGPoint, fdx: Double, fdy: Double) {
        if pressed == nil && hypot(target.x - location.x, target.y - location.y) > 1 { point(target) }
        let bounds = screen.bounds()
        // A wheel's positive turn scrolls up and left; these scroll down and right.
        scrollRemainder.x -= fdx * bounds.width
        scrollRemainder.y -= fdy * bounds.height
        let x = scrollRemainder.x.rounded(.towardZero)
        let y = scrollRemainder.y.rounded(.towardZero)
        guard x != 0 || y != 0 else { return }
        scrollRemainder.x -= x
        scrollRemainder.y -= y
        CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: Int32(y), wheel2: Int32(x),
                wheel3: 0)?.post(tap: .cghidEventTap)
    }

    // MARK: - Keyboard

    /// Types text as characters, so it comes out right whatever the Mac's
    /// keyboard layout. Return and Tab go as keys, which apps expect.
    private func type(_ text: String) {
        for character in text {
            switch character {
            case "\n", "\r": key(36, modifiers: [])
            case "\t": key(48, modifiers: [])
            default:
                let units = Array(String(character).utf16)
                for down in [true, false] {
                    guard let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                    event.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
                    event.post(tap: .cghidEventTap)
                }
            }
        }
    }

    private func key(_ code: UInt16, modifiers: RemoteProtocol.Modifiers) {
        var flags = CGEventFlags()
        if modifiers.contains(.command) { flags.insert(.maskCommand) }
        if modifiers.contains(.option) { flags.insert(.maskAlternate) }
        if modifiers.contains(.control) { flags.insert(.maskControl) }
        if modifiers.contains(.shift) { flags.insert(.maskShift) }
        // Arrows, F-keys and the editing keys carry these flags on a real keyboard.
        if (115...126).contains(code) || [96, 97, 98, 99, 100, 101, 103, 109, 111].contains(code) {
            flags.insert(.maskSecondaryFn)
            if (123...126).contains(code) { flags.insert(.maskNumericPad) }
        }
        for down in [true, false] {
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else { continue }
            event.flags = flags
            event.post(tap: .cghidEventTap)
        }
    }
}

/// Which of this Mac's displays the iPad is showing, and where it is.
///  - Use As Separate Display: the AirPlay display itself, which macOS names
///    after the receiver ("Mango Cast iPad").
///  - Mirroring: the AirPlay display mirrors another; that one's shown.
///  - Otherwise, the display shaped most like the picture, or the main one.
/// Looked up again every couple of seconds, so arranging displays is followed.
struct ShownScreen {
    var receiverName = "" { didSet { checked = .distantPast } }
    var pictureSize: CGSize? { didSet { checked = .distantPast } }
    private var cached = CGRect(x: 0, y: 0, width: 1440, height: 900)
    private var checked = Date.distantPast

    mutating func bounds() -> CGRect {
        if Date().timeIntervalSince(checked) > 2 {
            let name = receiverName
            let size = pictureSize
            cached = DispatchQueue.main.sync { Self.find(name: name, picture: size) }
            checked = Date()
        }
        return cached
    }

    private static func find(name: String, picture: CGSize?) -> CGRect {
        let shown = NSScreen.screens.compactMap { screen -> (String, CGDirectDisplayID)? in
            guard let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID
            else { return nil }
            return (screen.localizedName, id)
        }
        if !name.isEmpty, let match = shown.first(where: { $0.0 == name }) {
            return CGDisplayBounds(master(of: match.1))
        }
        var count: UInt32 = 0
        CGGetOnlineDisplayList(0, nil, &count)
        var online = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetOnlineDisplayList(count, &online, &count)
        // Mirrored displays aren't screens of their own: the one they mirror is.
        let mirrored = online.prefix(Int(count)).filter { CGDisplayMirrorsDisplay($0) != kCGNullDirectDisplay }
        let masters = mirrored.map { master(of: $0) }
        let candidates = masters.isEmpty ? shown.map(\.1) : masters
        if let picture, picture.width > 0, picture.height > 0, candidates.count > 1 {
            let aspect = picture.width / picture.height
            if let best = candidates.min(by: { shapeDifference($0, aspect) < shapeDifference($1, aspect) }) {
                return CGDisplayBounds(best)
            }
        }
        return CGDisplayBounds(candidates.first ?? CGMainDisplayID())
    }

    private static func master(of display: CGDirectDisplayID) -> CGDirectDisplayID {
        let master = CGDisplayMirrorsDisplay(display)
        return master == kCGNullDirectDisplay ? display : master
    }

    private static func shapeDifference(_ display: CGDirectDisplayID, _ aspect: CGFloat) -> CGFloat {
        let bounds = CGDisplayBounds(display)
        return bounds.height > 0 ? abs(bounds.width / bounds.height - aspect) : .infinity
    }
}
