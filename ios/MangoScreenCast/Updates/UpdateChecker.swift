import Combine
import UIKit

/// Looks for newer builds while the app is open: at launch and every few
/// hours after.
///
/// iOS only lets the App Store install app updates by itself, so this
/// can't update the app on its own. It says when a new version is out, and
/// opens AltStore or SideStore, which install it with a tap once this
/// project's source is added there.
final class UpdateChecker: ObservableObject {
    enum State: Equatable {
        case idle
        case checking
        case upToDate
        case available(ReleaseFeed.Release)
        case failed(String)
    }

    @Published private(set) var state = State.idle
    @Published private(set) var lastChecked: Date?

    let version: String
    let build: String
    /// CI builds say "release". A build made in Xcode says "local": it can't
    /// tell how it compares to published builds, so it shows no banner.
    let isReleaseBuild: Bool

    private var timer: Timer?
    private static let interval: TimeInterval = 6 * 60 * 60

    init(bundle: Bundle = .main) {
        let info = bundle.infoDictionary ?? [:]
        version = info["CFBundleShortVersionString"] as? String ?? "0"
        build = info["CFBundleVersion"] as? String ?? "0"
        isReleaseBuild = info["MangoBuildChannel"] as? String == "release"
    }

    var availableUpdate: ReleaseFeed.Release? {
        if case .available(let release) = state { return release }
        return nil
    }

    /// Checks now (unless it did a few minutes ago), then every few hours
    /// while the app stays open.
    func startChecking() {
        if let lastChecked, Date().timeIntervalSince(lastChecked) < 600 {
            // Just checked; coming back from Control Center shouldn't refetch.
        } else {
            check()
        }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Self.interval, repeats: true) { [weak self] _ in
            self?.check()
        }
    }

    func stopChecking() {
        timer?.invalidate()
        timer = nil
    }

    func check() {
        if state == .checking { return }
        state = .checking
        let request = URLRequest(url: ReleaseFeed.sourceURL, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: 20)
        let version = self.version
        let build = self.build
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            let next: State
            if let error {
                next = .failed(error.localizedDescription)
            } else if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                next = .failed("the release feed answered \(http.statusCode)")
            } else if let data,
                      let release = try? ReleaseFeed.latest(in: data, bundleIdentifier: Bundle.main.bundleIdentifier) {
                next = ReleaseFeed.isNewer(release, thanVersion: version, build: build) ? .available(release) : .upToDate
            } else {
                next = .failed("couldn’t read the release feed")
            }
            DispatchQueue.main.async {
                self?.state = next
                self?.lastChecked = Date()
            }
        }.resume()
    }

    /// Opens AltStore, or else SideStore, on this app's source, where the
    /// update installs with a tap. With neither, opens the download page.
    func openUpdate() {
        open([ReleaseFeed.sourceLink(scheme: "altstore"), ReleaseFeed.sourceLink(scheme: "sidestore"),
              ReleaseFeed.releasePage])
    }

    private func open(_ urls: [URL]) {
        guard let url = urls.first else { return }
        UIApplication.shared.open(url) { [weak self] opened in
            if !opened { self?.open(Array(urls.dropFirst())) }
        }
    }
}
