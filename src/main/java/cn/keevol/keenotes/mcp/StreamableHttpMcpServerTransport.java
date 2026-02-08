package cn.keevol.keenotes.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lightweight MCP Server implementation over HTTP.
 * Implements MCP JSON-RPC protocol directly without SDK transport abstractions.
 * 
 * Supports MCP protocol methods:
 * - initialize
 * - tools/list
 * - tools/call
 */
public class StreamableHttpMcpServerTransport implements HttpHandler {
    
    private final AddNoteTool addNoteTool;
    private final ObjectMapper objectMapper;
    private final String serverName;
    private final String serverVersion;
    
    public StreamableHttpMcpServerTransport(
            AddNoteTool addNoteTool,
            ObjectMapper objectMapper,
            String serverName,
            String serverVersion) {
        this.addNoteTool = addNoteTool;
        this.objectMapper = objectMapper;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only handle POST requests
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }
        
        try {
            // Read request body
            InputStream is = exchange.getRequestBody();
            byte[] requestBody = is.readAllBytes();
            String requestJson = new String(requestBody, StandardCharsets.UTF_8);
            
            System.out.println("MCP Request: " + requestJson);
            
            // Parse JSON-RPC request
            JsonNode requestNode = objectMapper.readTree(requestJson);
            String method = requestNode.path("method").asText();
            JsonNode id = requestNode.path("id");
            JsonNode params = requestNode.path("params");
            
            // Route to appropriate handler
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            
            switch (method) {
                case "initialize":
                    response.set("result", handleInitialize(params));
                    break;
                    
                case "tools/list":
                    response.set("result", handleToolsList());
                    break;
                    
                case "tools/call":
                    response.set("result", handleToolsCall(params));
                    break;
                    
                default:
                    ObjectNode error = objectMapper.createObjectNode();
                    error.put("code", -32601);
                    error.put("message", "Method not found: " + method);
                    response.set("error", error);
            }
            
            String responseJson = objectMapper.writeValueAsString(response);
            System.out.println("MCP Response: " + responseJson);
            
            sendJsonResponse(exchange, 200, responseJson);
            
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }
    
    /**
     * Handle initialize request
     */
    private ObjectNode handleInitialize(JsonNode params) {
        ObjectNode result = objectMapper.createObjectNode();
        
        // Protocol version
        result.put("protocolVersion", "2024-11-05");
        
        // Server info
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        result.set("serverInfo", serverInfo);
        
        // Capabilities
        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", false);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);
        
        return result;
    }
    
    /**
     * Handle tools/list request
     */
    private ObjectNode handleToolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        
        ArrayNode tools = objectMapper.createArrayNode();
        
        // Add note tool
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", "add_note");
        tool.put("description", "Add a new note to KeeNotes. The note content will be saved to the configured KeeNotes backend.");
        
        // Input schema
        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        
        ObjectNode properties = objectMapper.createObjectNode();
        
        ObjectNode contentProp = objectMapper.createObjectNode();
        contentProp.put("type", "string");
        contentProp.put("description", "The note content to save");
        properties.set("content", contentProp);
        
        ObjectNode channelProp = objectMapper.createObjectNode();
        channelProp.put("type", "string");
        channelProp.put("description", "Source channel (e.g., 'ai-assistant', 'claude', 'kiro'). Default is 'ai-assistant'");
        properties.set("channel", channelProp);
        
        ObjectNode encryptedProp = objectMapper.createObjectNode();
        encryptedProp.put("type", "boolean");
        encryptedProp.put("description", "Whether the note content has already been encrypted or not. Default is false");
        properties.set("encrypted", encryptedProp);
        
        inputSchema.set("properties", properties);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("content");
        inputSchema.set("required", required);
        
        tool.set("inputSchema", inputSchema);
        
        tools.add(tool);
        result.set("tools", tools);
        
        return result;
    }
    
    /**
     * Handle tools/call request
     */
    private ObjectNode handleToolsCall(JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        
        if (!"add_note".equals(toolName)) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("code", -32602);
            error.put("message", "Unknown tool: " + toolName);
            
            ObjectNode result = objectMapper.createObjectNode();
            result.set("error", error);
            return result;
        }
        
        // Convert arguments to Map
        @SuppressWarnings("unchecked")
        Map<String, Object> argsMap = objectMapper.convertValue(arguments, Map.class);
        
        // Execute tool
        McpToolResult toolResult = addNoteTool.execute(argsMap);
        
        // Convert to MCP response format
        ObjectNode result = objectMapper.createObjectNode();
        
        ArrayNode content = objectMapper.createArrayNode();
        for (McpToolResult.ContentItem item : toolResult.content()) {
            ObjectNode contentItem = objectMapper.createObjectNode();
            contentItem.put("type", item.type());
            contentItem.put("text", item.text());
            content.add(contentItem);
        }
        
        result.set("content", content);
        result.put("isError", toolResult.isError());
        
        return result;
    }
    
    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
