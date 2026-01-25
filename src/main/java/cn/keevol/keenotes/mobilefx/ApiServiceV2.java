package cn.keevol.keenotes.mobilefx;

import okhttp3.*;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * API Service V2 - 只保留POST功能
 * 使用 OkHttp（与 WebSocket 统一）
 */
public class ApiServiceV2 {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final SettingsService settings;
    private final CryptoService cryptoService;

    public ApiServiceV2() {
        this.httpClient = createClient();
        this.settings = SettingsService.getInstance();
        this.cryptoService = new CryptoService();
    }

    private OkHttpClient createClient() {
        try {
            // 信任所有证书
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAll[0])
                    .hostnameVerifier((h, s) -> true)
                    .build();
        } catch (Exception e) {
            System.err.println("[ApiServiceV2] SSL config failed: " + e.getMessage());
            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
    }

    public record ApiResult(boolean success, String message, String echoContent, Long noteId) {
        public static ApiResult success(String echoContent, Long noteId) {
            return new ApiResult(true, "Note saved successfully!", echoContent, noteId);
        }
        public static ApiResult failure(String message) {
            return new ApiResult(false, message, null, null);
        }
    }

    public CompletableFuture<ApiResult> postNote(String content) {
        return postNote(content, getDefaultChannel());
    }

    public CompletableFuture<ApiResult> postNote(String content, String channel) {
        return postNote(content, channel, LocalDateTime.now().format(TS_FORMATTER));
    }

    public CompletableFuture<ApiResult> postNote(String content, String channel, String ts) {
        String endpointUrl = settings.getEndpointUrl();
        String token = settings.getToken();

        if (endpointUrl == null || endpointUrl.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Endpoint URL not configured."));
        }
        if (token == null || token.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Token not configured."));
        }
        if (content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Note content cannot be empty."));
        }
        if (!cryptoService.isEncryptionEnabled()) {
            return CompletableFuture.completedFuture(ApiResult.failure("PIN code not set."));
        }

        final String originalContent = content;

        return CompletableFuture.supplyAsync(() -> {
            try {
                String encrypted = cryptoService.encrypt(content);
                String json = String.format(
                    "{\"channel\":\"%s\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":true}",
                    channel, escapeJson(encrypted), ts
                );

                Request request = new Request.Builder()
                        .url(endpointUrl)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "";
                        return ApiResult.success(originalContent, parseNoteId(body));
                    } else {
                        return ApiResult.failure("Server error: " + response.code());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                return ApiResult.failure("Network error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Get default channel name based on platform
     * Format: desktop-{os} (e.g., desktop-mac, desktop-win, desktop-linux)
     */
    private String getDefaultChannel() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String osType;
        
        if (os.contains("mac") || os.contains("darwin")) {
            osType = "mac";
        } else if (os.contains("win")) {
            osType = "win";
        } else if (os.contains("nux") || os.contains("nix")) {
            osType = "linux";
        } else {
            osType = "unknown";
        }
        
        return "desktop-" + osType;
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

    private Long parseNoteId(String body) {
        try {
            int i = body.indexOf("\"id\":");
            if (i == -1) return null;
            int start = i + 5;
            while (start < body.length() && !Character.isDigit(body.charAt(start))) start++;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
            return Long.parseLong(body.substring(start, end));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isConfigured() {
        return settings.getEndpointUrl() != null && !settings.getEndpointUrl().isBlank()
                && settings.getToken() != null && !settings.getToken().isBlank();
    }

    public boolean isEncryptionEnabled() {
        return cryptoService.isEncryptionEnabled();
    }
    
    /**
     * Post note directly without encryption (for importing already encrypted data)
     * This method should ONLY be used by DataImportService
     */
    public CompletableFuture<ApiResult> postNoteDirectly(String encryptedContent, String channel, String ts) {
        String endpointUrl = settings.getEndpointUrl();
        String token = settings.getToken();

        if (endpointUrl == null || endpointUrl.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Endpoint URL not configured."));
        }
        if (token == null || token.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Token not configured."));
        }
        if (encryptedContent == null || encryptedContent.isBlank()) {
            return CompletableFuture.completedFuture(ApiResult.failure("Note content cannot be empty."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = String.format(
                    "{\"channel\":\"%s\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":true}",
                    channel, escapeJson(encryptedContent), ts
                );

                Request request = new Request.Builder()
                        .url(endpointUrl)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "";
                        return ApiResult.success(encryptedContent, parseNoteId(body));
                    } else {
                        return ApiResult.failure("Server error: " + response.code());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                return ApiResult.failure("Network error: " + e.getMessage());
            }
        });
    }
}
