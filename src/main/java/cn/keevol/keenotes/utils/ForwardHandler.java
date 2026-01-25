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
            String ts = json.getString("ts");
            
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


            // 2. 构建转发请求 (同步阻塞)
//            HttpRequest forwardRequest = HttpRequest.newBuilder()
//                    .uri(URI.create(TARGET_URL))
//                    .header("Content-Type", "application/json") // 根据实际情况调整 Header
//                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
//                    .timeout(Duration.ofSeconds(10)) // 读取超时
//                    .build();
//
//            // 3. 发送并等待结果 (Blocking)
//            // 这里会阻塞住唯一的那个线程，直到目标返回，正好符合你的“等待前一个结束”的需求
//            HttpResponse<String> targetResponse = client.send(forwardRequest, HttpResponse.BodyHandlers.ofString());
//
//            // 4. 将目标服务器的结果返回给本地客户端
//            String responseBody = targetResponse.body();
//            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
//
//            // 发送响应头 (200 OK)
//            exchange.sendResponseHeaders(200, responseBytes.length);
//
//            // 写入响应体
//            try (OutputStream os = exchange.getResponseBody()) {
//                os.write(responseBytes);
//            }
//
//            System.out.println("<<< Forward success: " + responseBody);

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
}