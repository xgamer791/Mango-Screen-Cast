import XCTest

/// The touch controls on the mirrored picture, as Holli Bot's Screen has
/// them, driven for real on the simulator: the app runs its controls demo
/// (-MangoControlsDemo, debug builds only), which shows a stand-in Mac screen
/// and lists what would go to the Mac instead of sending it.
final class ControlsUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
        app.launchArguments = ["-MangoControlsDemo"]
        app.launch()
        XCTAssertTrue(screen.waitForExistence(timeout: 20))
        // The picture's size goes to the Mac first.
        waitForLog("the picture's size") { $0.contains { $0.hasPrefix("picture ") } }
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    private var screen: XCUIElement { app.otherElements["mirroredScreen"] }
    private var zoomBadge: XCUIElement { app.buttons["zoomBadge"] }

    /// The demo's log, one message a line, oldest first.
    private var log: [String] {
        let element = app.staticTexts["controlLog"]
        guard element.exists else { return [] }
        return element.label.split(separator: "\n").map(String.init)
    }

    @discardableResult
    private func waitForLog(_ what: String, timeout: TimeInterval = 5, _ check: ([String]) -> Bool) -> [String] {
        let deadline = Date().addingTimeInterval(timeout)
        var lines = log
        while !check(lines) && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            lines = log
        }
        XCTAssertTrue(check(lines), "expected \(what), log:\n\(lines.joined(separator: "\n"))")
        return lines
    }

    private func point(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate {
        screen.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
    }

    /// The wheel lines sent after `before` lines were already in the log.
    private func wheels(in lines: [String], after before: [String]) -> [(dx: Double, dy: Double)] {
        lines.dropFirst(max(0, lines.count - newLines(lines, after: before)))
            .filter { $0.hasPrefix("wheel ") }
            .compactMap { line in
                let parts = line.split(separator: " ")
                guard parts.count == 5, let dx = Double(parts[3]), let dy = Double(parts[4]) else { return nil }
                return (dx, dy)
            }
    }

    /// How many lines at the end of `lines` came after `before` (the log keeps its last 12).
    private func newLines(_ lines: [String], after before: [String]) -> Int {
        for overlap in stride(from: min(before.count, lines.count), through: 0, by: -1)
        where Array(before.suffix(overlap)) == Array(lines.prefix(overlap)) {
            return lines.count - overlap
        }
        return lines.count
    }

    func testATapClicksThere() {
        point(0.5, 0.5).tap()
        waitForLog("a click in the middle") { $0.last == "click 0.50 0.50 left 1" }
        point(0.25, 0.75).tap()
        waitForLog("a click at a quarter across, three quarters down") { lines in
            guard let last = lines.last, last.hasPrefix("click ") else { return false }
            let parts = last.split(separator: " ")
            // The picture keeps its shape inside the screen, so allow for the bars around it.
            guard let x = Double(parts[1]), let y = Double(parts[2]) else { return false }
            return x < 0.5 && y > 0.5 && parts[3] == "left" && parts[4] == "1"
        }
    }

    func testADoubleTapDoubleClicks() {
        point(0.5, 0.5).doubleTap()
        waitForLog("a double click") { lines in
            lines.suffix(2) == ["click 0.50 0.50 left 1", "click 0.50 0.50 left 2"]
        }
    }

    func testSwipingScrollsTheMacWithThePage() {
        var before = log
        point(0.5, 0.75).press(forDuration: 0.05, thenDragTo: point(0.5, 0.25), withVelocity: .fast,
                               thenHoldForDuration: 0)
        var lines = waitForLog("wheel turns down") { lines in
            !wheels(in: lines, after: before).isEmpty
        }
        // A finger going up scrolls down, as on a phone; only up and down.
        var turns = wheels(in: lines, after: before)
        XCTAssertTrue(turns.allSatisfy { $0.dy > 0 && $0.dx == 0 }, "\(turns)")
        XCTAssertGreaterThan(turns.reduce(0) { $0 + $1.dy }, 0.2)
        XCTAssertTrue(lines.allSatisfy { !$0.hasPrefix("click") }, "a swipe isn't a click")

        // Let the fling stop, then sideways: a finger going left scrolls right.
        Thread.sleep(forTimeInterval: 1.5)
        before = log
        point(0.75, 0.5).press(forDuration: 0.05, thenDragTo: point(0.25, 0.5), withVelocity: .fast,
                               thenHoldForDuration: 0)
        lines = waitForLog("wheel turns right") { lines in
            !wheels(in: lines, after: before).isEmpty
        }
        turns = wheels(in: lines, after: before)
        XCTAssertTrue(turns.allSatisfy { $0.dx > 0 && $0.dy == 0 }, "\(turns)")
    }

    /// Where the last line's click was on the picture, if it's a click.
    private func click(_ line: String?) -> (x: Double, y: Double)? {
        guard let parts = line?.split(separator: " "), parts.count == 5, parts[0] == "click",
              let x = Double(parts[1]), let y = Double(parts[2]) else { return nil }
        return (x, y)
    }

    func testPinchingZoomsAndFingersMoveAroundAndATapClicksWhatsUnderIt() throws {
        XCTAssertFalse(zoomBadge.exists)
        screen.pinch(withScale: 3, velocity: 2)
        XCTAssertTrue(zoomBadge.waitForExistence(timeout: 5), "zoomed in, the badge shows how far")
        snap("5 controls zoomed")

        // Zoomed in about the middle, a tap right of the middle clicks only a
        // little right of the picture's middle: what's under the finger.
        Thread.sleep(forTimeInterval: 0.6)
        point(0.6, 0.5).tap()
        let lines = waitForLog("a click, zoomed in") { click($0.last).map { $0.x > 0.5 && $0.x < 0.6 } ?? false }
        let first = click(lines.last)!
        XCTAssertEqual(first.y, 0.5, accuracy: 0.05)

        // Zoomed in, a finger moves the picture around (and doesn't turn the Mac's wheel)...
        let before = log
        point(0.4, 0.5).press(forDuration: 0.05, thenDragTo: point(0.6, 0.5), withVelocity: .slow,
                              thenHoldForDuration: 0.2)
        Thread.sleep(forTimeInterval: 0.6)
        XCTAssertTrue(wheels(in: log, after: before).isEmpty, "log:\n\(log.joined(separator: "\n"))")
        XCTAssertEqual(log, before, "moving the picture sends nothing to the Mac")

        // ...so the same spot on the screen is now further left on the picture.
        point(0.6, 0.5).tap()
        let moved = waitForLog("a click after moving the picture") { $0 != before }
        let second = try XCTUnwrap(click(moved.last))
        XCTAssertLessThan(second.x, first.x - 0.02, "the picture moved right with the finger")
        XCTAssertEqual(second.y, first.y, accuracy: 0.02)

        // Up and down too.
        Thread.sleep(forTimeInterval: 0.6)
        point(0.6, 0.4).press(forDuration: 0.05, thenDragTo: point(0.6, 0.6), withVelocity: .slow,
                              thenHoldForDuration: 0.2)
        Thread.sleep(forTimeInterval: 0.6)
        let beforeTap = log
        point(0.6, 0.5).tap()
        let third = try XCTUnwrap(click(waitForLog("a click after moving it down") { $0 != beforeTap }.last))
        XCTAssertLessThan(third.y, second.y - 0.02, "the picture moved down with the finger")

        // The badge goes back to the whole screen, where the middle is the middle.
        zoomBadge.tap()
        XCTAssertTrue(zoomBadge.waitForNonExistence(timeout: 5), "the badge goes back to the whole screen")
        point(0.5, 0.5).tap()
        waitForLog("a click in the middle, zoomed out") { $0.last == "click 0.50 0.50 left 1" }
    }

    func testTheKeyboardTypesOnTheMac() {
        let keyboardButton = app.buttons["keyboardButton"]
        XCTAssertTrue(keyboardButton.waitForExistence(timeout: 5))
        keyboardButton.tap()
        let box = app.textFields["typingBox"]
        XCTAssertTrue(box.waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 1)

        box.typeText("hi")
        waitForLog("typing \"hi\"") { lines in
            lines.filter { $0.hasPrefix("text ") }.map { String($0.dropFirst(5)) }.joined() == "hi"
        }
        snap("6 controls keyboard")

        box.typeText(XCUIKeyboardKey.delete.rawValue)
        waitForLog("Backspace") { $0.last == "key 51" }

        box.typeText("\n")
        waitForLog("Return") { $0.last == "key 36" }

        // The box starts afresh after Return; Backspace still reaches the Mac.
        box.typeText(XCUIKeyboardKey.delete.rawValue)
        waitForLog("Backspace on an empty box") { $0.suffix(2) == ["key 36", "key 51"] }

        keyboardButton.tap()
    }

    /// ⋯ pops up nothing for now: no bar saying what's mirroring, no stats,
    /// no Controlling, Fit or Stop. Nothing shows as the picture opens either.
    func testMorePopsUpNothing() {
        let more = app.buttons["moreButton"]
        XCTAssertTrue(more.waitForExistence(timeout: 5))
        let bar = app.descendants(matching: .any)["controlsBar"]
        let mirroring = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Mirroring'")).firstMatch
        let stats = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'fps' OR label CONTAINS 'recoveries'")).firstMatch
        XCTAssertFalse(bar.exists, "no bar as the picture opens")
        XCTAssertFalse(mirroring.exists, "nothing says what's mirroring")
        more.tap()
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(bar.exists, "⋯ shows no bar")
        XCTAssertFalse(mirroring.exists, "⋯ shows nothing saying what's mirroring")
        XCTAssertFalse(stats.exists, "⋯ shows no stats")
        for name in ["controlButton", "Controlling", "Control Mac", "Fit", "Fill", "Stop"] {
            XCTAssertFalse(app.buttons[name].exists, "no \(name) button")
        }
        snap("7 controls more")
    }

    private func snap(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let environment = ProcessInfo.processInfo.environment
        guard let directory = environment["SCREENSHOT_DIR"] else { return }
        let device = environment["SIMULATOR_DEVICE_NAME"] ?? "Device"
        try? screenshot.pngRepresentation.write(
            to: URL(fileURLWithPath: directory).appendingPathComponent("\(device) - \(name).png"))
    }
}
