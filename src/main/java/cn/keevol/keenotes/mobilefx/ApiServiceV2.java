package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;
import okhttp3.*;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * API Service V2 - 只保留POST功能
 * 使用 OkHttp（与 WebSocket 统一）
 */
public class ApiServiceV2 {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ExecutorService networkExecutor;
    private final SettingsService settings;
    private final CryptoService cryptoService;

    public ApiServiceV2() {
        this.httpClient = createClient();
        this.networkExecutor = new ThreadPoolExecutor(
                0, 4, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                r -> {
                    Thread t = new Thread(r, "api-network");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
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

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAll, new SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAll[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient();
        }
    }

    public static class ApiResult {
        private final boolean success;
        private final String message;
        private final String echoContent;
        private final Long noteId;

        public ApiResult(boolean success, String message, String echoContent, Long noteId) {
            this.success = success;
            this.message = message;
            this.echoContent = echoContent;
            this.noteId = noteId;
        }

        public boolean success() { return success; }
        public String message() { return message; }
        public String echoContent() { return echoContent; }
        public Long noteId() { return noteId; }

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
        String ts = DateTimeUtil.getCurrentUtcTimestamp();
        return postNote(content, channel, ts);
    }

    public CompletableFuture<ApiResult> postNote(String content, String channel, String utcTs) {
        return postNoteInternal(content, channel, utcTs, false);
    }

    /**
     * Post with timestamp from external/import sources (may be local time or mixed formats).
     */
    public CompletableFuture<ApiResult> postNoteNormalizingTimestamp(String content, String channel, String ts) {
        return postNoteInternal(content, channel, ts, true);
    }

    private CompletableFuture<ApiResult> postNoteInternal(
            String content, String channel, String ts, boolean normalizeIncoming) {
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

        final String normalizedTs = normalizeIncoming
                ? DateTimeUtil.normalizeToUtc(ts)
                : DateTimeUtil.requireUtcStorageFormat(ts);
        final String originalContent = content;

        return CompletableFuture.supplyAsync(() -> {
            try {
                String encrypted = cryptoService.encrypt(content);
                String json = String.format(
                    "{\"channel\":\"%s\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":true}",
                    channel, escapeJson(encrypted), normalizedTs
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
        }, networkExecutor);
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

    /**
     * Release OkHttp resources: dispatcher thread pool, connection pool, and cache.
     */
    public void close() {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (Exception ignored) {
            }
        }
        networkExecutor.shutdownNow();
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
    public CompletableFuture<ApiResult> postNoteDirectly(String encryptedContent, String channel, String utcTs) {
        return postNoteDirectlyInternal(encryptedContent, channel, utcTs, false);
    }

    /**
     * Post pre-encrypted note with timestamp from external/import sources.
     */
    public CompletableFuture<ApiResult> postNoteDirectlyNormalizingTimestamp(
            String encryptedContent, String channel, String ts) {
        return postNoteDirectlyInternal(encryptedContent, channel, ts, true);
    }

    private CompletableFuture<ApiResult> postNoteDirectlyInternal(
            String encryptedContent, String channel, String ts, boolean normalizeIncoming) {
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

        final String normalizedTs = normalizeIncoming
                ? DateTimeUtil.normalizeToUtc(ts)
                : DateTimeUtil.requireUtcStorageFormat(ts);

        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = String.format(
                    "{\"channel\":\"%s\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":true}",
                    channel, escapeJson(encryptedContent), normalizedTs
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
        }, networkExecutor);
    }
}
