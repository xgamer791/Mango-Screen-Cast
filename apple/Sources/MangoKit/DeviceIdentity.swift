import Foundation
import MangoProtocol
#if canImport(UIKit)
import UIKit
#endif

/// Who this device is on the network.
public enum DeviceIdentity {
    /// Apps that share state with an extension point this at their app group.
    public static var defaults: UserDefaults = .standard

    public static var id: String {
        if let id = defaults.string(forKey: "mango.deviceId") { return id }
        let id = UUID().uuidString
        defaults.set(id, forKey: "mango.deviceId")
        return id
    }

    public static var name: String {
        #if os(macOS)
        return Host.current().localizedName ?? "Mac"
        #else
        return UIDevice.current.name
        #endif
    }

    public static var os: String {
        #if os(macOS)
        return "macos"
        #else
        return "ios"
        #endif
    }

    public static var model: String {
        #if os(macOS)
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        var buffer = [CChar](repeating: 0, count: max(size, 1))
        sysctlbyname("hw.model", &buffer, &size, nil, 0)
        return String(cString: buffer)
        #else
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        #endif
    }

    public static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    public static func hello(role: String) -> Hello {
        Hello(role: role, id: id, name: name, os: os, model: model, app: appVersion)
    }
}

public enum MonotonicClock {
    /// Microseconds since boot, never goes backwards.
    public static var micros: UInt64 { DispatchTime.now().uptimeNanoseconds / 1000 }
}
