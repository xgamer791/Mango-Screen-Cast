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
        UIDevice.current.userInterfaceIdiom == .pad ? "Mango iPad" : "Mango iPhone"
    }

    /// What Macs show in Screen Mirroring.
    var receiverName: String {
        get {
            let name = defaults.string(forKey: Keys.name)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return name.isEmpty ? Self.defaultName : name
        }
        set { change(Keys.name, String(newValue.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))) }
    }

    var resolution: ResolutionSetting {
        get { defaults.string(forKey: Keys.resolution).flatMap(ResolutionSetting.init) ?? .native }
        set { change(Keys.resolution, newValue.rawValue) }
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
        static let resolution = "resolution"
        static let sound = "sound"
        static let stats = "showStats"
        static let awake = "keepAwake"
        static let deviceId = "deviceId"
    }
}
