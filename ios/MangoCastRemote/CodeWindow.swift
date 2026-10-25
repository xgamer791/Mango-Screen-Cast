import AppKit
import SwiftUI

/// The code an iPad's Link asked for, in a window in front of everything,
/// so it's there to read without opening the menu. It closes once the iPad
/// is linked, or with Cancel.
final class CodeWindow {
    static let shared = CodeWindow()
    private var window: NSWindow?

    func show(server: RemoteServer) {
        if window == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 300),
                                  styleMask: [.titled, .closable], backing: .buffered, defer: false)
            window.title = "Mango Cast Remote"
            window.isReleasedWhenClosed = false
            window.level = .floating
            window.contentView = NSHostingView(rootView: CodeView().environmentObject(server))
            self.window = window
        }
        window?.center()
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.close()
        window = nil
    }
}

private struct CodeView: View {
    @EnvironmentObject private var server: RemoteServer

    var body: some View {
        VStack(spacing: 14) {
            Text(server.pairingFor.map { "Link “\($0)” with this Mac" } ?? "Link an iPad with this Mac")
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)
            Text("Enter this code on the iPad:")
                .foregroundColor(.secondary)
            if let code = server.pairingCode {
                Text(verbatim: String(code.prefix(3)) + " " + String(code.suffix(3)))
                    .font(.system(size: 52, weight: .bold, design: .monospaced))
                    .foregroundColor(mango)
                    .textSelection(.enabled)
            }
            Text("They stay linked until you unlink them; this is only needed once.")
                .font(.callout)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if !server.canControl {
                VStack(spacing: 6) {
                    Text("To click and type for the iPad, macOS needs Mango Cast Remote allowed under Privacy & Security → Accessibility.")
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Allow…") { server.requestAccess() }
                }
            }
            Button("Cancel") { server.stopPairing() }
                .keyboardShortcut(.cancelAction)
        }
        .padding(24)
        .frame(width: 400)
    }
}
