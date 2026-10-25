import Foundation

/// Mac virtual key codes (the US layout's physical keys), and how iPad
/// input maps onto them.
enum MacKeys {
    static let returnKey: UInt16 = 36
    static let tab: UInt16 = 48
    static let space: UInt16 = 49
    static let delete: UInt16 = 51
    static let escape: UInt16 = 53
    static let forwardDelete: UInt16 = 117
    static let home: UInt16 = 115
    static let end: UInt16 = 119
    static let pageUp: UInt16 = 116
    static let pageDown: UInt16 = 121
    static let left: UInt16 = 123
    static let right: UInt16 = 124
    static let down: UInt16 = 125
    static let up: UInt16 = 126
    /// F1 to F12.
    static let function: [UInt16] = [122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111]

    /// The key that types `character` on a US keyboard, for shortcuts
    /// (⌘C) and keys that must arrive as keys (Return, Tab).
    static func code(for character: Character) -> UInt16? {
        characterCodes[Character(character.lowercased())]
    }

    private static let characterCodes: [Character: UInt16] = [
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12,
        "w": 13, "e": 14, "r": 15, "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23,
        "=": 24, "9": 25, "7": 26, "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33, "i": 34,
        "p": 35, "\n": 36, "\r": 36, "l": 37, "j": 38, "'": 39, "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44,
        "n": 45, "m": 46, ".": 47, "\t": 48, " ": 49, "`": 50,
    ]

    /// A hardware keyboard key on the iPad (its USB HID usage) as the Mac's
    /// key. Letters, digits and punctuation, plus editing, arrows and F-keys.
    static func code(forHIDUsage usage: Int) -> UInt16? {
        switch usage {
        case 0x04...0x1D: // a–z
            let letter = Character(UnicodeScalar(UInt8(ascii: "a") + UInt8(usage - 0x04)))
            return characterCodes[letter]
        case 0x1E...0x26: // 1–9
            return characterCodes[Character(String(usage - 0x1E + 1))]
        case 0x27: return characterCodes["0"]
        case 0x3A...0x45: return function[usage - 0x3A] // F1–F12
        default: return hidCodes[usage]
        }
    }

    private static let hidCodes: [Int: UInt16] = [
        0x28: returnKey, 0x29: escape, 0x2A: delete, 0x2B: tab, 0x2C: space, 0x2D: 27, 0x2E: 24, 0x2F: 33,
        0x30: 30, 0x31: 42, 0x33: 41, 0x34: 39, 0x35: 50, 0x36: 43, 0x37: 47, 0x38: 44, 0x4A: home,
        0x4B: pageUp, 0x4C: forwardDelete, 0x4D: end, 0x4E: pageDown, 0x4F: right, 0x50: left, 0x51: down,
        0x52: up, 0x58: returnKey,
    ]
}
