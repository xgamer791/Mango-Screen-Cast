import SwiftUI

/// The home screen, or the mirrored screen while a device is mirroring.
struct RootView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings

    var body: some View {
        ZStack {
            if receiver.status.state == .mirroring {
                MirrorView()
                    .transition(.opacity)
            } else {
                HomeView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: receiver.status.state == .mirroring)
        .onAppear(perform: updateIdleTimer)
        .onChange(of: receiver.status.state) { _ in updateIdleTimer() }
        .onChange(of: settings.keepAwake) { _ in updateIdleTimer() }
    }

    /// The screen stays on while mirroring and, unless turned off in
    /// Settings, while waiting: a locked device drops out of Screen Mirroring.
    private func updateIdleTimer() {
        let state = receiver.status.state
        let waiting = state == .ready || state == .connected || state == .starting
        UIApplication.shared.isIdleTimerDisabled = state == .mirroring || (waiting && settings.keepAwake)
    }
}
