import SwiftUI
import UIKit

/// The mirrored screen, edge to edge.
///
/// With Mango Cast Remote on the Mac, the picture is the Mac's screen to
/// work with, as Holli Bot's Screen is (ScreenView): tap to click, swipe to
/// scroll, pinch to zoom, and the keyboard button types on the Mac. The ⋯
/// button shows the bar saying what's mirroring. Control, Fill and stats are
/// in Settings.
///
/// Without it, a tap shows or hides the controls and a double tap switches
/// between showing the whole picture and filling the screen; pinching still
/// zooms.
struct MirrorView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var remote: RemoteControl
    /// The controls demo for UI tests: a stand-in picture, nothing sent.
    var demo = false

    @StateObject private var screen = ScreenController()
    @State private var typingField = TypingField()
    @State private var controlsVisible = true
    @State private var stats: VideoRenderer.Stats?
    @State private var typing = false
    @State private var showPairing = false
    /// The Mac to ask for a link code as the link screen opens.
    @State private var linkTarget: String?

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var controlling: Bool { remote.isConnected && settings.controlMac }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScreenSurface(renderer: demo ? nil : receiver.video, controller: screen, controlling: controlling,
                          demo: demo, send: remote.send, onTap: toggleControls, onDoubleTap: toggleFill,
                          onClick: { typingField.clear() })
                .ignoresSafeArea()
            TypingBox(isActive: $typing, send: remote.send, field: typingField)
                .frame(width: 1, height: 1)
                .opacity(0.01)
            if receiver.status.paused {
                Label("Paused on \(receiver.status.clientName ?? "the other device")", systemImage: "pause.circle.fill")
                    .font(.title3.weight(.semibold))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(.ultraThinMaterial, in: Capsule())
                    .allowsHitTesting(false)
            }
            if let check = receiver.resolutionCheck {
                ResolutionCheckCard(check: check, keep: receiver.keepResolution,
                                    goBack: { receiver.revertResolution() })
                    .transition(.scale.combined(with: .opacity))
            }
            VStack(spacing: 12) {
                if controlsVisible {
                    topBar.transition(.move(edge: .top).combined(with: .opacity))
                }
                if controlsVisible && !remote.isConnected && !demo {
                    setupHint.transition(.opacity)
                }
                if remote.isConnected && settings.controlMac && !remote.canControl {
                    Label("On the Mac, allow Mango Cast Remote under System Settings → Privacy & Security → Accessibility.",
                          systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                }
                Spacer()
                HStack(alignment: .bottom) {
                    // Only with Show stats on in Settings: ⋯ doesn't bring them up.
                    if settings.showStats, let stats, !demo {
                        statsText(stats).transition(.opacity)
                    }
                    Spacer(minLength: 0)
                    dock
                }
            }
            .padding(16)
            if demo {
                // The last few lines show; the tests read all of them.
                Text(remote.demoLog.suffix(12).joined(separator: "\n"))
                    .accessibilityLabel(remote.demoLog.joined(separator: "\n"))
                    .font(.system(.caption, design: .monospaced))
                    .padding(8)
                    .background(Color.black.opacity(0.6))
                    .accessibilityIdentifier("controlLog")
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(16)
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: screen.zoom)
        .sheet(isPresented: $showPairing) {
            PairMacView(linkTo: linkTarget)
                .environmentObject(remote)
                .environmentObject(settings)
        }
        .task(id: controlsVisible) {
            guard controlsVisible else { return }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled {
                withAnimation(.easeInOut(duration: 0.3)) { controlsVisible = false }
            }
        }
        .onReceive(ticker) { _ in
            stats = receiver.video.stats()
        }
        .onAppear {
            stats = receiver.video.stats()
            receiver.video.fillsScreen = settings.fillScreen
            if demo {
                remote.startDemo()
            } else {
                remote.activate(preferring: receiver.status.clientName, as: settings.receiverName)
            }
        }
        .onDisappear {
            typing = false
            remote.deactivate()
        }
        .onChange(of: receiver.status.clientName) { name in
            remote.activate(preferring: name, as: settings.receiverName)
        }
        .onChange(of: controlling) { on in
            if !on { typing = false }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .foregroundColor(.white)
    }

    private func toggleControls() {
        withAnimation(.easeInOut(duration: 0.2)) { controlsVisible.toggle() }
    }

    /// Fill zooms in to cover the screen when the picture's shape differs
    /// from it (a mirrored Mac on an iPad), cutting off the edges.
    private func toggleFill() {
        settings.fillScreen.toggle()
        receiver.video.fillsScreen = settings.fillScreen
    }

    /// Round buttons in the corner, as Holli Bot's Screen has: how far it's
    /// zoomed (tap for the whole screen), the keyboard, and ⋯ for the bar. In
    /// Liquid Glass, the glass of the iPad's Home Screen, with nothing round them.
    private var dock: some View {
        HStack(spacing: 10) {
            if screen.zoom > 1 {
                Button {
                    screen.showWholeScreen()
                } label: {
                    Text(String(format: "%.1f×", screen.zoom))
                        .font(.subheadline.weight(.bold).monospacedDigit())
                        .frame(minWidth: 44, minHeight: 44)
                        .padding(.horizontal, 6)
                        .contentShape(Capsule())
                        .homeScreenGlass(in: Capsule())
                }
                .buttonStyle(.plain)
                .noPointerHighlight()
                .accessibilityIdentifier("zoomBadge")
                .accessibilityLabel("Show the whole screen")
                .transition(.scale.combined(with: .opacity))
            }
            if controlling {
                dockButton("keyboard", on: typing, id: "keyboardButton", label: "Keyboard") { typing.toggle() }
                dockButton("ellipsis", on: controlsVisible, id: "moreButton", label: "More") { toggleControls() }
            }
        }
    }

    /// A round dock button in Liquid Glass; on (the keyboard up, the bar
    /// showing), the glass is tinted mango and the symbol black.
    private func dockButton(_ symbol: String, on: Bool, id: String, label: String,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .foregroundColor(on ? .black : .white)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
                .homeScreenGlass(in: Circle(), tint: on ? Theme.mango : nil)
        }
        .buttonStyle(.plain)
        .noPointerHighlight()
        .accessibilityIdentifier(id)
        .accessibilityLabel(label)
    }

    /// The bar ⋯ shows: what's mirroring.
    private var topBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundColor(Theme.ready)
            Text("Mirroring \(receiver.status.clientName ?? (demo ? "Demo Mac" : "a device"))")
                .font(.headline)
                .lineLimit(1)
            Spacer(minLength: 12)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("controlsBar")
    }

    /// Taps only click on the Mac with Mango Cast Remote running there; until
    /// then, say so, with the way to set it up.
    private var setupHint: some View {
        HStack(spacing: 12) {
            Image(systemName: "hand.tap.fill")
                .foregroundColor(Theme.mango)
            Text(setupHintText)
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button(linkableMac.map { _ in "Link" } ?? (remote.pairedMacs.isEmpty ? "Set up" : "Help")) {
                linkTarget = linkableMac
                showPairing = true
            }
                .buttonStyle(.borderedProminent)
                .tint(Theme.mango)
                .foregroundColor(.black)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: 640)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityIdentifier("controlSetupHint")
    }

    /// A Mac with Mango Cast Remote open that this iPad isn't linked with:
    /// the one mirroring, if it's one of them.
    private var linkableMac: String? {
        let unlinked = remote.macsAround.filter { !remote.pairedMacs.contains($0) }
        return unlinked.first { $0 == receiver.status.clientName } ?? (unlinked.count == 1 ? unlinked[0] : nil)
    }

    private var setupHintText: String {
        if case .failed(let reason) = remote.state { return reason }
        if let mac = linkableMac {
            return "Link with \(mac) once to tap to click, scroll and type on it from here."
        }
        if remote.pairedMacs.isEmpty {
            return "Pinch to zoom and drag to move around. To tap to click, scroll and type on the Mac too, get Mango Cast Remote on the Mac and pair once."
        }
        return "Looking for Mango Cast Remote on your Mac. Make sure it’s open there (it lives in the menu bar) and on the same Wi-Fi."
    }

    private func statsText(_ v: VideoRenderer.Stats) -> some View {
        let offered = receiver.status.profile.map { "offering \($0.width) × \($0.height)" } ?? ""
        let line1 = String(format: "%ld × %ld %@ · %.0f fps · %.1f Mbit/s", v.width, v.height, v.codec, v.fps,
                           v.megabitsPerSecond)
        let line2 = "dropped \(v.dropped) · recoveries \(v.recoveries) · \(offered)"
        return VStack(alignment: .leading, spacing: 2) {
            Text(line1)
            Text(line2)
        }
        .font(.system(.footnote, design: .monospaced))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .allowsHitTesting(false)
    }
}

private extension View {
    /// Liquid Glass in `shape`: the glass of the iPad's Home Screen and its
    /// Dock (iOS 26), reacting to a finger, tinted with `tint` if given.
    /// Before iOS 26 there's no Liquid Glass: a material, or the tint. Built
    /// with an Xcode older than 26 (no iOS 26 SDK), always that.
    @ViewBuilder
    func homeScreenGlass<S: Shape>(in shape: S, tint: Color? = nil) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            glassEffect((tint.map { Glass.regular.tint($0) } ?? .regular).interactive(), in: shape)
        } else {
            noGlass(in: shape, tint: tint)
        }
        #else
        noGlass(in: shape, tint: tint)
        #endif
    }

    private func noGlass<S: Shape>(in shape: S, tint: Color?) -> some View {
        background(tint.map { AnyShapeStyle($0) } ?? AnyShapeStyle(Material.ultraThinMaterial), in: shape)
    }

    /// No highlight round it from the iPad's pointer (a trackpad or a mouse).
    @ViewBuilder
    func noPointerHighlight() -> some View {
        if #available(iOS 17.0, *) {
            hoverEffectDisabled()
        } else {
            self
        }
    }
}

/// Asks whether to keep a picture size that's being tried, counting down
/// to going back on its own, the way a Mac does after a display change.
private struct ResolutionCheckCard: View {
    let check: Receiver.ResolutionCheck
    let keep: () -> Void
    let goBack: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            if let deadline = check.deadline {
                Text("Keep \(check.trying.sizeText)?")
                    .font(.title2.weight(.bold))
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let seconds = max(0, Int(deadline.timeIntervalSince(context.date).rounded(.up)))
                    Text("Going back to \(check.previous.sizeText) in \(seconds) s unless you keep it.")
                        .font(.body)
                        .monospacedDigit()
                        .multilineTextAlignment(.center)
                }
                HStack(spacing: 12) {
                    Button("Go back", action: goBack)
                        .buttonStyle(.bordered)
                        .tint(.white)
                    Button("Keep", action: keep)
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.mango)
                        .foregroundColor(.black)
                }
                .font(.headline)
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Trying \(check.trying.sizeText)…")
                        .font(.headline)
                }
                Button("Go back to \(check.previous.sizeText)", action: goBack)
                    .buttonStyle(.bordered)
                    .tint(.white)
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(24)
    }
}
