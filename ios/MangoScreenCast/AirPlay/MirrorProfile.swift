import CoreGraphics

enum ResolutionSetting: String, CaseIterable, Identifiable {
    /// Automatic: the most this device shows, which is its screen's own
    /// pixels (e.g. 2360 × 1640 on an iPad Air).
    case native
    /// About 1080p in the screen's shape: less data for a busy Wi-Fi network.
    case hd1080

    var id: String { rawValue }
}

/// What this device tells Macs, iPhones and iPads it can show. They size
/// their stream to it; a Mac's Use As Separate Display creates a display of
/// exactly this size.
///
/// Every choice keeps the screen's shape, in the orientation the device is
/// held, so the picture fills the screen with no black bars.
struct MirrorProfile: Equatable {
    var width: Int
    var height: Int
    var refreshRate = 60
    var maxFps = 60
    /// Offer HEVC. Above 1080p, Apple devices only send HEVC.
    var h265: Bool
    /// The screen in pixels, in the orientation the profile was made for.
    var screenWidth: Int
    var screenHeight: Int

    var codecs: String { h265 ? "HEVC + H.264" : "H.264" }

    /// - Parameters:
    ///   - screen: the screen's size in pixels, either orientation.
    ///   - landscape: whether the device is held in landscape.
    ///   - hevc: whether the device decodes HEVC in hardware.
    static func make(screen: CGSize, landscape: Bool, hevc: Bool, setting: ResolutionSetting) -> MirrorProfile {
        let longSide = Int(max(screen.width, screen.height).rounded())
        let shortSide = Int(min(screen.width, screen.height).rounded())
        let screenWidth = landscape ? longSide : shortSide
        let screenHeight = landscape ? shortSide : longSide
        // HEVC: up to 4K's size. H.264 (or the lighter setting): about 1080p,
        // which every sender can encode.
        let (limitLong, limitShort) = setting == .native && hevc ? (3840, 2160) : (1920, 1080)
        let size = fit(screenWidth, screenHeight,
                       within: landscape ? limitLong : limitShort, landscape ? limitShort : limitLong)
        return MirrorProfile(width: size.width, height: size.height, h265: hevc,
                             screenWidth: screenWidth, screenHeight: screenHeight)
    }

    /// The largest size with the same shape that fits, rounded down to even
    /// numbers (video encoders work in 2×2 blocks of colour).
    static func fit(_ width: Int, _ height: Int, within maxWidth: Int, _ maxHeight: Int) -> (width: Int, height: Int) {
        guard width > 0, height > 0 else { return (1920, 1080) }
        if width <= maxWidth && height <= maxHeight { return (width & ~1, height & ~1) }
        // Whole numbers, so the limiting side lands exactly on its limit.
        if width * maxHeight >= height * maxWidth {
            return (maxWidth & ~1, (height * maxWidth / width) & ~1)
        }
        return ((width * maxHeight / height) & ~1, maxHeight & ~1)
    }
}
