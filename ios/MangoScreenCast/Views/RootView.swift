import SwiftUI

/// The home screen, or the mirrored screen while a device is mirroring.
struct RootView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings

    var body: some View {
        ZStack {
            if Self.controlsDemo {
                MirrorView(demo: true)
            } else if receiver.status.state == .mirroring {
                MirrorView()
                    .transition(.opacity)
            } else {
                HomeView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: receiver.status.state == .mirroring)
        .background(
            // Turning the device changes the shape the next Mac should send.
            GeometryReader { geometry in
                Color.clear.onChange(of: geometry.size) { _ in receiver.screenShapeChanged() }
            }
        )
        .onAppear(perform: updateIdleTimer)
        .onChange(of: receiver.status.state) { _ in updateIdleTimer() }
        .onChange(of: settings.keepAwake) { _ in updateIdleTimer() }
    }

    /// The controls demo, for UI tests (MangoScreenCastUITests): the mirrored
    /// screen with a stand-in picture, listing what would go to the Mac.
    private static let controlsDemo: Bool = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-MangoControlsDemo")
        #else
        return false
        #endif
    }()

    /// The screen stays on while mirroring and, unless turned off in
    /// Settings, while waiting: a locked device drops out of Screen Mirroring.
    private func updateIdleTimer() {
        let state = receiver.status.state
        let waiting = state == .ready || state == .connected || state == .starting
        UIApplication.shared.isIdleTimerDisabled = state == .mirroring || (waiting && settings.keepAwake)
    }
}
