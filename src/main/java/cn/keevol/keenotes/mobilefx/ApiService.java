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
 * Service for interacting with the KeeNotes Web API.
 */
public class ApiService {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final SettingsService settings;

    public ApiService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.settings = SettingsService.getInstance();
    }

    /**
     * Result of API call.
     */
    public record ApiResult(boolean success, String message, String echoContent) {
        public static ApiResult success(String echoContent) {
            return new ApiResult(true, "Note saved successfully!", echoContent);
        }

        public static ApiResult failure(String message) {
            return new ApiResult(false, message, null);
        }
    }

    /**
     * Post a note to the API asynchronously.
     */
    public CompletableFuture<ApiResult> postNote(String content) {
        // Validate settings at execution time
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
            String timestamp = LocalDateTime.now().format(TS_FORMATTER);
            String jsonPayload = buildJsonPayload(content, timestamp);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return ApiResult.success(content);
                        } else {
                            return ApiResult.failure("Server error: " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> ApiResult.failure("Network error: " + ex.getMessage()));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                    ApiResult.failure("Invalid endpoint URL: " + e.getMessage()));
        }
    }

    private String buildJsonPayload(String text, String timestamp) {
        // Simple JSON construction without external library
        return String.format(
                "{\"channel\":\"mobile\",\"text\":%s,\"ts\":\"%s\"}",
                escapeJson(text),
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
}
