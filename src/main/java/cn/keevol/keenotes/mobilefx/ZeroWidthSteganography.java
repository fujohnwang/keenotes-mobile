package cn.keevol.keenotes.mobilefx;

import java.nio.charset.StandardCharsets;

/**
 * Zero-width character steganography utility.
 * Encodes a secret message into invisible Unicode characters that can be
 * prepended to visible text for traceability when sharing/copying.
 */
public class ZeroWidthSteganography {

    private static final String START_MARKER = "\u200B"; // Zero Width Space
    private static final String ZERO_CHAR = "\u200C";    // Zero Width Non-Joiner
    private static final String ONE_CHAR = "\u2063";     // Invisible Separator
    private static final String END_MARKER = "\u200D";   // Zero Width Joiner

    /**
     * Encode a message into a zero-width character string.
     */
    public static String encode(String message) {
        StringBuilder hidden = new StringBuilder();
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                hidden.append(((b >> i) & 1) == 0 ? ZERO_CHAR : ONE_CHAR);
            }
        }
        return START_MARKER + hidden + END_MARKER;
    }

    /**
     * Decode a zero-width encoded message from text. Returns null if no payload found.
     */
    public static String decode(String text) {
        int startIdx = text.indexOf(START_MARKER);
        if (startIdx < 0) return null;
        int endIdx = text.indexOf(END_MARKER, startIdx + START_MARKER.length());
        if (endIdx < 0) return null;

        String payload = text.substring(startIdx + START_MARKER.length(), endIdx);
        StringBuilder binary = new StringBuilder();
        for (int i = 0; i < payload.length(); i++) {
            String ch = String.valueOf(payload.charAt(i));
            if (ZERO_CHAR.equals(ch)) binary.append('0');
            else if (ONE_CHAR.equals(ch)) binary.append('1');
        }

        byte[] result = new byte[binary.length() / 8];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(binary.substring(i * 8, i * 8 + 8), 2);
        }
        return new String(result, StandardCharsets.UTF_8);
    }

    /**
     * Insert the encoded hidden message after the first character of the content.
     * Returns original content unchanged if hiddenMessage is empty or content is empty.
     */
    public static String embedIfNeeded(String content, String hiddenMessage) {
        if (hiddenMessage == null || hiddenMessage.trim().isEmpty() || content == null || content.isEmpty()) {
            return content;
        }
        String payload = encode(hiddenMessage.trim());
        return content.charAt(0) + payload + content.substring(1);
    }
}
