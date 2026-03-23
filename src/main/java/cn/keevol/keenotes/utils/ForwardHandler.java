package cn.keevol.keenotes.utils;

import cn.keevol.keenotes.mobilefx.ApiServiceV2;
import cn.keevol.keenotes.mobilefx.ServiceManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ForwardHandler implements HttpHandler {

    private final ApiServiceV2 serviceManager = ServiceManager.getInstance().getApiService();

    public ForwardHandler() {

    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 只处理 POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            // 1. 读取本地请求体
            InputStream is = exchange.getRequestBody();
            byte[] requestBody = is.readAllBytes();
            // 注意：JDK原生流操作记得手动关，或者由HttpExchange处理，readAllBytes方便但要注意内存

            JsonObject json = new JsonObject(new String(requestBody, StandardCharsets.UTF_8));
            String content = json.getString("content");
            String channel = json.getString("channel");
            String ts = normalizeDate(json.getString("created_at"));
            
            // 2. Check if data is already encrypted
            Boolean encrypted = json.getBoolean("encrypted", false);
            
            CompletableFuture<ApiServiceV2.ApiResult> future;
            if (encrypted) {
                // Data is already encrypted, send directly without E2EE
                future = serviceManager.postNoteDirectly(content, channel, ts);
            } else {
                // Data is not encrypted, use E2EE encryption
                future = serviceManager.postNote(content, channel, ts);
            }
            
            ApiServiceV2.ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.success()) {
                exchange.sendResponseHeaders(200, 0);
            } else {
                throw new Exception(result.message());
            }

        } catch (Exception e) {
            // 发生异常，返回 500
            String errorMsg = "Forward Error: " + e.getMessage();
            exchange.sendResponseHeaders(500, errorMsg.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errorMsg.getBytes());
            }
        } finally {
            exchange.close(); // 必须关闭 exchange
        }
    }

    /**
     * Standardize date string to "yyyy-MM-dd HH:mm:ss"
     */
    private String normalizeDate(String input) {
        if (input == null || input.isBlank()) return null;
        
        try {
            // 1. Try ISO-8601 (T and Z)
            java.time.OffsetDateTime odt;
            try {
                odt = java.time.OffsetDateTime.parse(input);
            } catch (java.time.format.DateTimeParseException e) {
                // Try ISO_INSTANT or other ISO variations
                odt = java.time.Instant.parse(input).atOffset(java.time.ZoneOffset.UTC);
            }
            return odt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            // 2. Try standard SQLite format (yyyy-MM-dd HH:mm:ss) or fallback
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(input.replace(" ", "T"));
                return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                // If all fails, return raw input as last resort (SQLite might handle it)
                return input;
            }
        }
    }
}