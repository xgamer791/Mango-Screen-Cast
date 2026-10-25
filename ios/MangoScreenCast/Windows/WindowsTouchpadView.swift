import SwiftUI
import UIKit

/// Mango Cast as a touchpad and keyboard for a Windows PC running Mango Screen
/// Cast For Windows: the PC's screen is on the TV, and this works it.
///
///  - One finger moves the pointer; a tap clicks; tap twice to double-click.
///  - Two fingers scroll (the page follows them); a two-finger tap right-clicks.
///  - Hold a moment, then drag, to move things or select (the button stays down).
///  - The keyboard button types on the PC, and the keys underneath press Esc,
///    Tab, the Windows key, the arrows, Backspace and Enter.
///
/// Linking happens here too: Link makes the PC pop up a code to enter.
struct WindowsTouchpadView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: Settings
    @StateObject private var remote = WindowsRemote()
    @State private var typingField = TypingField()
    @State private var typing = false
    @State private var code = ""

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 14) {
                header
                if remote.isConnected {
                    touchpad
                } else {
                    ScrollView { setup.frame(maxWidth: .infinity, alignment: .leading) }
                }
            }
            .padding(16)
            // The keyboard's text box, out of sight (as on the mirrored Mac screen).
            TypingBox(isActive: $typing, send: forwardTyping, field: typingField)
                .frame(width: 1, height: 1)
                .opacity(0.01)
        }
        .foregroundColor(Theme.textPrimary)
        .onAppear { remote.start(as: settings.receiverName) }
        .onDisappear { remote.stop() }
        .onChange(of: remote.isConnected) { connected in
            if !connected { typing = false }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 12) {
            Button("Done") { dismiss() }
                .buttonStyle(.bordered)
                .tint(Theme.textSecondary)
            VStack(alignment: .leading, spacing: 2) {
                Label("Windows PC", systemImage: "pc")
                    .font(.headline)
                    .foregroundColor(Theme.mango)
                Text(statusText)
                    .font(.subheadline)
                    .foregroundColor(Theme.textSecondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            if remote.isConnected {
                Button {
                    typing.toggle()
                } label: {
                    Image(systemName: typing ? "keyboard.chevron.compact.down" : "keyboard")
                        .font(.title2)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.mango)
                .foregroundColor(.black)
                .accessibilityLabel(typing ? "Hide the keyboard" : "Type on the PC")
                if remote.linkedPCs.count > 1 {
                    Menu {
                        ForEach(remote.linkedPCs, id: \.self) { pc in
                            Button(pc) { remote.choose(pc) }
                        }
                    } label: {
                        Image(systemName: "arrow.left.arrow.right").font(.title3)
                    }
                    .accessibilityLabel("Switch PC")
                }
            }
        }
    }

    private var statusText: String {
        switch remote.state {
        case .off, .searching:
            return remote.linkedPCs.isEmpty ? "Link this iPhone with your PC once" : "Looking for your PC…"
        case .connecting(let pc): return "Connecting to \(pc)…"
        case .connected(let pc): return "Working \(pc) (on the TV)"
        case .failed(let reason): return reason
        }
    }

    // MARK: - The touchpad

    private var touchpad: some View {
        VStack(spacing: 12) {
            TouchpadSurface(send: remote.send)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface))
                .overlay(alignment: .bottom) {
                    Text("Tap to click · Two fingers to scroll, tap with two to right-click · Hold, then drag to move things")
                        .font(.footnote)
                        .foregroundColor(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(14)
                        .allowsHitTesting(false)
                }
                .accessibilityIdentifier("windowsTouchpad")
            keyRow
        }
    }

    private var keyRow: some View {
        HStack(spacing: 6) {
            key("escape", "Esc", 53)
            key("arrow.right.to.line", "Tab", 48)
            key("square.grid.2x2", "Windows key", WindowsRemoteProtocol.windowsKey)
            key("arrow.left", "Left", 123)
            key("arrow.up", "Up", 126)
            key("arrow.down", "Down", 125)
            key("arrow.right", "Right", 124)
            key("delete.left", "Backspace", 51)
            key("return", "Enter", 36)
        }
    }

    private func key(_ symbol: String, _ label: String, _ code: Int) -> some View {
        Button {
            remote.send(.key(code: code, modifiers: []))
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 40)
        }
        .buttonStyle(.bordered)
        .tint(Theme.textSecondary)
        .accessibilityLabel(label)
    }

    /// The keyboard's typing (made for the Mac's keys) goes to the PC as it is.
    private func forwardTyping(_ message: RemoteProtocol.Message) {
        switch message {
        case .text(let text):
            remote.send(.text(text))
        case .key(let code, let modifiers):
            remote.send(.key(code: Int(code), modifiers: WindowsRemoteProtocol.Modifiers(rawValue: Int(modifiers.rawValue))))
        default:
            break
        }
    }

    // MARK: - Finding and linking a PC

    private var setup: some View {
        VStack(alignment: .leading, spacing: 16) {
            if remote.pcsAround.isEmpty {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Looking for PCs with Mango Screen Cast For Windows open…")
                        .font(.headline)
                }
                Text("Open Mango Screen Cast For Windows on your PC, on the same Wi-Fi network as this iPhone. If Windows Firewall is in the way, the app’s iPhone control row says so: click it to allow.")
                    .foregroundColor(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(remote.linkedPCs.isEmpty ? "Pick your PC to link this iPhone with it, once." : "PCs on this network")
                    .font(.headline)
                ForEach(remote.pcsAround, id: \.self) { pc in
                    pcRow(pc)
                }
            }
            if let message = remote.message {
                Text(message)
                    .font(.subheadline)
                    .foregroundColor(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !remote.linkedPCs.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Linked")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Theme.textSecondary)
                    ForEach(remote.linkedPCs, id: \.self) { pc in
                        HStack {
                            Text(pc)
                            Spacer()
                            Button("Unlink", role: .destructive) { remote.unlink(pc) }
                                .font(.subheadline)
                        }
                    }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
            }
        }
    }

    private func pcRow(_ pc: String) -> some View {
        let linked = remote.linkedPCs.contains(pc)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "pc").font(.title2).foregroundColor(Theme.mango)
                Text(pc).font(.headline)
                Spacer()
                if remote.requestingCode == pc {
                    ProgressView()
                } else if linked {
                    Button("Connect") { remote.choose(pc) }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.mango)
                        .foregroundColor(.black)
                } else {
                    Button("Link") {
                        code = ""
                        remote.requestCode(from: pc)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.mango)
                    .foregroundColor(.black)
                }
            }
            if remote.codeShownOn == pc {
                Text("Enter the code \(pc) shows:")
                    .foregroundColor(Theme.textSecondary)
                HStack(spacing: 12) {
                    TextField("6-digit code", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .font(.title2.monospacedDigit())
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.background))
                        .onChange(of: code) { value in
                            let digits = String(value.filter(\.isNumber).prefix(6))
                            if digits != value { code = digits }
                            if digits.count == 6 && !remote.pairing { remote.pair(with: pc, code: digits) }
                        }
                    if remote.pairing {
                        ProgressView()
                    } else {
                        Button("Link") { remote.pair(with: pc, code: code) }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.mango)
                            .foregroundColor(.black)
                            .disabled(code.count != 6)
                    }
                }
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
    }
}

/// The touchpad itself: touches in, pointer and wheel out (TouchpadGestures).
struct TouchpadSurface: UIViewRepresentable {
    let send: (WindowsRemoteProtocol.Message) -> Void

    func makeUIView(context: Context) -> TouchpadUIView {
        let view = TouchpadUIView()
        view.send = send
        return view
    }

    func updateUIView(_ view: TouchpadUIView, context: Context) {
        view.send = send
    }
}

final class TouchpadUIView: UIView {
    var send: ((WindowsRemoteProtocol.Message) -> Void)?

    private var touches: [UITouch: CGPoint] = [:]
    private var mostFingers = 0
    private var began: TimeInterval = 0
    private var lastTime: TimeInterval = 0
    private var travel: CGFloat = 0
    private var dragging = false
    private var hold: Timer?
    private let tap = UIImpactFeedbackGenerator(style: .light)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        isAccessibilityElement = true
        accessibilityLabel = "Touchpad"
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let now = event?.timestamp ?? CACurrentMediaTime()
        if self.touches.isEmpty {
            began = now
            lastTime = now
            travel = 0
            mostFingers = 0
            tap.prepare()
        }
        for touch in touches { self.touches[touch] = touch.location(in: self) }
        mostFingers = max(mostFingers, self.touches.count)
        hold?.invalidate()
        hold = nil
        if self.touches.count == 1 && mostFingers == 1 {
            hold = Timer.scheduledTimer(withTimeInterval: TouchpadGestures.holdTime, repeats: false) { [weak self] _ in
                self?.startDrag()
            }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        var sum = CGPoint.zero
        var count = 0
        for touch in touches {
            guard let previous = self.touches[touch] else { continue }
            let location = touch.location(in: self)
            sum.x += location.x - previous.x
            sum.y += location.y - previous.y
            count += 1
            self.touches[touch] = location
        }
        guard count > 0 else { return }
        let now = event?.timestamp ?? CACurrentMediaTime()
        let elapsed = max(0.001, now - lastTime)
        lastTime = now
        let delta = CGPoint(x: sum.x / CGFloat(count), y: sum.y / CGFloat(count))
        let distance = hypot(delta.x, delta.y)
        travel += distance
        if travel > TouchpadGestures.tapSlop {
            hold?.invalidate()
            hold = nil
        }
        if self.touches.count == 1 {
            let gain = TouchpadGestures.gain(speed: distance / CGFloat(elapsed))
            send?(.move(dx: Double(delta.x * gain), dy: Double(delta.y * gain)))
        } else if self.touches.count == 2 && !dragging {
            send?(.scroll(dx: Double(delta.x), dy: Double(delta.y)))
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { self.touches[touch] = nil }
        guard self.touches.isEmpty else { return }
        hold?.invalidate()
        hold = nil
        let now = event?.timestamp ?? CACurrentMediaTime()
        switch TouchpadGestures.ending(fingers: mostFingers, duration: now - began, travel: travel, dragging: dragging) {
        case .click:
            send?(.click(right: false))
            tap.impactOccurred()
        case .rightClick:
            send?(.click(right: true))
            tap.impactOccurred()
        case .endDrag:
            send?(.button(right: false, down: false))
            dragging = false
        case .nothing:
            break
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { self.touches[touch] = nil }
        guard self.touches.isEmpty else { return }
        hold?.invalidate()
        hold = nil
        if dragging {
            send?(.button(right: false, down: false))
            dragging = false
        }
    }

    /// Held still: the button goes down, and moving drags.
    private func startDrag() {
        hold = nil
        guard touches.count == 1, travel < TouchpadGestures.tapSlop, !dragging else { return }
        dragging = true
        send?(.button(right: false, down: true))
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}
