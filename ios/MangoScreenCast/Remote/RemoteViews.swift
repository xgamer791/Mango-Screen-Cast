import SwiftUI

/// Pairing with the Mac helper: the Mac shows a code, this asks for it.
struct PairMacView: View {
    @EnvironmentObject private var remote: RemoteControl
    @EnvironmentObject private var settings: Settings
    @Environment(\.dismiss) private var dismiss
    @State private var selected: String?
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("1. On your Mac, open Mango Cast Remote. It’s in the menu bar once it’s running.")
                    Text("2. Choose Pair an iPad. The Mac shows a 6-digit code.")
                    Text("3. Pick the Mac here and enter the code.")
                } footer: {
                    Text("Don’t have it yet? Download Mango Cast Remote for the Mac from the same page as Mango Cast, or build it in Xcode (the MangoCastRemote scheme). The first time, the Mac asks to allow it under Privacy & Security → Accessibility. Once paired, tap, swipe and type on the Mac’s picture here while it mirrors.")
                }

                Section("Macs ready to pair") {
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
