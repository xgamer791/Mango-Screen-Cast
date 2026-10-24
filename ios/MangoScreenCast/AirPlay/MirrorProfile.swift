import CoreGraphics

enum ResolutionSetting: String, CaseIterable, Identifiable {
    /// The screen's own pixels, e.g. 2360 × 1640 on an iPad Air.
    case native
    /// 1920 × 1080, which every sender can encode.
    case hd1080

    var id: String { rawValue }
}

/// What this device tells Macs, iPhones and iPads it can show. They size
/// their stream to it; a Mac's Use As Separate Display creates a display of
/// exactly this size.
struct MirrorProfile: Equatable {
    var width: Int
    var height: Int
    var refreshRate = 60
    var maxFps = 60
    /// Offer HEVC. Above 1080p, Apple devices only send HEVC.
    var h265: Bool
    /// The screen in landscape pixels.
    var screenWidth: Int
    var screenHeight: Int

    var codecs: String { h265 ? "HEVC + H.264" : "H.264" }

    /// - Parameters:
    ///   - screen: the screen's size in pixels, either orientation.
    ///   - hevc: whether the device decodes HEVC in hardware.
    static func make(screen: CGSize, hevc: Bool, setting: ResolutionSetting) -> MirrorProfile {
        let longSide = Int(max(screen.width, screen.height).rounded())
        let shortSide = Int(min(screen.width, screen.height).rounded())
        let size: (width: Int, height: Int)
        switch setting {
        case .native where hevc:
            size = fit(longSide, shortSide, within: 3840, 2160)
        case .native, .hd1080:
            size = (1920, 1080)
        }
        return MirrorProfile(width: size.width, height: size.height, h265: hevc,
                             screenWidth: longSide, screenHeight: shortSide)
    }

    /// The largest size with the same shape that fits, rounded down to even
    /// numbers (video encoders work in 2×2 blocks of colour).
    static func fit(_ width: Int, _ height: Int, within maxWidth: Int, _ maxHeight: Int) -> (width: Int, height: Int) {
        guard width > 0, height > 0 else { return (1920, 1080) }
        let scale = min(1, min(Double(maxWidth) / Double(width), Double(maxHeight) / Double(height)))
        let fittedWidth = Int((Double(width) * scale).rounded(.down)) & ~1
        let fittedHeight = Int((Double(height) * scale).rounded(.down)) & ~1
        return (fittedWidth, fittedHeight)
    }
}
