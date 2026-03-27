package cn.keevol.keenotes.util

/**
 * Zero-width character steganography utility.
 * Encodes a secret message into invisible Unicode characters that can be
 * prepended to visible text for traceability when sharing/copying.
 */
object ZeroWidthSteganography {

    private const val START_MARKER = "\u200B" // Zero Width Space
    private const val ZERO_CHAR = "\u200C"    // Zero Width Non-Joiner
    private const val ONE_CHAR = "\u2063"     // Invisible Separator
    private const val END_MARKER = "\u200D"   // Zero Width Joiner

    /** Encode a message into a zero-width character string. */
    fun encode(message: String): String {
        val hidden = StringBuilder()
        for (byte in message.toByteArray(Charsets.UTF_8)) {
            for (i in 7 downTo 0) {
                hidden.append(if ((byte.toInt() shr i) and 1 == 0) ZERO_CHAR else ONE_CHAR)
            }
        }
        return START_MARKER + hidden + END_MARKER
    }

    /** Decode a zero-width encoded message from text. Returns null if no payload found. */
    fun decode(text: String): String? {
        val startIdx = text.indexOf(START_MARKER)
        if (startIdx < 0) return null
        val endIdx = text.indexOf(END_MARKER, startIdx + START_MARKER.length)
        if (endIdx < 0) return null

        val payload = text.substring(startIdx + START_MARKER.length, endIdx)
        val binary = StringBuilder()
        for (ch in payload) {
            when (ch.toString()) {
                ZERO_CHAR -> binary.append('0')
                ONE_CHAR -> binary.append('1')
            }
        }

        val bytes = ByteArray(binary.length / 8) { i ->
            binary.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Insert the encoded hidden message after the first character of the content.
     * Returns original content unchanged if hiddenMessage is empty or content is empty.
     */
    fun embedIfNeeded(content: String, hiddenMessage: String): String {
        val trimmed = hiddenMessage.trim()
        if (trimmed.isEmpty() || content.isEmpty()) return content
        return content[0] + encode(trimmed) + content.substring(1)
    }
}
