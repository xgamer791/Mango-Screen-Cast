import AVKit

/// Picture in Picture for the mirrored picture: leaving the app while a
/// device mirrors shows its screen in a floating window, which is also the
/// only way iOS lets an app keep decoding video in the background, so the
/// picture is live the moment Mango Cast comes back. Main thread.
final class PictureInPicture: NSObject {
    /// Receives one line per notable event.
    var log: ((String) -> Void)?

    private let layer: AVSampleBufferDisplayLayer
    private var controller: AVPictureInPictureController?

    init(layer: AVSampleBufferDisplayLayer) {
        self.layer = layer
        super.init()
    }

    /// Whether leaving the app starts Picture in Picture (while mirroring).
    var startsWhenLeaving = false {
        didSet { update() }
    }

    var isActive: Bool { controller?.isPictureInPictureActive ?? false }

    /// Back in the app: the picture goes back where it was.
    func stop() {
        if isActive { controller?.stopPictureInPicture() }
    }

    private func update() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        if controller == nil, startsWhenLeaving {
            let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: layer,
                                                                    playbackDelegate: self)
            let controller = AVPictureInPictureController(contentSource: source)
            controller.delegate = self
            // A live screen: no skipping, no scrubbing.
            controller.requiresLinearPlayback = true
            self.controller = controller
        }
        controller?.canStartPictureInPictureAutomaticallyFromInline = startsWhenLeaving
        if !startsWhenLeaving { stop() }
    }
}

extension PictureInPicture: AVPictureInPictureSampleBufferPlaybackDelegate {
    func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {
        // The Mac's screen can't be paused from here.
    }

    func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
        // Live, with no end.
        CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
        false
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    func pictureInPictureController(_ controller: AVPictureInPictureController, skipByInterval: CMTime,
                                    completion: @escaping () -> Void) {
        completion()
    }
}

extension PictureInPicture: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        log?("picture in picture started")
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        log?("picture in picture stopped")
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    failedToStartPictureInPictureWithError error: Error) {
        log?("picture in picture didn't start: \(error.localizedDescription)")
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completion: @escaping (Bool) -> Void) {
        // The mirrored screen is still there behind it.
        completion(true)
    }
}
