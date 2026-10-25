import ServiceManagement
import SwiftUI

/// Mango Cast Remote: a menu bar app that lets an iPad running Mango Cast
/// control this Mac: tap, scroll and type on the Mac's picture on the iPad.
@main
struct MangoCastRemoteApp: App {
    @StateObject private var server = RemoteServer()

    var body: some Scene {
        MenuBarExtra {
            RemoteMenu()
                .environmentObject(server)
        } label: {
            Image(systemName: server.connected.isEmpty ? "cursorarrow.rays" : "cursorarrow.motionlines")
        }
        .menuBarExtraStyle(.window)
    }
}

let mango = Color(red: 1, green: 164 / 255, blue: 27 / 255)

struct RemoteMenu: View {
    @EnvironmentObject private var server: RemoteServer
    @State private var openAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Mango Cast Remote")
                .font(.headline)
            Text("Lets an iPad running Mango Cast control this Mac while it shows this Mac’s screen: tap to click, swipe to scroll, and type with the iPad’s keyboard. To link an iPad, tap Link on it: a code pops up here to enter there.")
                .font(.callout)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Divider()

            if server.canControl {
                Label("Allowed to control this Mac", systemImage: "checkmark.circle.fill")
                    .foregroundColor(.green)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Needs Accessibility permission", systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text("Turn on Mango Cast Remote under System Settings → Privacy & Security → Accessibility.")
                        .font(.callout)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button("Ask for permission") { server.requestAccess() }
                        Button("Open Settings") {
                            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
                        }
                    }
                }
            }

            if server.connected.isEmpty {
                Label("No iPad connected", systemImage: "ipad.landscape")
                    .foregroundColor(.secondary)
            } else {
                ForEach(server.connected, id: \.self) { name in
                    Label("\(name) is connected", systemImage: "ipad.landscape")
                }
            }

            Divider()

            if let code = server.pairingCode {
                VStack(alignment: .leading, spacing: 8) {
                    Text("On the iPad, enter")
                        .font(.callout)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: String(code.prefix(3)) + " " + String(code.suffix(3)))
                        .font(.system(size: 34, weight: .bold, design: .monospaced))
                        .foregroundColor(mango)
                        .textSelection(.enabled)
                    Button("Cancel") { server.stopPairing() }
                }
            } else {
                Button("Show a link code…") { server.startPairing() }
            }

            if let message = server.message {
                Text(message)
                    .font(.callout)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !server.pairedNames.isEmpty {
                Text("Linked: \(server.pairedNames.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Unlink all iPads", role: .destructive) { server.forgetAll() }
            }

            Divider()

            Toggle("Open at login", isOn: $openAtLogin)
                .onChange(of: openAtLogin) { enabled in
                    do {
                        if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                    } catch {
                        openAtLogin = SMAppService.mainApp.status == .enabled
                    }
                }
            HStack {
                Text("This Mac: \(server.macName)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                Button("Quit") { NSApplication.shared.terminate(nil) }
            }
        }
        .padding(16)
        .frame(width: 320)
    }
}
