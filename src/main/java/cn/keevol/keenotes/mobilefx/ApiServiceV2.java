package cn.keevol.keenotes.mobilefx;

import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * API Service V2 - 只保留POST功能
 * 搜索和回顾功能已移到本地LocalCacheService
 * 
 * 使用 OkHttp 替代 java.net.http.HttpClient 以解决 SSL 握手问题
 */
public class ApiServiceV2 {

    private static final int TIMEOUT_SECONDS = 30;
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final SettingsService settings;
    private final CryptoService cryptoService;

    public ApiServiceV2() {
        this.httpClient = createHttpClient();
        this.settings = SettingsService.getInstance();
        this.cryptoService = new CryptoService();
    }

    /**
     * 创建配置好的 OkHttpClient，支持更宽松的 SSL/TLS 配置
     */
    private OkHttpClient createHttpClient() {
        try {
            // 创建信任所有证书的 TrustManager（开发/测试用）
            // 生产环境应该使用正确的证书验证
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };

            // 创建 SSLContext，使用 TLS 1.2/1.3
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            return new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            System.err.println("[ApiServiceV2] Failed to create SSL-enabled client, using default: " + e.getMessage());
            // 回退到默认配置
            return new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        }
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

            Request request = new Request.Builder()
                    .url(endpointUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .post(RequestBody.create(jsonPayload, JSON))
                    .build();

            final String originalContent = content;
            
            return CompletableFuture.supplyAsync(() -> {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        Long noteId = parseNoteId(responseBody);
                        return ApiResult.success(originalContent, noteId);
                    } else {
                        return ApiResult.failure("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    return ApiResult.failure("Network error: " + e.getMessage());
                }
            });
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