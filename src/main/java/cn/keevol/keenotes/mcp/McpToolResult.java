package cn.keevol.keenotes.mcp;

import java.util.List;

/**
 * Simple data class for MCP tool execution result.
 * Replaces io.modelcontextprotocol.spec.McpSchema.CallToolResult
 */
public record McpToolResult(
    List<ContentItem> content,
    boolean isError
) {
    
    /**
     * Content item in the result
     */
    public record ContentItem(
        String type,
        String text
    ) {
        public static ContentItem text(String text) {
            return new ContentItem("text", text);
        }
    }
}
