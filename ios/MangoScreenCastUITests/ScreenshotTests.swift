import XCTest

/// Not a behaviour test: takes screenshots of the home and Settings screens
/// (sideways, the only way the app runs), so CI can show how the layout
/// looks on every simulated iPad and iPhone (the Screenshots scheme; see
/// ios.yml).
///
/// With SCREENSHOT_DIR set (TEST_RUNNER_SCREENSHOT_DIR for xcodebuild), the
/// pictures are also written there as "<device> - <name>.png".
final class ScreenshotTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    func testScreens() {
        let app = XCUIApplication()
        XCUIDevice.shared.orientation = .landscapeLeft
        app.launch()
        _ = app.staticTexts["Ready to mirror"].waitForExistence(timeout: 20)
        settle()
        snap("1 home")

        app.buttons["Settings"].firstMatch.tap()
        settle()
        snap("2 settings")
    }

    private func settle() {
        // Rotation and sheet animations, and the server restarting with the new shape.
        Thread.sleep(forTimeInterval: 2.5)
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
        let url = URL(fileURLWithPath: directory).appendingPathComponent("\(device) - \(name).png")
        try? screenshot.pngRepresentation.write(to: url)
    }
}
