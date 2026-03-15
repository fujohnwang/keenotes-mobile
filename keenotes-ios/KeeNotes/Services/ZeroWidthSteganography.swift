import Foundation

/// Zero-width character steganography utility.
/// Encodes a secret message into invisible Unicode characters that can be
/// prepended to visible text for traceability when sharing/copying.
struct ZeroWidthSteganography {
    
    static let startMarker = "\u{200B}" // Zero Width Space
    static let zeroChar    = "\u{200C}" // Zero Width Non-Joiner
    static let oneChar     = "\u{2063}" // Invisible Separator
    static let endMarker   = "\u{200D}" // Zero Width Joiner
    
    /// Encode a message into a zero-width character string.
    static func encode(message: String) -> String {
        var hidden = ""
        for byte in message.utf8 {
            let binary = String(byte, radix: 2)
            let padded = String(repeating: "0", count: 8 - binary.count) + binary
            for bit in padded {
                hidden.append(bit == "0" ? zeroChar : oneChar)
            }
        }
        return startMarker + hidden + endMarker
    }
    
    /// Decode a zero-width encoded message from text. Returns nil if no payload found.
    static func decode(from text: String) -> String? {
        guard let startRange = text.range(of: startMarker),
              let endRange = text.range(of: endMarker, range: startRange.upperBound..<text.endIndex) else {
            return nil
        }
        
        let payload = text[startRange.upperBound..<endRange.lowerBound]
        var binary = ""
        for char in payload {
            let s = String(char)
            if s == zeroChar { binary.append("0") }
            else if s == oneChar { binary.append("1") }
        }
        
        var bytes: [UInt8] = []
        var idx = binary.startIndex
        while idx < binary.endIndex {
            let next = binary.index(idx, offsetBy: 8, limitedBy: binary.endIndex) ?? binary.endIndex
            if binary.distance(from: idx, to: next) == 8,
               let byte = UInt8(String(binary[idx..<next]), radix: 2) {
                bytes.append(byte)
            }
            idx = next
        }
        return String(bytes: bytes, encoding: .utf8)
    }
    
    /// Insert the encoded hidden message after the first character of the content.
    /// Returns original content unchanged if hiddenMessage is empty or content is empty.
    static func embedIfNeeded(content: String, hiddenMessage: String) -> String {
        let trimmed = hiddenMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !content.isEmpty else { return content }
        let payload = encode(message: trimmed)
        let firstIdx = content.index(after: content.startIndex)
        return String(content[content.startIndex..<firstIdx]) + payload + String(content[firstIdx...])
    }
}
