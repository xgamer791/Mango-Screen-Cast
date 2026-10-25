import SwiftUI
import UIKit

/// Linking with a Mac running Mango Cast Remote: Link here pops up a code on
/// the Mac, which is entered here, once. They stay linked until unlinked.
struct PairMacView: View {
    /// Ask this Mac for a code as soon as the screen opens.
    var linkTo: String?

    @EnvironmentObject private var remote: RemoteControl
    @EnvironmentObject private var settings: Settings
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var linkCopied = false
    @FocusState private var codeFocused: Bool

    /// The Mac showing a code to enter: the one asked, or one showing a
    /// code from its own menu.
    private var macWithCode: String? {
        remote.codeShownOn ?? (remote.macsPairing.count == 1 ? remote.macsPairing.first : nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let mac = macWithCode {
                    Section {
                        TextField("123 456", text: $code)
                            .keyboardType(.numberPad)
                            .textContentType(.oneTimeCode)
                            .font(.system(.title, design: .monospaced))
                            .focused($codeFocused)
                            .accessibilityIdentifier("linkCode")
                        Button {
                            remote.pair(with: mac, code: code, as: settings.receiverName)
                        } label: {
                            HStack {
                                Text("Link")
                                if remote.pairing { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(remote.pairing || code.filter(\.isNumber).count != 6)
                    } header: {
                        Text("Enter the code shown on \(mac)")
                    } footer: {
                        Text("Once linked, they stay linked, even if Mango Cast is deleted and installed again, until you unlink them.")
                    }
                }

                if let message = remote.pairingMessage {
                    Section {
                        Text(message)
                    }
                }

                Section {
                    if remote.macsAround.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Looking for Macs with Mango Cast Remote open…")
                                .foregroundColor(.secondary)
                        }
                    }
                    ForEach(remote.macsAround, id: \.self) { mac in
                        HStack {
                            Label(mac, systemImage: "desktopcomputer")
                            Spacer()
                            if remote.pairedMacs.contains(mac) {
                                Label("Linked", systemImage: "checkmark.circle.fill")
                                    .foregroundColor(Theme.ready)
                            } else if remote.requestingCode == mac {
                                ProgressView()
                            } else {
                                Button(macWithCode == mac ? "New code" : "Link") { link(mac) }
                                    .buttonStyle(.borderedProminent)
                                    .tint(Theme.mango)
                                    .foregroundColor(.black)
                            }
                        }
                    }
                } header: {
                    Text("Your Macs")
                } footer: {
                    Text("Link pops up a code on the Mac. Enter it here, and that’s it.")
                }

                if !remote.pairedMacs.isEmpty {
                    Section("Linked") {
                        ForEach(remote.pairedMacs, id: \.self) { mac in
                            HStack {
                                Label(mac, systemImage: "checkmark.circle.fill")
                                Spacer()
                                Button("Unlink", role: .destructive) { remote.forget(mac) }
                                    .buttonStyle(.borderless)
                            }
                        }
                    }
                }

                Section {
                    Text("AirPlay only sends the Mac’s picture here, so to click and type on the Mac, a small app on the Mac does it for this iPad: Mango Cast Remote. Get it once:")
                    ShareLink(item: ReleaseFeed.macRemoteDownload) {
                        Label("Send the download link to your Mac", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        UIPasteboard.general.url = ReleaseFeed.macRemoteDownload
                        linkCopied = true
                    } label: {
                        Label(linkCopied ? "Link copied" : "Copy the download link",
                              systemImage: linkCopied ? "checkmark" : "doc.on.doc")
                    }
                    Text(verbatim: ReleaseFeed.macRemoteDownload.absoluteString)
                        .font(.footnote.monospaced())
                        .foregroundColor(.secondary)
                        .textSelection(.enabled)
                } header: {
                    Text("Mac not listed?")
                } footer: {
                    Text("AirDrop the link to the Mac (or open it there), open the zip and move MangoCastRemote to Applications. The first time, Control-click it and choose Open; it then asks to be allowed under Privacy & Security → Accessibility, which is what lets it click and type. It opens by itself after that.")
                }
            }
            .navigationTitle("Link with a Mac")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            remote.startLookingForPairing()
            if let linkTo, remote.macsAround.contains(linkTo), !remote.pairedMacs.contains(linkTo) { link(linkTo) }
        }
        .onDisappear { remote.stopLookingForPairing() }
        .onChange(of: macWithCode) { mac in
            code = ""
            codeFocused = mac != nil
        }
        .onChange(of: remote.pairedMacs) { macs in
            if let mac = macWithCode, macs.contains(mac) { code = "" }
        }
        .onChange(of: remote.macsAround) { macs in
            // Opened from the picture before the Mac was found: ask once it is.
            if let linkTo, macs.contains(linkTo), remote.codeShownOn == nil, remote.requestingCode == nil,
               !remote.pairedMacs.contains(linkTo), remote.pairingMessage == nil {
                link(linkTo)
            }
        }
    }

    private func link(_ mac: String) {
        code = ""
        remote.requestCode(from: mac, as: settings.receiverName)
    }
}
