package cn.keevol.keenotes.mobilefx;

import java.util.regex.Pattern;

/** Last-resort redaction for secrets accidentally included in log messages. */
final class SensitiveLogRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern JSON_SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\"(?:authorization|token|password|secret|api[_-]?key|content|text)\"\\s*:\\s*\")((?:\\\\.|[^\"\\\\])*)(\")");
    private static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile(
            "(?i)\\b(authorization|token|password|secret|api[_-]?key|content|text)\\s*[:=]\\s*(?:Bearer\\s+)?([^\\s,;}&]+)");
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_QUERY = Pattern.compile(
            "(?i)((?:https?|wss?)://[^\\s?]+)\\?[^\\s,}]+");

    private SensitiveLogRedactor() {
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = JSON_SENSITIVE_FIELD.matcher(text).replaceAll("$1" + REDACTED + "$3");
        redacted = SENSITIVE_KEY_VALUE.matcher(redacted).replaceAll("$1=" + REDACTED);
        redacted = BEARER_VALUE.matcher(redacted).replaceAll("Bearer " + REDACTED);
        return URL_QUERY.matcher(redacted).replaceAll("$1?" + REDACTED);
    }
}
