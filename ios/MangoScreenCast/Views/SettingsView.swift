import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @Environment(\.dismiss) private var dismiss
    @State private var draftName = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(Settings.defaultName, text: $draftName)
                        .submitLabel(.done)
                        .onSubmit(commitName)
                        .autocorrectionDisabled()
                } header: {
                    Text("Name")
                } footer: {
                    Text("What your Mac shows in Screen Mirroring.")
                }

                Section {
                    Picker("Resolution", selection: resolution) {
                        Text("This screen (\(native.width) × \(native.height))").tag(ResolutionSetting.native)
                        Text("1080p (1920 × 1080)").tag(ResolutionSetting.hd1080)
                    }
                } footer: {
                    Text("Your Mac sizes its picture to this. With Use As Separate Display it becomes the size of the extra display. Changing it disconnects whoever is mirroring.")
                }

                Section {
                    Toggle("Sound", isOn: binding(\.soundEnabled) { receiver.setSoundEnabled($0) })
                    Toggle("Show stats while mirroring", isOn: binding(\.showStats))
                    Toggle("Keep the screen on while waiting", isOn: binding(\.keepAwake))
                } footer: {
                    Text("Sound plays the Mac’s audio here. A locked screen drops out of Screen Mirroring, so the screen stays on while Mango waits; it always stays on while mirroring.")
                }

                Section {
                    NavigationLink("Diagnostics") {
                        DiagnosticsView()
                    }
                } footer: {
                    Text("What this device offers and the recent AirPlay log. Include it when reporting a problem.")
                }

                Section {
                    Text(about)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        commitName()
                        dismiss()
                    }
                }
            }
        }
        .onAppear { draftName = settings.receiverName }
        .onDisappear(perform: commitName)
    }

    private var native: MirrorProfile {
        MirrorProfile.make(screen: Receiver.screenPixels(), hevc: Receiver.hevcSupported, setting: .native)
    }

    private var resolution: Binding<ResolutionSetting> {
        Binding {
            settings.resolution
        } set: { value in
            guard value != settings.resolution else { return }
            settings.resolution = value
            receiver.restart()
        }
    }

    private func binding(_ key: ReferenceWritableKeyPath<Settings, Bool>,
                         then apply: ((Bool) -> Void)? = nil) -> Binding<Bool> {
        Binding {
            settings[keyPath: key]
        } set: { value in
            settings[keyPath: key] = value
            apply?(value)
        }
    }

    /// Renaming restarts the server, so it happens once, when editing ends.
    private func commitName() {
        let name = draftName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            draftName = settings.receiverName
            return
        }
        guard name != settings.receiverName else { return }
        settings.receiverName = name
        draftName = settings.receiverName
        receiver.restart()
    }

    private var about: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        return "Mango Screen Cast \(version). Built on UxPlay (GPL-3.0), libplist (LGPL-2.1) and OpenSSL. AirPlay is a trademark of Apple Inc.; this app isn’t affiliated with Apple."
    }
}

struct DiagnosticsView: View {
    @EnvironmentObject private var receiver: Receiver
    @State private var report = ""

    var body: some View {
        ScrollView {
            Text(report)
                .font(.system(.footnote, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    report = receiver.diagnostics()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                ShareLink(item: report)
            }
        }
        .onAppear { report = receiver.diagnostics() }
    }
}
