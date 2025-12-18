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
    private final CryptoService cryptoService;

    public ApiService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.settings = SettingsService.getInstance();
        this.cryptoService = new CryptoService();
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
            
            // Encrypt content if encryption is enabled
            String contentToSend = content;
            boolean encrypted = false;
            if (cryptoService.isEncryptionEnabled()) {
                try {
                    contentToSend = cryptoService.encrypt(content);
                    encrypted = true;
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(
                            ApiResult.failure("Encryption failed: " + e.getMessage()));
                }
            }
            
            String jsonPayload = buildJsonPayload(contentToSend, timestamp, encrypted);

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
                            return ApiResult.success(originalContent);
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

    private String buildJsonPayload(String text, String timestamp, boolean encrypted) {
        // Simple JSON construction without external library
        return String.format(
                "{\"channel\":\"mobile\",\"text\":%s,\"ts\":\"%s\",\"encrypted\":%s}",
                escapeJson(text),
                timestamp,
                encrypted
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
     * Note record for review.
     */
    public record Note(String id, String content, String createdAt) {}

    /**
     * Check if API is configured.
     */
    private boolean isConfigured() {
        String endpointUrl = settings.getEndpointUrl();
        return endpointUrl != null && !endpointUrl.isBlank();
    }

    /**
     * Build base URL for API calls (removes trailing slash if present).
     */
    private String getBaseUrl() {
        String url = settings.getEndpointUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Search notes API - uses real API if configured, otherwise mock data.
     */
    public CompletableFuture<List<SearchResult>> searchNotes(String query) {
        if (!isConfigured()) {
            return searchNotesMock(query);
        }
        return searchNotesReal(query);
    }

    private CompletableFuture<List<SearchResult>> searchNotesReal(String query) {
        try {
            String baseUrl = getBaseUrl();
            String token = settings.getToken();
            String url = baseUrl + "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&size=100";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET();

            if (token != null && !token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }

            return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return parseSearchResults(response.body());
                        }
                        return List.<SearchResult>of();
                    })
                    .exceptionally(ex -> {
                        System.err.println("Search API error: " + ex.getMessage());
                        return List.of();
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private List<SearchResult> parseSearchResults(String json) {
        List<SearchResult> results = new ArrayList<>();
        try {
            // Simple JSON parsing without external library
            int resultsStart = json.indexOf("\"results\"");
            if (resultsStart == -1) return results;

            int arrayStart = json.indexOf("[", resultsStart);
            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return results;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            parseJsonArray(arrayContent, results, true);
        } catch (Exception e) {
            System.err.println("Parse error: " + e.getMessage());
        }
        return results;
    }

    /**
     * Get notes API - uses real API if configured, otherwise mock data.
     */
    public CompletableFuture<List<Note>> getNotes(int days) {
        if (!isConfigured()) {
            return getNotesMock(days);
        }
        return getNotesReal(days);
    }

    private CompletableFuture<List<Note>> getNotesReal(int days) {
        try {
            String baseUrl = getBaseUrl();
            String token = settings.getToken();
            String url = baseUrl + "/notes?days=" + days + "&size=100";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET();

            if (token != null && !token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }

            return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return parseNotes(response.body());
                        }
                        return List.<Note>of();
                    })
                    .exceptionally(ex -> {
                        System.err.println("Notes API error: " + ex.getMessage());
                        return List.of();
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private List<Note> parseNotes(String json) {
        List<Note> results = new ArrayList<>();
        try {
            int resultsStart = json.indexOf("\"results\"");
            if (resultsStart == -1) return results;

            int arrayStart = json.indexOf("[", resultsStart);
            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return results;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            List<SearchResult> searchResults = new ArrayList<>();
            parseJsonArray(arrayContent, searchResults, false);
            
            for (SearchResult sr : searchResults) {
                results.add(new Note(sr.id(), sr.content(), sr.createdAt()));
            }
        } catch (Exception e) {
            System.err.println("Parse error: " + e.getMessage());
        }
        return results;
    }

    private void parseJsonArray(String arrayContent, List<SearchResult> results, boolean isSearch) {
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf("{", pos);
            if (objStart == -1) break;

            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd == -1) break;

            String objStr = arrayContent.substring(objStart, objEnd + 1);
            SearchResult result = parseNoteObject(objStr);
            if (result != null) {
                results.add(result);
            }
            pos = objEnd + 1;
        }
    }

    private SearchResult parseNoteObject(String objStr) {
        String id = extractJsonString(objStr, "id");
        String content = extractJsonString(objStr, "content");
        String createdAt = extractJsonString(objStr, "createdAt");
        boolean encrypted = extractJsonBoolean(objStr, "encrypted");
        
        if (content != null) {
            // Decrypt content if encrypted and password is set
            if (encrypted && cryptoService.isEncryptionEnabled()) {
                try {
                    content = cryptoService.decrypt(content);
                } catch (Exception e) {
                    // If decryption fails, show placeholder
                    content = "[Encrypted - decryption failed]";
                }
            } else if (encrypted) {
                // Encrypted but no password set
                content = "[Encrypted - password required]";
            }
            
            // Format createdAt for display
            if (createdAt != null && createdAt.contains("T")) {
                createdAt = createdAt.replace("T", " ");
                if (createdAt.length() > 16) {
                    createdAt = createdAt.substring(0, 16);
                }
            }
            return new SearchResult(id != null ? id : "", content, createdAt != null ? createdAt : "");
        }
        return null;
    }
    
    private boolean extractJsonBoolean(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos == -1) return false;

        int colonPos = json.indexOf(":", keyPos);
        if (colonPos == -1) return false;

        // Find the boolean value after colon
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart < json.length() - 3 && json.substring(valueStart, valueStart + 4).equals("true")) {
            return true;
        }
        return false;
    }

    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos == -1) return null;

        int colonPos = json.indexOf(":", keyPos);
        if (colonPos == -1) return null;

        int valueStart = json.indexOf("\"", colonPos);
        if (valueStart == -1) return null;

        int valueEnd = findStringEnd(json, valueStart + 1);
        if (valueEnd == -1) return null;

        String value = json.substring(valueStart + 1, valueEnd);
        // Unescape JSON string
        return value.replace("\\n", "\n").replace("\\r", "\r")
                    .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private int findStringEnd(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++; // Skip escaped character
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingBracket(String json, int start) {
        if (start == -1 || json.charAt(start) != '[') return -1;
        int depth = 1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i = findStringEnd(json, i + 1);
                if (i == -1) return -1;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int start) {
        if (start == -1 || json.charAt(start) != '{') return -1;
        int depth = 1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                i = findStringEnd(json, i + 1);
                if (i == -1) return -1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ========== Mock Data Methods ==========

    private CompletableFuture<List<SearchResult>> searchNotesMock(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            List<SearchResult> mockResults = new ArrayList<>();
            String lowerQuery = query.toLowerCase();

            List<SearchResult> allNotes = List.of(
                new SearchResult("1", "Meeting Notes\n\nDiscussed project timeline and deliverables for Q1.", "2025-12-12 10:30"),
                new SearchResult("2", "Shopping List\n\n- Milk\n- Bread\n- Eggs", "2025-12-11 15:20"),
                new SearchResult("3", "Ideas for App\n\n1. Dark mode\n2. Cloud sync", "2025-12-10 20:15"),
                new SearchResult("4", "Book Recommendations\n\n- Clean Code\n- Design Patterns", "2025-12-09 14:00"),
                new SearchResult("5", "Travel Plans\n\nDestination: Tokyo", "2025-12-08 09:45")
            );

            for (SearchResult note : allNotes) {
                if (note.content().toLowerCase().contains(lowerQuery)) {
                    mockResults.add(note);
                }
            }
            return mockResults;
        });
    }

    private CompletableFuture<List<Note>> getNotesMock(int days) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            return List.of(
                new Note("1", "今天完成了项目的第一阶段开发。", "2025-12-12 10:30"),
                new Note("2", "学习了 JavaFX 的布局系统。", "2025-12-11 15:20"),
                new Note("3", "读完了《Clean Code》第三章。", "2025-12-10 20:15"),
                new Note("4", "周会讨论要点：Q1 目标确认。", "2025-12-09 14:00"),
                new Note("5", "GluonFX 打包 iOS 应用的步骤记录。", "2025-12-08 09:45")
            );
        });
    }
}
