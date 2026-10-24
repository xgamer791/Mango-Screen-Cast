import SwiftUI
import UIKit

/// The mirrored screen, edge to edge. Tap to show who's mirroring, a Stop
/// button and live stats; they hide again after a few seconds. Double-tap
/// switches between showing the whole picture and filling the screen.
struct MirrorView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @State private var controlsVisible = true
    @State private var stats: VideoRenderer.Stats?

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoLayerView(renderer: receiver.video)
                .ignoresSafeArea()
            if receiver.status.paused {
                Label("Paused on \(receiver.status.clientName ?? "the other device")", systemImage: "pause.circle.fill")
                    .font(.title3.weight(.semibold))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(.ultraThinMaterial, in: Capsule())
            }
            VStack(spacing: 0) {
                if controlsVisible {
                    topBar.transition(.move(edge: .top).combined(with: .opacity))
                }
                Spacer()
                if settings.showStats || controlsVisible, let stats {
                    statsText(stats).transition(.opacity)
                }
            }
            .padding(16)
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2, perform: toggleFill)
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) { controlsVisible.toggle() }
        }
        .onAppear { receiver.video.fillsScreen = settings.fillScreen }
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
        .onAppear { stats = receiver.video.stats() }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .foregroundColor(.white)
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundColor(Theme.ready)
            Text("Mirroring \(receiver.status.clientName ?? "a device")")
                .font(.headline)
                .lineLimit(1)
            Spacer(minLength: 12)
            Button(action: toggleFill) {
                Label(settings.fillScreen ? "Fit" : "Fill",
                      systemImage: settings.fillScreen ? "arrow.down.right.and.arrow.up.left"
                                                       : "arrow.up.left.and.arrow.down.right")
                    .font(.headline)
            }
            .buttonStyle(.bordered)
            .tint(.white)
            Button(role: .destructive) {
                receiver.disconnect()
            } label: {
                Label("Stop", systemImage: "xmark.circle.fill")
                    .font(.headline)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.error)
        }
        .padding(.leading, 18)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// Fill zooms in to cover the screen when the picture's shape differs
    /// from it (a mirrored Mac on an iPad), cutting off the edges.
    private func toggleFill() {
        settings.fillScreen.toggle()
        receiver.video.fillsScreen = settings.fillScreen
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
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Hosts the renderer's AVSampleBufferDisplayLayer. The layer belongs to the
/// renderer and outlives this view, so leaving and coming back to the
/// player never interrupts decoding.
struct VideoLayerView: UIViewRepresentable {
    let renderer: VideoRenderer

    func makeUIView(context: Context) -> LayerHostView {
        let view = LayerHostView()
        view.backgroundColor = .black
        view.host(renderer.layer)
        return view
    }

    func updateUIView(_ view: LayerHostView, context: Context) {
        view.host(renderer.layer)
    }

    static func dismantleUIView(_ view: LayerHostView, coordinator: ()) {
        view.release()
    }
}

final class LayerHostView: UIView {
    private weak var hosted: CALayer?

    func host(_ sublayer: CALayer) {
        guard sublayer.superlayer !== layer else { return }
        sublayer.removeFromSuperlayer()
        layer.addSublayer(sublayer)
        hosted = sublayer
        setNeedsLayout()
    }

    /// Lets go of the layer unless another view has already taken it.
    func release() {
        if let hosted, hosted.superlayer === layer { hosted.removeFromSuperlayer() }
        hosted = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        hosted?.frame = bounds
        CATransaction.commit()
    }
}
