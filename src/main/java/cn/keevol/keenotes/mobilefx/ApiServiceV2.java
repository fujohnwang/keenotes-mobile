package cn.keevol.keenotes.mobilefx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * API Service V2 - 只保留POST功能
 * 搜索和回顾功能已移到本地LocalCacheService
 */
public class ApiServiceV2 {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final SettingsService settings;
    private final CryptoService cryptoService;

    public ApiServiceV2() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.settings = SettingsService.getInstance();
        this.cryptoService = new CryptoService();
    }

    /**
     * Result of API call.
     */
    public record ApiResult(boolean success, String message, String echoContent, Long noteId) {
        public static ApiResult success(String echoContent, Long noteId) {
            return new ApiResult(true, "Note saved successfully!", echoContent, noteId);
        }

        public static ApiResult failure(String message) {
            return new ApiResult(false, message, null, null);
        }
    }

    /**
     * Post a note to the API asynchronously.
     * 所有内容在发送前必须加密
     */
    public CompletableFuture<ApiResult> postNote(String content) {
        String endpointUrl = settings.getEndpointUrl();
        String token = settings.getToken();

        if (endpointUrl == null || endpointUrl.isBlank()) {
            return CompletableFuture.completedFuture(
                    ApiResult.failure("Endpoint URL not configured. Please check Settings."));
        }
        if (token == null || token.isBlank()) {
            return CompletableFuture.completedFuture(
                    ApiResult.failure("Token not configured. Please check Settings."));
        }
        if (content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(
                    ApiResult.failure("Note content cannot be empty."));
        }

        try {
            // 加密内容 - 所有离开客户端的内容必须加密
            if (!cryptoService.isEncryptionEnabled()) {
                return CompletableFuture.completedFuture(
                        ApiResult.failure("Encryption password not set. Please set PIN code in Settings."));
            }

            String encryptedContent = cryptoService.encrypt(content);
            String timestamp = LocalDateTime.now().format(TS_FORMATTER);

            String jsonPayload = buildJsonPayload(encryptedContent, timestamp);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            final String originalContent = content;
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            // 解析返回的id
                            Long noteId = parseNoteId(response.body());
                            return ApiResult.success(originalContent, noteId);
                        } else {
                            return ApiResult.failure("Server error: " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> ApiResult.failure("Network error: " + ex.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    ApiResult.failure("Encryption failed: " + e.getMessage()));
        }
    }

    private String buildJsonPayload(String encryptedContent, String timestamp) {
        return String.format(
                "{\"channel\":\"mobile\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":true}",
                escapeJson(encryptedContent),
                timestamp
        );
    }

    private String escapeJson(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private Long parseNoteId(String responseBody) {
        try {
            int idStart = responseBody.indexOf("\"id\":");
            if (idStart == -1) return null;

            int valueStart = idStart + 5;
            while (valueStart < responseBody.length() && Character.isWhitespace(responseBody.charAt(valueStart))) {
                valueStart++;
            }

            int valueEnd = valueStart;
            while (valueEnd < responseBody.length() && Character.isDigit(responseBody.charAt(valueEnd))) {
                valueEnd++;
            }

            String idStr = responseBody.substring(valueStart, valueEnd);
            return Long.parseLong(idStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查API是否已配置
     */
    public boolean isConfigured() {
        String endpointUrl = settings.getEndpointUrl();
        String token = settings.getToken();
        return endpointUrl != null && !endpointUrl.isBlank() && token != null && !token.isBlank();
    }

    /**
     * 检查加密是否已启用
     */
    public boolean isEncryptionEnabled() {
        return cryptoService.isEncryptionEnabled();
    }
}