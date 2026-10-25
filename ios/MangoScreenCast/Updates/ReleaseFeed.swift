import Foundation

/// Where new builds of the app are announced: an AltStore source (the same
/// file AltStore and SideStore read), which CI publishes with every build
/// next to the IPA in the repository's `ios-latest` pre-release.
enum ReleaseFeed {
    static let repository = "xgamer791/Mango-Screen-Cast"
    static let sourceURL = URL(string: "https://github.com/\(repository)/releases/download/ios-latest/altstore-source.json")!
    static let releasePage = URL(string: "https://github.com/\(repository)/releases/tag/ios-latest")!
    /// Mango Cast Remote for the Mac, published with every build.
    static let macRemoteDownload = URL(string: "https://github.com/\(repository)/releases/download/ios-latest/MangoCastRemote-mac.zip")!

    struct Release: Equatable {
        let version: String
        let build: String
        let date: Date?
        let downloadURL: URL
        let notes: String?
    }

    private struct Source: Decodable {
        let apps: [App]
    }

    private struct App: Decodable {
        let bundleIdentifier: String
        let versions: [Version]
    }

    private struct Version: Decodable {
        let version: String
        let buildVersion: String?
        let date: String?
        let downloadURL: URL
        let localizedDescription: String?
    }

    /// The newest release of this app in a source. Matched by bundle id, else
    /// the first app: AltStore can change the bundle id when it signs.
    static func latest(in data: Data, bundleIdentifier: String?) throws -> Release? {
        let source = try JSONDecoder().decode(Source.self, from: data)
        let app = source.apps.first { $0.bundleIdentifier == bundleIdentifier } ?? source.apps.first
        guard let newest = app?.versions.first else { return nil }
        return Release(version: newest.version, build: newest.buildVersion ?? newest.version,
                       date: newest.date.flatMap { ISO8601DateFormatter().date(from: $0) },
                       downloadURL: newest.downloadURL, notes: newest.localizedDescription)
    }

    /// Whether `release` is newer than the running build: by build number,
    /// or by version when either build isn't a number.
    static func isNewer(_ release: Release, thanVersion version: String, build: String) -> Bool {
        if let releaseBuild = Int(release.build), let ownBuild = Int(build) { return releaseBuild > ownBuild }
        return release.version.compare(version, options: .numeric) == .orderedDescending
    }

    /// Opens the source in AltStore or SideStore, where the app's updates
    /// show up (adding the source if it isn't there yet).
    static func sourceLink(scheme: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "source"
        components.queryItems = [URLQueryItem(name: "url", value: sourceURL.absoluteString)]
        return components.url!
    }
}
