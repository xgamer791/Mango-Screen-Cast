import Foundation

/// Picks a starting bitrate for a stream and adapts it and the FEC level to
/// the loss the receiver reports (docs/PROTOCOL.md §5).
public struct RateController {
    public enum Quality: String, CaseIterable, Codable {
        case dataSaver
        case balanced
        case high

        var multiplier: Double {
            switch self {
            case .dataSaver: return 0.5
            case .balanced: return 1.0
            case .high: return 1.6
            }
        }
    }

    public static let minimumFec = 10
    public static let maximumBitrateKbps = 200_000

    public let targetKbps: Int
    public private(set) var bitrateKbps: Int
    public private(set) var fecPercent: Int = RateController.minimumFec
    private var cleanReports = 0

    public init(targetKbps: Int) {
        self.targetKbps = targetKbps
        bitrateKbps = targetKbps
    }

    /// Bitrate for desktop content. Text needs more bits than video, so this
    /// is generous: about 60 Mbit/s for 4K60 HEVC on the balanced setting.
    public static func targetBitrateKbps(width: Int, height: Int, fps: Int, codec: VideoCodec,
                                         quality: Quality, receiverMaxKbps: Int?) -> Int {
        let bitsPerPixel = codec == .hevc ? 0.12 : 0.18
        // Frame rates above 60 add detail more slowly than they add pixels.
        let effectiveFps = fps <= 60 ? Double(fps) : 60 + Double(fps - 60) * 0.5
        var kbps = Double(width * height) * effectiveFps * bitsPerPixel / 1000 * quality.multiplier
        kbps = max(kbps, 8_000 * quality.multiplier)
        var result = min(Int(kbps), maximumBitrateKbps)
        if let cap = receiverMaxKbps, cap > 0 { result = min(result, cap) }
        return result
    }

    public var floorKbps: Int { max(2_000, targetKbps / 5) }

    /// Returns true when the bitrate changed and the encoder should be updated.
    @discardableResult
    public mutating func update(with stats: Stats) -> Bool {
        let received = stats.packets ?? 0
        let lost = stats.lostPackets ?? 0
        let lostFrames = stats.lostFrames ?? 0
        let total = received + lost
        guard total > 0 || lostFrames > 0 else { return false }
        let loss = total > 0 ? Double(lost) / Double(total) : 1
        let before = bitrateKbps

        if lostFrames > 0 || loss > 0.03 {
            bitrateKbps = max(floorKbps, Int(Double(bitrateKbps) * 0.8))
            fecPercent = min(50, fecPercent + 10)
            cleanReports = 0
        } else if loss > 0.01 {
            fecPercent = min(40, fecPercent + 5)
            cleanReports = 0
        } else if loss < 0.003 {
            cleanReports += 1
            if cleanReports >= 3 {
                bitrateKbps = min(targetKbps, Int(Double(bitrateKbps) * 1.08) + 1)
                fecPercent = max(Self.minimumFec, fecPercent - 2)
            }
        } else {
            cleanReports = 0
        }
        return bitrateKbps != before
    }
}

public enum ModeSelection {
    /// The first receiver mode (they come best first) that this source can encode.
    public static func choose(from modes: [StreamMode], codecs: [VideoCodec], maxFps: Int) -> StreamMode? {
        modes.first { mode in
            guard let codec = mode.videoCodec, codecs.contains(codec) else { return false }
            return mode.fps <= maxFps && mode.width > 0 && mode.height > 0
        } ?? modes.compactMap { mode -> StreamMode? in
            // Every mode is faster than we allow: keep the size, cap the rate.
            guard let codec = mode.videoCodec, codecs.contains(codec) else { return nil }
            var capped = mode
            capped.fps = min(mode.fps, maxFps)
            return capped
        }.first
    }

    /// Largest even size with the content's aspect ratio that fits in the mode.
    public static func fit(width: Int, height: Int, into mode: StreamMode) -> (width: Int, height: Int) {
        guard width > 0, height > 0 else { return (mode.width, mode.height) }
        let scale = min(Double(mode.width) / Double(width), Double(mode.height) / Double(height), 1)
        let w = max(2, Int(Double(width) * scale) & ~1)
        let h = max(2, Int(Double(height) * scale) & ~1)
        return (w, h)
    }
}
