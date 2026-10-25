import SwiftUI

/// What's shown while nothing is mirroring: whether the device is ready,
/// the name to pick, and how to start from a Mac.
///
/// Laid out from the space the window actually has, so it works on every
/// iPad in either orientation, in Split View and Stage Manager windows, and
/// on iPhones: the two cards sit side by side only when each gets a
/// comfortable width, and stack otherwise.
struct HomeView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var updates: UpdateChecker
    @EnvironmentObject private var remote: RemoteControl
    @State private var showSettings = false

    /// Below this width (iPhones, narrow Split View panes) everything gets smaller.
    private static let compactWidth: CGFloat = 600
    /// Content never gets wider than this, so lines stay readable on a 13-inch iPad.
    private static let maxContentWidth: CGFloat = 1040
    /// Each card needs about this much to read well side by side.
    private static let sideBySideWidth: CGFloat = 860

    var body: some View {
        GeometryReader { geometry in
            let compact = geometry.size.width < Self.compactWidth
            let horizontalPadding: CGFloat = compact ? 20 : 48
            let verticalPadding: CGFloat = compact ? 16 : 32
            let contentWidth = max(0, min(geometry.size.width - horizontalPadding * 2, Self.maxContentWidth))
            let contentHeight = max(0, geometry.size.height - verticalPadding * 2)

            ScrollView {
                VStack(alignment: .leading, spacing: compact ? 24 : 36) {
                    header(compact: compact)
                    if updates.isReleaseBuild, let update = updates.availableUpdate {
                        updateBanner(update)
                    }
                    if let notice = receiver.notice {
                        Banner(systemImage: "arrow.uturn.backward.circle.fill", text: notice) {
                            Button("OK") { receiver.notice = nil }
                                .buttonStyle(.bordered)
                                .tint(Theme.textSecondary)
                        }
                    } else if settings.resolutionOnTrial(landscape: receiver.offeredLandscape) {
                        let landscape = receiver.offeredLandscape
                        let kept = settings.confirmedResolutionScale(landscape: landscape)
                        Banner(systemImage: "rectangle.dashed",
                               text: "Trying \(receiver.currentProfile(landscape: landscape).sizeText). Mirror from your Mac to see it; you’ll be asked to keep it, or it goes back to \(receiver.currentProfile(scale: kept, landscape: landscape).sizeText).") {
                            Button("Go back") { receiver.revertResolution() }
                                .buttonStyle(.bordered)
                                .tint(Theme.textSecondary)
                        }
                    }
                    Spacer(minLength: 0)
                    statusBlock(compact: compact)
                    cards(sideBySide: contentWidth >= Self.sideBySideWidth)
                    Spacer(minLength: 0)
                    footer
                }
                // As tall as the window when the content fits, so the
                // status sits in the middle; taller (and scrolling) when not.
                .frame(width: contentWidth, alignment: .leading)
                .frame(minHeight: contentHeight, idealHeight: contentHeight, alignment: .top)
                .padding(.vertical, verticalPadding)
                .frame(maxWidth: .infinity)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .foregroundColor(Theme.textPrimary)
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .environmentObject(receiver)
                .environmentObject(settings)
                .environmentObject(updates)
                .environmentObject(remote)
        }
    }

    private var status: Receiver.Status { receiver.status }
    private var name: String { status.receiverName.isEmpty ? settings.receiverName : status.receiverName }

    private func header(compact: Bool) -> some View {
        HStack(spacing: compact ? 12 : 16) {
            Image("Logo")
                .resizable()
                .frame(width: compact ? 40 : 52, height: compact ? 40 : 52)
                .accessibilityHidden(true)
            Text("Mango Cast")
                .font(.system(size: compact ? 22 : 28, weight: .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 12)
            Button {
                showSettings = true
            } label: {
                Label("Settings", systemImage: "gearshape")
                    .font(.headline)
            }
            .buttonStyle(.bordered)
            .tint(Theme.textSecondary)
        }
    }

    private func updateBanner(_ update: ReleaseFeed.Release) -> some View {
        Banner(systemImage: "arrow.down.circle.fill", text: "Mango Cast \(update.version) is available.") {
            Button("Update") { updates.openUpdate() }
                .buttonStyle(.borderedProminent)
                .tint(Theme.mango)
                .foregroundColor(.black)
        }
    }

    private func statusBlock(compact: Bool) -> some View {
        let dotSize: CGFloat = compact ? 12 : 16
        let indent = dotSize + 14
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Circle()
                    .fill(dotColor)
                    .frame(width: dotSize, height: dotSize)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: compact ? 30 : 44, weight: .bold))
                    .minimumScaleFactor(0.7)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(detail)
                .font(compact ? .body : .title3)
                .foregroundColor(Theme.textSecondary)
                .padding(.leading, indent)
                .fixedSize(horizontal: false, vertical: true)
            if status.state == .failed {
                Button("Try again") { receiver.restart() }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.mango)
                    .foregroundColor(.black)
                    .padding(.leading, indent)
                    .padding(.top, 6)
            }
        }
    }

    private var title: String {
        switch status.state {
        case .stopped, .starting: return "Starting…"
        case .ready: return "Ready to mirror"
        case .connected: return "\(status.clientName ?? "A device") is connected"
        case .mirroring: return "Mirroring \(status.clientName ?? "a device")"
        case .failed: return "AirPlay isn’t running"
        }
    }

    private var detail: String {
        switch status.state {
        case .stopped, .starting:
            return "Getting ready for Screen Mirroring."
        case .ready:
            let ready = "Pick “\(name)” in Screen Mirroring on your Mac."
            return status.message.map { "\($0) \(ready)" } ?? ready
        case .connected:
            return "Start Screen Mirroring on it to show its screen here."
        case .mirroring:
            return ""
        case .failed:
            return status.message ?? "Something stopped the AirPlay server."
        }
    }

    private var dotColor: Color {
        switch status.state {
        case .ready, .connected, .mirroring: return Theme.ready
        case .failed: return Theme.error
        case .stopped, .starting: return Theme.waiting
        }
    }

    /// Side by side the cards share the width equally and match heights;
    /// stacked, each takes the full width. Either way they're as tall as
    /// their text and no taller.
    @ViewBuilder
    private func cards(sideBySide: Bool) -> some View {
        if sideBySide {
            HStack(alignment: .top, spacing: 20) {
                macCard
                keepOpenCard
            }
            .fixedSize(horizontal: false, vertical: true)
        } else {
            VStack(alignment: .leading, spacing: 16) {
                macCard
                keepOpenCard
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var macCard: some View {
        Card(title: "From your Mac", systemImage: "laptopcomputer") {
            Step(number: 1, text: "Open Control Center and click Screen Mirroring.")
            Step(number: 2, text: "Pick “\(name)”.")
            Step(number: 3, text: "To use this screen as a second display instead of a copy, choose Use As Separate Display. It fills the screen exactly.")
            Text("No Screen Mirroring in Control Center? Use System Settings → Displays → Add Display.")
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var keepOpenCard: some View {
        Card(title: settings.runInBackground ? "Works in the background" : "Keep Mango Cast open",
             systemImage: "rectangle.on.rectangle") {
            Text(settings.runInBackground
                 ? "Switch apps or lock the screen and your Mac stays connected; only closing Mango Cast disconnects it. Your Mac and this device need to be on the same Wi-Fi network."
                 : "This device only shows up in Screen Mirroring while Mango Cast is on screen. Your Mac and this device need to be on the same Wi-Fi network.")
                .fixedSize(horizontal: false, vertical: true)
            Text("An iPhone or another iPad can mirror here too: Control Center → Screen Mirroring → “\(name)”.")
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var footer: some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            if let profile = status.profile {
                // verbatim: Text would otherwise print 2360 as "2,360".
                Text(verbatim: "Offering \(profile.sizeText)\(profile.scale == 100 ? "" : " (\(profile.scale)%)") · "
                     + "\(profile.maxFps) fps · \(profile.codecs)   ·   Screen \(profile.screenWidth) × \(profile.screenHeight)")
                    .fixedSize(horizontal: false, vertical: true)
                // Always one tap back to the size this device starts with.
                if settings.resolutionScale(landscape: receiver.offeredLandscape) != Settings.defaultResolutionScale {
                    Button("Back to the default resolution") {
                        receiver.setResolution(scale: Settings.defaultResolutionScale, landscape: receiver.offeredLandscape)
                    }
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Theme.mango)
                }
            } else {
                Text(" ")
            }
        }
        .font(.footnote)
        .foregroundColor(Theme.textSecondary)
    }
}

/// A one-line message with an action, under the header.
private struct Banner<Action: View>: View {
    let systemImage: String
    let text: String
    @ViewBuilder let action: Action

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.title2)
                .foregroundColor(Theme.mango)
            Text(text)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            action
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
    }
}

private struct Card<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: systemImage)
                .font(.headline)
                .foregroundColor(Theme.mango)
            content
        }
        .padding(22)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.surface))
    }
}

private struct Step: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(number)")
                .font(.subheadline.weight(.bold))
                .foregroundColor(.black)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Theme.mango))
            Text(text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
