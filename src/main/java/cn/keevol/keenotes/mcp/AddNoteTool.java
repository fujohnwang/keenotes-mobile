package cn.keevol.keenotes.mcp;

import cn.keevol.keenotes.mobilefx.ApiServiceV2;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP Tool for adding notes to KeeNotes
 */
public class AddNoteTool {
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ApiServiceV2 apiService;
    
    public AddNoteTool(ApiServiceV2 apiService) {
        this.apiService = apiService;
    }
    
    /**
     * Execute the tool with given arguments
     */
    public McpToolResult execute(Map<String, Object> arguments) {
        // Extract parameters
        String content = (String) arguments.get("content");
        String channel = (String) arguments.getOrDefault("channel", "ai-assistant");
        Boolean encrypted = (Boolean) arguments.getOrDefault("encrypted", false);
        
        // Validate content
        if (content == null || content.isBlank()) {
            return new McpToolResult(
                List.of(McpToolResult.ContentItem.text("✗ Error: Note content cannot be empty")),
                true
            );
        }
        
        // Generate timestamp
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        
        try {
            CompletableFuture<ApiServiceV2.ApiResult> future;
            if (encrypted) {
                // Content is already encrypted, send directly
                future = apiService.postNoteDirectly(content, channel, timestamp);
            } else {
                // Content is plain text, use E2EE encryption
                future = apiService.postNote(content, channel, timestamp);
            }
            
            ApiServiceV2.ApiResult result = future.get();
            
            if (result.success()) {
                String message = String.format("✓ Note saved successfully to KeeNotes (channel: %s)", channel);
                return new McpToolResult(
                    List.of(McpToolResult.ContentItem.text(message)),
                    false
                );
            } else {
                String message = String.format("✗ Failed to save note: %s", result.message());
                return new McpToolResult(
                    List.of(McpToolResult.ContentItem.text(message)),
                    true
                );
            }
        } catch (Exception e) {
            String message = String.format("✗ Error saving note: %s", e.getMessage());
            return new McpToolResult(
                List.of(McpToolResult.ContentItem.text(message)),
                true
            );
        }
    }
}
