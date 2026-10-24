import SwiftUI

/// What's shown while nothing is mirroring: whether the device is ready,
/// the name to pick, and how to start from a Mac or a Windows PC.
struct HomeView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @State private var showSettings = false

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: 32) {
                    header
                    Spacer(minLength: 0)
                    statusBlock
                    cards(wide: geometry.size.width >= 760)
                    Spacer(minLength: 0)
                    footer
                }
                .frame(maxWidth: 880, minHeight: max(0, geometry.size.height - 56), alignment: .leading)
                .padding(.horizontal, 32)
                .padding(.vertical, 28)
                .frame(maxWidth: .infinity)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .foregroundColor(Theme.textPrimary)
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
    }

    private var status: Receiver.Status { receiver.status }
    private var name: String { status.receiverName.isEmpty ? settings.receiverName : status.receiverName }

    private var header: some View {
        HStack(spacing: 16) {
            Image("Logo")
                .resizable()
                .frame(width: 52, height: 52)
                .accessibilityHidden(true)
            Text("Mango Screen Cast")
                .font(.system(size: 28, weight: .bold))
            Spacer()
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

    private var statusBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Circle()
                    .fill(dotColor)
                    .frame(width: 16, height: 16)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 40, weight: .bold))
                    .minimumScaleFactor(0.6)
                    .lineLimit(2)
            }
            Text(detail)
                .font(.title3)
                .foregroundColor(Theme.textSecondary)
                .padding(.leading, 30)
                .fixedSize(horizontal: false, vertical: true)
            if status.state == .failed {
                Button("Try again") { receiver.restart() }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.mango)
                    .foregroundColor(.black)
                    .padding(.leading, 30)
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
            let ready = settings.windowsEnabled
                ? "Pick “\(name)” in Screen Mirroring on your Mac, or in Mango Cast on a Windows PC."
                : "Pick “\(name)” in Screen Mirroring on your Mac."
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

    @ViewBuilder
    private func cards(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            if wide {
                HStack(alignment: .top, spacing: 20) {
                    macCard.layoutPriority(1)
                    keepOpenCard
                }
            } else {
                macCard
                keepOpenCard
            }
            if settings.windowsEnabled {
                windowsCard
            }
        }
    }

    private var macCard: some View {
        Card(title: "From your Mac", systemImage: "laptopcomputer") {
            Step(number: 1, text: "Open Control Center and click Screen Mirroring.")
            Step(number: 2, text: "Pick “\(name)”.")
            Step(number: 3, text: "To use this screen as a second display instead of a copy, choose Use As Separate Display.")
            Text("No Screen Mirroring in Control Center? Use System Settings → Displays → Add Display.")
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var windowsCard: some View {
        Card(title: "From a Windows PC", systemImage: "pc") {
            Step(number: 1, text: "On the PC, open Mango Cast for Windows.")
            Step(number: 2, text: "Pick “\(name)” and click Connect. Its screen and sound play here.")
            Text("Get Mango Cast for Windows from the Mango Screen Cast releases on GitHub. The PC and this device need to be on the same network.")
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var keepOpenCard: some View {
        Card(title: "Keep Mango open", systemImage: "rectangle.on.rectangle") {
            Text("This device only shows up in Screen Mirroring while Mango is on screen. Your Mac and this device need to be on the same Wi-Fi network.")
                .fixedSize(horizontal: false, vertical: true)
            Text("An iPhone or another iPad can mirror here too: Control Center → Screen Mirroring → “\(name)”.")
                .font(.subheadline)
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var footer: some View {
        Group {
            if let profile = status.profile {
                Text("Offering \(profile.width) × \(profile.height) · \(profile.maxFps) fps · \(profile.codecs)   ·   Screen \(profile.screenWidth) × \(profile.screenHeight)")
            } else {
                Text(" ")
            }
        }
        .font(.footnote)
        .foregroundColor(Theme.textSecondary)
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
        .frame(maxWidth: .infinity, alignment: .leading)
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
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
