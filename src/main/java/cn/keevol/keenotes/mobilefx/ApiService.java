package cn.keevol.keenotes.mobilefx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Search result record.
     */
    public record SearchResult(String id, String content, String createdAt) {}

    /**
     * Mock search notes API.
     */
    public CompletableFuture<List<SearchResult>> searchNotes(String query) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate network delay
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Mock data
            List<SearchResult> mockResults = new ArrayList<>();
            String lowerQuery = query.toLowerCase();

            // Sample mock notes (same format as Note for consistency)
            List<SearchResult> allNotes = List.of(
                new SearchResult("1", 
                    "Meeting Notes\n\nDiscussed project timeline and deliverables for Q1. Action items:\n- Complete design review\n- Update documentation\n- Schedule follow-up meeting",
                    "2025-12-12 10:30"),
                new SearchResult("2", 
                    "Shopping List\n\n- Milk\n- Bread\n- Eggs\n- Vegetables\n- Fruits",
                    "2025-12-11 15:20"),
                new SearchResult("3", 
                    "Ideas for App\n\n1. Dark mode support\n2. Cloud sync\n3. Tags and categories\n4. Export to PDF",
                    "2025-12-10 20:15"),
                new SearchResult("4", 
                    "Book Recommendations\n\n- Clean Code by Robert Martin\n- The Pragmatic Programmer\n- Design Patterns",
                    "2025-12-09 14:00"),
                new SearchResult("5", 
                    "Travel Plans\n\nDestination: Tokyo\nDates: March 15-22\nTodo:\n- Book flights\n- Reserve hotel\n- Plan itinerary",
                    "2025-12-08 09:45")
            );

            // Filter by query
            for (SearchResult note : allNotes) {
                if (note.content().toLowerCase().contains(lowerQuery)) {
                    mockResults.add(note);
                }
            }

            return mockResults;
        });
    }

    /**
     * Note record for review.
     */
    public record Note(String id, String content, String createdAt) {}

    /**
     * Mock get notes API for review.
     */
    public CompletableFuture<List<Note>> getNotes(int days) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate network delay
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Mock data - notes from past N days
            List<Note> mockNotes = new ArrayList<>();
            
            mockNotes.add(new Note("1", 
                "今天完成了项目的第一阶段开发，主要实现了用户登录和注册功能。",
                "2025-12-12 10:30"));
            mockNotes.add(new Note("2", 
                "学习了 JavaFX 的布局系统，包括 VBox、HBox、BorderPane 等容器的使用方法。",
                "2025-12-11 15:20"));
            mockNotes.add(new Note("3", 
                "读完了《Clean Code》第三章，关于函数的设计原则：\n1. 函数应该短小\n2. 只做一件事\n3. 使用描述性名称",
                "2025-12-10 20:15"));
            mockNotes.add(new Note("4", 
                "周会讨论要点：\n- Q1 目标确认\n- 资源分配\n- 风险评估",
                "2025-12-09 14:00"));
            mockNotes.add(new Note("5", 
                "GluonFX 打包 iOS 应用的步骤记录，需要配置 GRAALVM_HOME 和 Xcode。",
                "2025-12-08 09:45"));

            // In real implementation, filter by days
            return mockNotes;
        });
    }
}
