import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var receiver: Receiver
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var updates: UpdateChecker
    @EnvironmentObject private var remote: RemoteControl
    @State private var showPairing = false
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
                    Toggle("Keep running in the background", isOn: binding(\.runInBackground) { _ in
                        receiver.backgroundSettingsChanged()
                    })
                    Toggle("Picture in Picture when you leave", isOn: binding(\.pictureInPicture) { _ in
                        receiver.backgroundSettingsChanged()
                    })
                    .disabled(!settings.runInBackground)
                } header: {
                    Text("In the background")
                } footer: {
                    Text("Your Mac stays connected while you use other apps or lock the screen, and this device stays in Screen Mirroring; only closing Mango Cast (swiping it away in the app switcher) disconnects it. iOS stops decoding video for apps in the background, so leaving while a Mac mirrors shows its screen in Picture in Picture, and the picture is live when you come back; without it, the picture catches up when you come back. Off, Mango Cast stops receiving when you leave it.")
                }

                Section {
                    Toggle("Sound", isOn: binding(\.soundEnabled) { receiver.setSoundEnabled($0) })
                    Toggle("Show stats while mirroring", isOn: binding(\.showStats))
                    Toggle("Keep the screen on while waiting", isOn: binding(\.keepAwake))
                } footer: {
                    Text("Sound plays the Mac’s audio here. The screen stays on while mirroring, and while Mango Cast waits unless you turn that off.")
                }

                Section {
                    ForEach(remote.pairedMacs, id: \.self) { mac in
                        HStack {
                            Label(mac, systemImage: "desktopcomputer")
                            Spacer()
                            Button("Forget", role: .destructive) { remote.forget(mac) }
                                .buttonStyle(.borderless)
                        }
                    }
                    Button(remote.pairedMacs.isEmpty ? "Pair with a Mac…" : "Pair with another Mac…") {
                        showPairing = true
                    }
                    Toggle("Control the Mac while mirroring", isOn: binding(\.controlMac))
                } header: {
                    Text("Mac control")
                } footer: {
                    Text("With Mango Cast Remote running on the Mac, work the Mac on this screen while it mirrors: tap to click, swipe to scroll, pinch to zoom in and move around, and the keyboard button types on the Mac. A mouse, trackpad or keyboard attached to this iPad works the Mac too. Pair once; only paired iPads can control the Mac, and everything they send is encrypted.")
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
        .sheet(isPresented: $showPairing) {
            PairMacView()
                .environmentObject(remote)
                .environmentObject(settings)
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
