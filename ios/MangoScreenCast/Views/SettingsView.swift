import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var updates: UpdateChecker
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
                        Text("Automatic (\(best.width) × \(best.height))").tag(ResolutionSetting.native)
                        Text("Lighter (\(lighter.width) × \(lighter.height))").tag(ResolutionSetting.hd1080)
                    }
                    Toggle("Fill the screen", isOn: binding(\.fillScreen) { receiver.video.fillsScreen = $0 })
                } header: {
                    Text("Picture")
                } footer: {
                    Text("Automatic offers this screen’s own pixels, the way it’s held, so with Use As Separate Display your Mac makes a display that fills it exactly. Lighter keeps the same shape with less detail, for a busy Wi-Fi network. Fill the screen zooms a mirrored picture of another shape to cover the screen, cutting off its edges; double-tap while mirroring to switch.")
                }

                Section {
                    Toggle("Sound", isOn: binding(\.soundEnabled) { receiver.setSoundEnabled($0) })
                    Toggle("Show stats while mirroring", isOn: binding(\.showStats))
                    Toggle("Keep the screen on while waiting", isOn: binding(\.keepAwake))
                } footer: {
                    Text("Sound plays the Mac’s audio here. A locked screen drops out of Screen Mirroring, so the screen stays on while Mango Cast waits; it always stays on while mirroring.")
                }

                Section {
                    LabeledContent("Version", value: "\(updates.version) (\(updates.build))")
                    HStack {
                        Text(updateStatus)
                            .foregroundColor(.secondary)
                        Spacer()
                        if updates.state == .checking { ProgressView() }
                    }
                    Button("Check for updates") { updates.check() }
                        .disabled(updates.state == .checking)
                    Button(updates.availableUpdate == nil ? "Get updates with AltStore or SideStore" : "Install the update") {
                        updates.openUpdate()
                    }
                } header: {
                    Text("Updates")
                } footer: {
                    Text("iOS only lets the App Store update apps by itself. Add Mango Cast to AltStore or SideStore (the button above does it) and new versions show up there to install with a tap; AltStore also renews the app every week so it keeps opening. Built it in Xcode? Get the latest code and press Run.")
                }

                Section {
                    Toggle("Windows computers", isOn: binding(\.windowsEnabled) { _ in receiver.applyWindowsSetting() })
                } footer: {
                    Text("Lets PCs show their screen and play their sound here with Mango Cast for Windows. They find this device on the same Wi-Fi network, by its name.")
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

    private var best: MirrorProfile {
        MirrorProfile.make(screen: Receiver.screenPixels(), landscape: Receiver.isLandscape(),
                           hevc: Receiver.hevcSupported, setting: .native)
    }

    private var lighter: MirrorProfile {
        MirrorProfile.make(screen: Receiver.screenPixels(), landscape: Receiver.isLandscape(),
                           hevc: Receiver.hevcSupported, setting: .hd1080)
    }

    private var updateStatus: String {
        switch updates.state {
        case .idle: return updates.isReleaseBuild ? "Not checked yet" : "Built in Xcode"
        case .checking: return "Checking…"
        case .upToDate: return "Up to date"
        case .available(let release): return "Version \(release.version) is available"
        case .failed(let reason): return "Couldn’t check: \(reason)"
        }
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
        return "Mango Cast \(version). Built on UxPlay (GPL-3.0), libplist (LGPL-2.1) and OpenSSL. AirPlay is a trademark of Apple Inc.; this app isn’t affiliated with Apple."
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
