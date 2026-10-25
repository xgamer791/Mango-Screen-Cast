import SwiftUI
import UIKit

/// The iPad's keyboard, typing on the Mac as it's typed, the way Holli
/// Bot's Screen does it (src/ui/computer.js useKeyboard there).
///
/// It's the keyboard of a text box out of sight. Each change to what's in
/// the box goes to the Mac at once: Backspaces for what came off its end,
/// then what's new. So a word picked above the keyboard, dictation and
/// Chinese typed in pinyin come out on the Mac as they would in the box.
/// Return (and on a keyboard attached to the iPad, Tab, Esc, the arrows,
/// the F-keys and shortcuts like ⌘C) is pressed on the Mac. The box starts
/// afresh after Return, and after a tap on the screen.
enum TypingSync {
    /// Zero-width spaces the box always starts with, so Backspace has
    /// something to take (and says it was pressed) when it holds nothing else.
    static let pad = String(repeating: "\u{200B}", count: 8)

    struct Change: Equatable {
        /// Backspaces to press on the Mac.
        var erase: Int
        /// Then this to type.
        var insert: String
        /// What the box holds after its padding, as far as the Mac has it.
        var sent: String
        /// The padding was eaten into and goes back.
        var restorePad: Bool
    }

    /// What changed, from what was `sent` to what the box holds now.
    static func change(sent: String, box: String) -> Change {
        let typed = String(String.UnicodeScalarView(box.unicodeScalars.filter { $0 != "\u{200B}" }))
        let padLeft = box.unicodeScalars.count - typed.unicodeScalars.count
        let lost = max(0, pad.unicodeScalars.count - padLeft)
        let was = Array(sent)
        let now = Array(typed)
        var same = 0
        while same < was.count && same < now.count && was[same] == now[same] { same += 1 }
        return Change(erase: was.count - same + lost, insert: String(now[same...]), sent: typed, restorePad: lost > 0)
    }

    /// What a key on a keyboard attached to the iPad presses on the Mac, or
    /// nil for one that types in the box. ⌃ or ⌘ with a character is a
    /// shortcut, but not ⌃⌥ (typing a character), nor pasting, which pastes
    /// into the box and so types what was copied.
    static func pressedKey(usage: Int, characters: String, command: Bool, option: Bool, control: Bool,
                           shift: Bool) -> RemoteProtocol.Message? {
        let backspace = 0x2A
        let named: Set<Int> = [0x28, 0x58, 0x2B, 0x29, 0x4C, 0x4A, 0x4D, 0x4B, 0x4E, 0x4F, 0x50, 0x51, 0x52]
        let functionKey = (0x3A...0x45).contains(usage)
        let isNamed = named.contains(usage) || functionKey || (usage == backspace && (control || option || command))
        let shortcut = (control || command) && characters.count == 1 && !(control && option)
            && characters.lowercased() != "v"
        guard isNamed || shortcut, let code = MacKeys.code(forHIDUsage: usage) else { return nil }
        var modifiers: RemoteProtocol.Modifiers = []
        if command { modifiers.insert(.command) }
        if option { modifiers.insert(.option) }
        if control { modifiers.insert(.control) }
        if shift { modifiers.insert(.shift) }
        return .key(code: code, modifiers: modifiers)
    }
}

/// The box, shown or hidden with `isActive` (the keyboard button).
struct TypingBox: UIViewRepresentable {
    @Binding var isActive: Bool
    let send: (RemoteProtocol.Message) -> Void
    let field: TypingField

    func makeUIView(context: Context) -> TypingField {
        field.send = send
        field.onEnd = { isActive = false }
        return field
    }

    func updateUIView(_ field: TypingField, context: Context) {
        field.send = send
        field.onEnd = { isActive = false }
        DispatchQueue.main.async {
            if isActive && !field.isFirstResponder {
                field.becomeFirstResponder()
            } else if !isActive && field.isFirstResponder {
                field.resignFirstResponder()
            }
        }
    }
}

final class TypingField: UITextField, UITextFieldDelegate {
    var send: ((RemoteProtocol.Message) -> Void)?
    var onEnd: (() -> Void)?
    private var sent = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        text = TypingSync.pad
        delegate = self
        // Typing goes to another computer: what's typed is what goes.
        autocorrectionType = .no
        autocapitalizationType = .none
        spellCheckingType = .no
        smartQuotesType = .no
        smartDashesType = .no
        smartInsertDeleteType = .no
        textColor = .clear
        tintColor = .clear
        accessibilityIdentifier = "typingBox"
        accessibilityLabel = "Type on the Mac"
        addTarget(self, action: #selector(changed), for: .editingChanged)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    /// Starts afresh (unless a word is still being put together).
    func clear() {
        guard markedTextRange == nil else { return }
        sent = ""
        text = TypingSync.pad
    }

    @objc private func changed() {
        // A word still being put together (pinyin) goes when it's done.
        guard markedTextRange == nil else { return }
        let change = TypingSync.change(sent: sent, box: text ?? "")
        sent = change.sent
        if change.restorePad { text = TypingSync.pad + change.sent }
        for _ in 0..<change.erase { send?(.key(code: MacKeys.delete, modifiers: [])) }
        if !change.insert.isEmpty { send?(.text(change.insert)) }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        send?(.key(code: MacKeys.returnKey, modifiers: []))
        clear()
        return false
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        onEnd?()
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var rest = Set<UIPress>()
        for press in presses {
            guard let key = press.key, let message = TypingSync.pressedKey(
                usage: key.keyCode.rawValue, characters: key.charactersIgnoringModifiers,
                command: key.modifierFlags.contains(.command), option: key.modifierFlags.contains(.alternate),
                control: key.modifierFlags.contains(.control), shift: key.modifierFlags.contains(.shift))
            else {
                rest.insert(press)
                continue
            }
            send?(message)
            clear()
        }
        if !rest.isEmpty { super.pressesBegan(rest, with: event) }
    }
}
