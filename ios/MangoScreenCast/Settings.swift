import Combine
import Foundation
import Security
import UIKit

/// The app's settings, kept in UserDefaults. Reads are safe from any
/// thread; changes come from the UI on the main thread.
final class Settings: ObservableObject {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The picture size offered unless another is picked, as percent of the
    /// full size: full, the screen's own pixels, on every device.
    static let defaultResolutionScale = 100

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

    /// The picture size offered with the device held upright (portrait) or
    /// sideways (landscape), as percent of full (see MirrorProfile.choices).
    /// Each way has its own: a Mac's display takes the shape the device has
    /// when it connects. `defaultResolutionScale` unless changed.
    func resolutionScale(landscape: Bool) -> Int {
        if let scale = defaults.object(forKey: Keys.scale(landscape)) as? Int { return scale }
        // Earlier versions had one size for both ways, and before that a "1080p" choice.
        if let scale = defaults.object(forKey: Keys.scale) as? Int { return scale }
        return defaults.string(forKey: Keys.oldResolution) == "hd1080" ? 66 : Self.defaultResolutionScale
    }

    func setResolutionScale(_ scale: Int, landscape: Bool) {
        change(Keys.scale(landscape), scale)
    }

    /// The last size kept that way round (or never in question). A
    /// different `resolutionScale` is on trial until it's kept.
    func confirmedResolutionScale(landscape: Bool) -> Int {
        if let scale = defaults.object(forKey: Keys.confirmed(landscape)) as? Int { return scale }
        if let scale = defaults.object(forKey: Keys.confirmedScale) as? Int { return scale }
        return resolutionScale(landscape: landscape)
    }

    func setConfirmedResolutionScale(_ scale: Int, landscape: Bool) {
        change(Keys.confirmed(landscape), scale)
    }

    func resolutionOnTrial(landscape: Bool) -> Bool {
        resolutionScale(landscape: landscape) != confirmedResolutionScale(landscape: landscape)
    }

    var resolutionOnTrial: Bool { resolutionOnTrial(landscape: false) || resolutionOnTrial(landscape: true) }

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

    /// Keep the screen on while waiting (it always stays on while mirroring).
    /// Without running in the background, a locked device also drops out of
    /// Screen Mirroring lists.
    var keepAwake: Bool {
        get { defaults.object(forKey: Keys.awake) as? Bool ?? true }
        set { change(Keys.awake, newValue) }
    }

    /// Keep receiving while the app is in the background or the screen is
    /// locked, so a device mirroring stays connected until the app is closed.
    var runInBackground: Bool {
        get { defaults.object(forKey: Keys.background) as? Bool ?? true }
        set { change(Keys.background, newValue) }
    }

    /// Leaving the app while a device mirrors shows its screen in Picture in
    /// Picture (which also keeps the picture live for coming back).
    var pictureInPicture: Bool {
        get { defaults.object(forKey: Keys.pictureInPicture) as? Bool ?? true }
        set { change(Keys.pictureInPicture, newValue) }
    }

    /// With a Mac to control, taps and swipes on the picture go to the Mac.
    /// Off, they only show the controls, as without one.
    var controlMac: Bool {
        get { defaults.object(forKey: Keys.controlMac) as? Bool ?? true }
        set { change(Keys.controlMac, newValue) }
    }

    /// AirPlay identifies receivers by a MAC-style id. iOS doesn't expose
    /// the real one, so make a random, locally administered one and keep it,
    /// in the keychain, which iOS keeps when the app is deleted: installed
    /// again, Macs see the same device.
    var deviceId: [UInt8] {
        if let saved = KeychainValue.load(Keys.deviceId), saved.count == 6 { return [UInt8](saved) }
        if let saved = defaults.data(forKey: Keys.deviceId), saved.count == 6 {
            KeychainValue.save(saved, Keys.deviceId)
            return [UInt8](saved)
        }
        var bytes = (0..<6).map { _ in UInt8.random(in: 0...255) }
        bytes[0] = (bytes[0] & 0xFC) | 0x02
        KeychainValue.save(Data(bytes), Keys.deviceId)
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
        static let controlMac = "controlMac"
        static let background = "runInBackground"
        static let pictureInPicture = "pictureInPicture"
        static let deviceId = "deviceId"
        static func scale(_ landscape: Bool) -> String { landscape ? "resolutionScaleLandscape" : "resolutionScalePortrait" }
        static func confirmed(_ landscape: Bool) -> String {
            landscape ? "confirmedResolutionScaleLandscape" : "confirmedResolutionScalePortrait"
        }
    }
}

enum Device {
    /// The model, such as "iPad12,1" (in the simulator, the one simulated).
    static let model: String = {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }()
}

/// Small values kept in the keychain (this device only), which outlast the
/// app being deleted and installed again.
enum KeychainValue {
    private static let service = "com.mangoscreencast.device"

    static func load(_ name: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: name, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    static func save(_ data: Data, _ name: String) {
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: name,
        ]
        SecItemDelete(match as CFDictionary)
        var item = match
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
