import Combine
import Foundation
import UIKit

/// The app's settings, kept in UserDefaults. Reads are safe from any
/// thread; changes come from the UI on the main thread.
final class Settings: ObservableObject {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    static var defaultName: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "Mango Cast iPad" : "Mango Cast iPhone"
    }

    /// Names earlier versions used as the default; they read as unset, so
    /// those devices take the current default.
    private static let oldDefaultNames: Set<String> = ["Mango iPad", "Mango iPhone"]

    /// What Macs show in Screen Mirroring.
    var receiverName: String {
        get {
            let name = defaults.string(forKey: Keys.name)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return name.isEmpty || Self.oldDefaultNames.contains(name) ? Self.defaultName : name
        }
        set { change(Keys.name, String(newValue.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))) }
    }

    /// The picture size offered, as percent of full (see MirrorProfile.scales).
    /// 100, the screen's own pixels, unless changed.
    var resolutionScale: Int {
        get {
            if let scale = defaults.object(forKey: Keys.scale) as? Int { return scale }
            // Earlier versions had a "1080p" choice; about two thirds is closest.
            return defaults.string(forKey: Keys.oldResolution) == "hd1080" ? 66 : 100
        }
        set { change(Keys.scale, newValue) }
    }

    /// The last picture size that was kept (or never in question). A
    /// different `resolutionScale` is on trial until it's kept.
    var confirmedResolutionScale: Int {
        get { defaults.object(forKey: Keys.confirmedScale) as? Int ?? resolutionScale }
        set { change(Keys.confirmedScale, newValue) }
    }

    var resolutionOnTrial: Bool { resolutionScale != confirmedResolutionScale }

    /// A mirroring session started with a size on trial and hasn't ended.
    /// Still set at launch means the app stopped mid-trial.
    var resolutionTrialInUse: Bool {
        get { defaults.bool(forKey: Keys.trialInUse) }
        set { change(Keys.trialInUse, newValue) }
    }

    /// Zoom the picture to cover the whole screen when its shape differs
    /// from the screen's (a mirrored Mac on an iPad), cutting off the edges.
    var fillScreen: Bool {
        get { defaults.bool(forKey: Keys.fill) }
        set { change(Keys.fill, newValue) }
    }

    /// Play the Mac's sound on this device.
    var soundEnabled: Bool {
        get { defaults.object(forKey: Keys.sound) as? Bool ?? true }
        set { change(Keys.sound, newValue) }
    }

    var showStats: Bool {
        get { defaults.bool(forKey: Keys.stats) }
        set { change(Keys.stats, newValue) }
    }

    /// Keep the screen on while waiting, so the device stays in Screen
    /// Mirroring lists (it disappears when it locks).
    var keepAwake: Bool {
        get { defaults.object(forKey: Keys.awake) as? Bool ?? true }
        set { change(Keys.awake, newValue) }
    }

    /// AirPlay identifies receivers by a MAC-style id. iOS doesn't expose
    /// the real one, so make a random, locally administered one and keep it.
    var deviceId: [UInt8] {
        if let saved = defaults.data(forKey: Keys.deviceId), saved.count == 6 { return [UInt8](saved) }
        var bytes = (0..<6).map { _ in UInt8.random(in: 0...255) }
        bytes[0] = (bytes[0] & 0xFC) | 0x02
        defaults.set(Data(bytes), forKey: Keys.deviceId)
        return bytes
    }

    private func change(_ key: String, _ value: Any) {
        objectWillChange.send()
        defaults.set(value, forKey: key)
    }

    private enum Keys {
        static let name = "receiverName"
        static let oldResolution = "resolution"
        static let scale = "resolutionScale"
        static let confirmedScale = "confirmedResolutionScale"
        static let trialInUse = "resolutionTrialInUse"
        static let sound = "sound"
        static let stats = "showStats"
        static let awake = "keepAwake"
        static let fill = "fillScreen"
        static let deviceId = "deviceId"
    }
}
