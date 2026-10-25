import SwiftUI
import UIKit

/// Pairing with the Mac helper: the Mac shows a code, this asks for it.
struct PairMacView: View {
    @EnvironmentObject private var remote: RemoteControl
    @EnvironmentObject private var settings: Settings
    @Environment(\.dismiss) private var dismiss
    @State private var selected: String?
    @State private var code = ""
    @State private var linkCopied = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("AirPlay only sends the Mac’s picture here, so to click and type on the Mac, a small app on the Mac does it for this iPad: Mango Cast Remote.")
                    ShareLink(item: ReleaseFeed.macRemoteDownload) {
                        Label("Send the download link to your Mac", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        UIPasteboard.general.url = ReleaseFeed.macRemoteDownload
                        linkCopied = true
                    } label: {
                        Label(linkCopied ? "Link copied" : "Copy the download link", systemImage: linkCopied ? "checkmark" : "doc.on.doc")
                    }
                    Text(verbatim: ReleaseFeed.macRemoteDownload.absoluteString)
                        .font(.footnote.monospaced())
                        .foregroundColor(.secondary)
                        .textSelection(.enabled)
                } header: {
                    Text("1. Get Mango Cast Remote on the Mac")
                } footer: {
                    Text("AirDrop the link to the Mac (or open it there), open the zip and move MangoCastRemote to Applications. The first time, Control-click it and choose Open, then allow it under System Settings → Privacy & Security → Accessibility when it asks: that’s what lets it click and type.")
                }

                Section {
                    Text("On the Mac, click Mango Cast Remote in the menu bar (the pointer icon) and choose Pair an iPad. It shows a 6-digit code.")
                } header: {
                    Text("2. Show a code on the Mac")
                }

                Section("3. Pick the Mac and enter the code") {
                    if remote.macsPairing.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Looking…")
                                .foregroundColor(.secondary)
                        }
                    }
                    ForEach(remote.macsPairing, id: \.self) { mac in
                        Button {
                            selected = mac
                        } label: {
                            HStack {
                                Label(mac, systemImage: "desktopcomputer")
                                    .foregroundColor(.primary)
                                Spacer()
                                if selected == mac { Image(systemName: "checkmark").foregroundColor(Theme.mango) }
                            }
                        }
                    }
                }

                if let mac = selected ?? (remote.macsPairing.count == 1 ? remote.macsPairing.first : nil) {
                    Section("Code shown on \(mac)") {
                        TextField("123 456", text: $code)
                            .keyboardType(.numberPad)
                            .textContentType(.oneTimeCode)
                            .font(.system(.title2, design: .monospaced))
                        Button {
                            remote.pair(with: mac, code: code, as: settings.receiverName)
                        } label: {
                            HStack {
                                Text("Pair")
                                if remote.pairing { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(remote.pairing || code.filter(\.isNumber).count != 6)
                    }
                }

                if let message = remote.pairingMessage {
                    Section {
                        Text(message)
                    }
                }

                if !remote.pairedMacs.isEmpty {
                    Section("Paired") {
                        ForEach(remote.pairedMacs, id: \.self) { mac in
                            Label(mac, systemImage: "checkmark.circle.fill")
                        }
                    }
                }
            }
            .navigationTitle("Pair with a Mac")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { remote.startLookingForPairing() }
        .onDisappear { remote.stopLookingForPairing() }
        .onChange(of: remote.pairedMacs) { macs in
            if let mac = selected ?? remote.macsPairing.first, macs.contains(mac), !remote.pairing { code = "" }
        }
    }
}
