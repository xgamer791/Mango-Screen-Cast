import CoreGraphics

/// What this device tells Macs, iPhones and iPads it can show. They size
/// their stream to it; a Mac's Use As Separate Display creates a display of
/// exactly this size.
///
/// Every size on offer has the screen's own shape (to within a pixel), in
/// the orientation the device is held, so the picture fills the screen with
/// no black bars. The full size is the screen's own pixels; smaller ones
/// trade sharpness for less data over Wi-Fi (and, on a Mac's separate
/// display, bigger text).
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
    /// Percent of the largest size on offer (100 = full).
    var scale = 100

    var codecs: String { h265 ? "HEVC + H.264" : "H.264" }
    var sizeText: String { "\(width) × \(height)" }

    /// The steps offered, as percent of the full size.
    static let scales = [100, 95, 90, 85, 80, 75, 70, 66, 60, 55, 50, 45, 40]

    /// - Parameters:
    ///   - screen: the screen's size in pixels, either orientation.
    ///   - landscape: whether the device is held in landscape.
    ///   - hevc: whether the device decodes HEVC in hardware.
    ///   - scale: percent of the full size, 40 to 100.
    static func make(screen: CGSize, landscape: Bool, hevc: Bool, scale: Int = 100) -> MirrorProfile {
        let longSide = Int(max(screen.width, screen.height).rounded())
        let shortSide = Int(min(screen.width, screen.height).rounded())
        let screenWidth = landscape ? longSide : shortSide
        let screenHeight = landscape ? shortSide : longSide
        // HEVC: up to 4K's size. H.264: about 1080p, which every sender can encode.
        let (limitLong, limitShort) = hevc ? (3840, 2160) : (1920, 1080)
        let full = fit(screenWidth, screenHeight,
                       within: landscape ? limitLong : limitShort, landscape ? limitShort : limitLong)
        let percent = min(100, max(scales.last!, scale))
        let size = percent == 100
            ? full
            : scaled(full.height, percent: percent, shape: screenWidth, screenHeight)
        return MirrorProfile(width: size.width, height: size.height, h265: hevc,
                             screenWidth: screenWidth, screenHeight: screenHeight, scale: percent)
    }

    /// Every size on offer, largest first.
    static func choices(screen: CGSize, landscape: Bool, hevc: Bool) -> [MirrorProfile] {
        var seen = Set<Int>()
        return scales
            .map { make(screen: screen, landscape: landscape, hevc: hevc, scale: $0) }
            .filter { min($0.width, $0.height) >= 480 && seen.insert($0.width * 100_000 + $0.height).inserted }
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

    /// `percent` of a picture `fullHeight` tall, in the shape
    /// `shapeWidth` × `shapeHeight`, with even sides. Looks a few pixels
    /// either side of the exact size for the one whose shape matches best,
    /// so the picture still fills the screen.
    static func scaled(_ fullHeight: Int, percent: Int, shape shapeWidth: Int, _ shapeHeight: Int) -> (width: Int, height: Int) {
        let aspect = Double(shapeWidth) / Double(shapeHeight)
        let target = Double(fullHeight) * Double(percent) / 100
        let center = Int(target / 2) * 2
        var best = (width: 0, height: 0, error: Double.infinity)
        for height in stride(from: max(2, center - 8), through: center + 8, by: 2) {
            let width = Int((Double(height) * aspect / 2).rounded()) * 2
            let error = abs(Double(width) / Double(height) - aspect)
            let closer = abs(Double(height) - target) < abs(Double(best.height) - target)
            if error < best.error - 1e-9 || (abs(error - best.error) <= 1e-9 && closer) {
                best = (width, height, error)
            }
        }
        return (best.width, best.height)
    }
}
