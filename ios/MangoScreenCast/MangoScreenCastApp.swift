import SwiftUI

@main
struct MangoScreenCastApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var settings: Settings
    @StateObject private var receiver: Receiver
    @StateObject private var updates = UpdateChecker()
    @StateObject private var remote = RemoteControl()

    init() {
        let settings = Settings()
        _settings = StateObject(wrappedValue: settings)
        _receiver = StateObject(wrappedValue: Receiver(settings: settings))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(receiver)
                .environmentObject(updates)
                .environmentObject(remote)
                .preferredColorScheme(.dark)
                .onAppear {
                    receiver.start()
                    updates.startChecking()
                }
        }
        .onChange(of: scenePhase) { phase in
            // Inactive (Control Center, the app switcher, a call banner)
            // keeps the server up; only going to the background stops it.
            switch phase {
            case .active:
                receiver.start()
                updates.startChecking()
            case .background:
                receiver.stop()
                updates.stopChecking()
            default:
                break
            }
        }
    }
}

enum Theme {
    static let background = Color(hex: 0x0E0D12)
    static let surface = Color(hex: 0x1B1922)
    static let textPrimary = Color(hex: 0xF4F2F7)
    static let textSecondary = Color(hex: 0xA9A4B5)
    static let mango = Color(hex: 0xFFA41B)
    static let ready = Color(hex: 0x4CD38A)
    static let waiting = Color(hex: 0xFFB547)
    static let error = Color(hex: 0xFF5C5C)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}
