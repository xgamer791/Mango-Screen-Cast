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
                        ForEach(receiver.resolutionChoices(), id: \.scale) { choice in
                            HStack {
                                Text(choice.sizeText)
                                    .monospacedDigit()
                                Spacer()
                                Text(label(for: choice))
                                    .foregroundColor(.secondary)
                            }
                            .tag(choice.scale)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    if settings.resolutionOnTrial {
                        Button("Go back to \(receiver.currentProfile(scale: settings.confirmedResolutionScale).sizeText)") {
                            receiver.revertResolution()
                        }
                    }
                } header: {
                    Text("Resolution")
                } footer: {
                    Text("Every size here has this screen’s exact shape, the way it’s held, so the picture fills it with no black bars. Full is the sharpest; step down if the picture stutters on a busy Wi-Fi network, or to make everything bigger on a Mac’s separate display. A new size is only tried at first: the next time you mirror, you’re asked to keep it, and it goes back by itself after 20 seconds, if no picture arrives, or if mirroring stops first. Changing it reconnects whatever is mirroring.")
                }

                Section {
                    Toggle("Fill the screen", isOn: binding(\.fillScreen) { receiver.video.fillsScreen = $0 })
                } footer: {
                    Text("For a mirrored picture of another shape (a Mac’s own screen is wider than an iPad’s): zoom it to cover the screen, cutting off its edges. Double-tap while mirroring to switch.")
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

    private func label(for choice: MirrorProfile) -> String {
        var parts = [choice.scale == 100 ? "Full (best)" : "\(choice.scale)%"]
        if settings.resolutionOnTrial {
            if choice.scale == settings.resolutionScale { parts.append("trying") }
            if choice.scale == settings.confirmedResolutionScale { parts.append("last kept") }
        }
        return parts.joined(separator: " · ")
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

    private var resolution: Binding<Int> {
        Binding {
            settings.resolutionScale
        } set: { scale in
            receiver.setResolution(scale: scale)
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
