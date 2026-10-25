import XCTest

final class TypingSyncTests: XCTestCase {
    private let pad = TypingSync.pad

    func testTypingSendsWhatsNew() {
        let change = TypingSync.change(sent: "", box: pad + "h")
        XCTAssertEqual(change, TypingSync.Change(erase: 0, insert: "h", sent: "h", restorePad: false))
        XCTAssertEqual(TypingSync.change(sent: "h", box: pad + "hi").insert, "i")
    }

    func testAPickedWordReplacesWhatWasTyped() {
        // "teh" picked as "the" above the keyboard: back to "t", then "he".
        let change = TypingSync.change(sent: "teh", box: pad + "the")
        XCTAssertEqual(change.erase, 2)
        XCTAssertEqual(change.insert, "he")
    }

    func testBackspaceOnAnEmptyBoxStillReachesTheMac() {
        // The padding lost one character: one Backspace, and the padding goes back.
        let box = String(pad.dropLast())
        let change = TypingSync.change(sent: "", box: box)
        XCTAssertEqual(change.erase, 1)
        XCTAssertEqual(change.insert, "")
        XCTAssertTrue(change.restorePad)
    }

    func testBackspaceAfterTyping() {
        let change = TypingSync.change(sent: "hello", box: pad + "hell")
        XCTAssertEqual(change, TypingSync.Change(erase: 1, insert: "", sent: "hell", restorePad: false))
    }

    func testChineseAndEmoji() {
        XCTAssertEqual(TypingSync.change(sent: "", box: pad + "你好").insert, "你好")
        let emoji = TypingSync.change(sent: "👍🏽", box: pad)
        XCTAssertEqual(emoji.erase, 1)
    }

    func testKeysThatArePressedOnTheMac() {
        func press(_ usage: Int, _ characters: String = "", command: Bool = false, option: Bool = false,
                   control: Bool = false, shift: Bool = false) -> RemoteProtocol.Message? {
            TypingSync.pressedKey(usage: usage, characters: characters, command: command, option: option,
                                  control: control, shift: shift)
        }
        XCTAssertEqual(press(0x28), .key(code: 36, modifiers: []))            // Return
        XCTAssertEqual(press(0x29), .key(code: 53, modifiers: []))            // Esc
        XCTAssertEqual(press(0x2B, shift: true), .key(code: 48, modifiers: .shift)) // ⇧Tab
        XCTAssertEqual(press(0x52), .key(code: 126, modifiers: []))           // ↑
        XCTAssertEqual(press(0x3E), .key(code: 96, modifiers: []))            // F5
        XCTAssertEqual(press(0x06, "c", command: true), .key(code: 8, modifiers: .command)) // ⌘C
        XCTAssertEqual(press(0x06, "c", control: true), .key(code: 8, modifiers: .control)) // ⌃C
        XCTAssertEqual(press(0x2A, option: true), .key(code: 51, modifiers: .option))      // ⌥⌫: a word
        // Typed in the box: letters, plain Backspace, ⌘V (pastes into the box), ⌃⌥ letters.
        XCTAssertNil(press(0x04, "a"))
        XCTAssertNil(press(0x04, "a", shift: true))
        XCTAssertNil(press(0x2A))
        XCTAssertNil(press(0x19, "v", command: true))
        XCTAssertNil(press(0x04, "a", option: true, control: true))
    }
}
