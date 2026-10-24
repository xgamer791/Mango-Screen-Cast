import XCTest

final class ReleaseFeedTests: XCTestCase {
    private let source = Data("""
    {
      "name": "Mango Screen Cast",
      "apps": [{
        "name": "Mango Cast",
        "bundleIdentifier": "com.mangoscreencast.ios",
        "versions": [{
          "version": "1.0.12",
          "buildVersion": "12",
          "date": "2026-10-25T01:02:03Z",
          "downloadURL": "https://github.com/xgamer791/Mango-Screen-Cast/releases/download/ios-latest/MangoCast-1.0.12.ipa",
          "size": 2649914,
          "localizedDescription": "Fill the iPad screen"
        }]
      }]
    }
    """.utf8)

    func testReadsTheNewestVersion() throws {
        let release = try XCTUnwrap(ReleaseFeed.latest(in: source, bundleIdentifier: "com.mangoscreencast.ios"))
        XCTAssertEqual(release.version, "1.0.12")
        XCTAssertEqual(release.build, "12")
        XCTAssertEqual(release.notes, "Fill the iPad screen")
        XCTAssertEqual(release.downloadURL.lastPathComponent, "MangoCast-1.0.12.ipa")
        XCTAssertEqual(release.date, ISO8601DateFormatter().date(from: "2026-10-25T01:02:03Z"))
    }

    func testFallsBackToTheFirstAppWhenTheBundleIdWasChanged() throws {
        // AltStore can add the signer's team id to the bundle id.
        let release = try ReleaseFeed.latest(in: source, bundleIdentifier: "com.mangoscreencast.ios.ABCDE12345")
        XCTAssertEqual(release?.build, "12")
    }

    func testComparesBuildsThenVersions() throws {
        let release = try XCTUnwrap(ReleaseFeed.latest(in: source, bundleIdentifier: nil))
        XCTAssertTrue(ReleaseFeed.isNewer(release, thanVersion: "1.0", build: "1"))
        XCTAssertTrue(ReleaseFeed.isNewer(release, thanVersion: "1.0.9", build: "9"))
        XCTAssertFalse(ReleaseFeed.isNewer(release, thanVersion: "1.0.12", build: "12"))
        XCTAssertFalse(ReleaseFeed.isNewer(release, thanVersion: "1.0.13", build: "13"))
        // Numeric, not alphabetical: 1.0.12 is newer than 1.0.9.
        XCTAssertTrue(ReleaseFeed.isNewer(release, thanVersion: "1.0.9", build: "local"))
    }

    func testOpensTheSourceInAltStore() {
        XCTAssertEqual(ReleaseFeed.sourceLink(scheme: "altstore").absoluteString,
                       "altstore://source?url=https://github.com/xgamer791/Mango-Screen-Cast/releases/download/ios-latest/altstore-source.json")
    }
}
