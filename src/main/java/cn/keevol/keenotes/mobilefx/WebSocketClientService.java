package cn.keevol.keenotes.mobilefx;

import okhttp3.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket客户端服务 - 基于OkHttp实现
 * 处理与服务器的实时同步：连接管理、数据同步、心跳和重连
 */
public class WebSocketClientService {
    private static final Logger logger = Logger.getLogger(WebSocketClientService.class.getName());

    private volatile OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile Call healthCheckCall; // 用于心跳的HTTP健康检查调用

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    // 服务依赖
    private final LocalCacheService localCache;
    private final CryptoService cryptoService;
    private final SettingsService settings;

    // 同步状态
    private long lastSyncId = -1;
    private String clientId;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int RECONNECT_BASE_DELAY_MS = 1000;

    // 心跳定时器
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledExecutorService reconnectScheduler;
    private ScheduledFuture<?> reconnectTask;

    // 回调监听器
    private final List<SyncListener> listeners = new CopyOnWriteArrayList<>();

    // 批量同步临时存储
    private List<LocalCacheService.NoteData> syncBatchBuffer = new ArrayList<>();
    private int expectedBatches = 0;
    private int receivedBatches = 0;

    public WebSocketClientService() {
        this.localCache = LocalCacheService.getInstance();
        this.cryptoService = new CryptoService();
        this.settings = SettingsService.getInstance();
        this.clientId = generateClientId();
        // 不在构造函数中初始化OkHttp，延迟到第一次连接时
    }

    /**
     * 延迟初始化OkHttp - 只在第一次连接时调用
     */
    private synchronized void ensureInitialized(boolean ssl) {
        if (isInitialized.get() || isShuttingDown.get()) {
            return;
        }

        logger.info("Initializing OkHttp...");

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS) // 自动ping/pong支持
                .retryOnConnectionFailure(false);   // 我们自己处理重连逻辑

        if (ssl) {
            // 信任所有证书（仅用于开发/测试环境）
            // 生产环境应该使用正确的证书验证
            builder.hostnameVerifier((hostname, session) -> true);
        }

        this.httpClient = builder.build();
        isInitialized.set(true);
        logger.info("OkHttp initialized");
    }

    /**
     * 连接到WebSocket服务器
     */
    public void connect() {
        if (isShuttingDown.get()) {
            return;
        }

        if (isConnected.get() || isConnecting.get()) {
            logger.info("Already connected or connecting");
            return;
        }

        if (!isConnecting.compareAndSet(false, true)) {
            return;
        }

        String wsUrl = settings.getEndpointUrl();
        if (wsUrl == null || wsUrl.isEmpty()) {
            logger.warning("WebSocket URL not configured");
            isConnecting.set(false);
            return;
        }

        // 解析URL
        String host;
        int port;
        boolean ssl;
        String path = "/ws";

        try {
            java.net.URI uri = new java.net.URI(wsUrl);
            host = uri.getHost();
            ssl = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme());
            port = uri.getPort();
            if (port == -1) {
                port = ssl ? 443 : 80;
            }
            if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
                path = uri.getPath();
                if (!path.endsWith("/ws")) {
                    path = path + "/ws";
                }
            }
        } catch (Exception e) {
            logger.warning("Invalid URL: " + wsUrl);
            isConnecting.set(false);
            return;
        }

        // 延迟初始化OkHttp
        ensureInitialized(ssl);

        if (isShuttingDown.get()) {
            isConnecting.set(false);
            return;
        }

        lastSyncId = localCache.getLastSyncId();
        String authToken = settings.getToken();

        // 构建WebSocket URL
        String protocol = ssl ? "wss" : "ws";
        String wsRequestUrl = protocol + "://" + host + ":" + port + path;

        // 构建请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(wsRequestUrl);

        // 添加 Origin 头 - Cloudflare WebSocket 要求
        String origin = (ssl ? "https" : "http") + "://" + host;
        requestBuilder.addHeader("Origin", origin);

        // 添加 Authorization 头 - 复用 HTTP POST 的 token 认证方式
        if (authToken != null && !authToken.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + authToken);
            logger.info("Adding Authorization header: Bearer " + authToken.substring(0, Math.min(4, authToken.length())) + "...");
        } else {
            logger.warning("No auth token configured!");
        }

        logger.info("WebSocket connecting to: " + wsRequestUrl);

        // 创建WebSocket监听器
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (isShuttingDown.get()) {
                    webSocket.close(1000, "Shutdown");
                    return;
                }

                WebSocketClientService.this.webSocket = webSocket;
                isConnected.set(true);
                isConnecting.set(false);
                reconnectAttempts = 0;

                sendHandshake();
                startHeartbeat();
                notifyConnectionStatus(true);

                logger.info("WebSocket connected successfully");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleTextMessage(text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (isShuttingDown.get()) {
                    logger.info("WebSocket closed (shutdown)");
                    return;
                }
                logger.info("WebSocket closed: " + code + " " + reason);
                isConnected.set(false);
                cleanup();
                notifyConnectionStatus(false);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (isShuttingDown.get()) {
                    return;
                }
                logger.warning("WebSocket failure: " + t.getMessage());
                isConnected.set(false);
                notifyError(t.getMessage());
                cleanup();
                scheduleReconnect();
            }
        };

        // 发起WebSocket连接
        try {
            httpClient.newWebSocket(requestBuilder.build(), listener);
        } catch (Exception e) {
            logger.warning("WebSocket connection exception: " + e.getMessage());
            isConnecting.set(false);
            scheduleReconnect();
        }
    }

    private void sendHandshake() {
        if (webSocket == null) return;

        JsonObject handshake = new JsonObject()
                .put("type", "handshake")
                .put("client_id", clientId)
                .put("last_sync_id", lastSyncId);

        try {
            boolean sent = webSocket.send(handshake.encode());
            if (sent) {
                logger.info("Sent handshake with lastSyncId=" + lastSyncId);
            } else {
                logger.warning("Failed to send handshake");
            }
        } catch (Exception e) {
            logger.warning("Exception sending handshake: " + e.getMessage());
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
        }
        cleanup();
    }

    /**
     * 发送新笔记到服务器
     */
    public void sendNewNote(String content) throws Exception {
        if (!isConnected.get()) {
            throw new IllegalStateException("Not connected to server");
        }

        if (!cryptoService.isEncryptionEnabled()) {
            throw new IllegalStateException("Encryption password not set");
        }

        String encryptedContent = cryptoService.encrypt(content);

        JsonObject message = new JsonObject()
                .put("type", "new_note")
                .put("content", encryptedContent)
                .put("channel", "mobile")
                .put("timestamp", LocalDateTime.now().toString());

        boolean sent = webSocket.send(message.encode());
        if (sent) {
            logger.info("Sent new note to server");
        } else {
            throw new IllegalStateException("Failed to send message");
        }
    }

    /**
     * 处理服务器消息
     */
    private void handleTextMessage(String message) {
        try {
            logger.info("Received message: " + message);

            JsonObject json = new JsonObject(message);
            String type = json.getString("type");

            if (type == null) {
                logger.warning("Message without type: " + message);
                return;
            }

            logger.info("Processing message type: " + type);

            switch (type) {
                case "sync_batch":
                    handleSyncBatch(json);
                    break;
                case "sync_complete":
                    handleSyncComplete(json);
                    break;
                case "realtime_update":
                    handleRealtimeUpdate(json);
                    break;
                case "pong":
                    logger.fine("Received pong");
                    break;
                case "ping":
                    if (webSocket != null) {
                        webSocket.send("{\"type\":\"pong\"}");
                    }
                    break;
                case "error":
                    handleError(json);
                    break;
                case "new_note_ack":
                    handleNewNoteAck(json);
                    break;
                default:
                    logger.warning("Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.warning("Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleSyncBatch(JsonObject json) {
        isSyncing.set(true);
        notifySyncProgress(0, 1);

        int batchId = json.getInteger("batch_id", 0);
        int totalBatches = json.getInteger("total_batches", 1);

        if (expectedBatches == 0) {
            expectedBatches = totalBatches;
            syncBatchBuffer.clear();
        }

        JsonArray notes = json.getJsonArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.size(); i++) {
                JsonObject note = notes.getJsonObject(i);
                try {
                    long id = note.getLong("id");
                    String encryptedContent = note.getString("content");
                    String channel = note.getString("channel");
                    String createdAt = note.getString("created_at");

                    String decryptedContent = cryptoService.decrypt(encryptedContent);
                    syncBatchBuffer.add(new LocalCacheService.NoteData(
                            id, decryptedContent, channel, createdAt, encryptedContent
                    ));
                } catch (Exception e) {
                    logger.warning("Failed to decrypt note: " + e.getMessage());
                }
            }
        }

        receivedBatches++;
        notifySyncProgress(receivedBatches, totalBatches);
        logger.info("Received batch " + batchId + "/" + totalBatches + ", buffer size=" + syncBatchBuffer.size());
    }

    private void handleSyncComplete(JsonObject json) {
        int totalSynced = json.getInteger("total_synced", 0);
        long newLastSyncId = json.getLong("last_sync_id", -1L);

        try {
            if (!syncBatchBuffer.isEmpty()) {
                localCache.batchInsertNotes(syncBatchBuffer);
                logger.info("Batch inserted " + syncBatchBuffer.size() + " notes");
            }

            if (totalSynced > 0 && newLastSyncId > 0) {
                localCache.updateLastSyncId(newLastSyncId);
                this.lastSyncId = newLastSyncId;
                logger.info("Updated lastSyncId to: " + newLastSyncId);
            }
        } catch (Exception e) {
            logger.warning("Failed to save synced notes: " + e.getMessage());
        }

        syncBatchBuffer.clear();
        expectedBatches = 0;
        receivedBatches = 0;
        isSyncing.set(false);

        notifySyncComplete(totalSynced, newLastSyncId);
        logger.info("Sync complete: " + totalSynced + " notes");
    }

    private void handleRealtimeUpdate(JsonObject json) {
        logger.info("handleRealtimeUpdate called with json: " + json);

        JsonObject noteJson = json.getJsonObject("note");
        logger.info("note object: " + noteJson);

        if (noteJson == null) {
            logger.warning("note object is null");
            return;
        }

        try {
            long id = noteJson.getLong("id");
            String encryptedContent = noteJson.getString("content");
            String channel = noteJson.getString("channel");
            String createdAt = noteJson.getString("created_at");

            logger.info("Parsed note - id: " + id + ", channel: " + channel + ", createdAt: " + createdAt);

            String decryptedContent = cryptoService.decrypt(encryptedContent);
            logger.info("Decrypted content: " + decryptedContent);

            LocalCacheService.NoteData note = new LocalCacheService.NoteData(
                    id, decryptedContent, channel, createdAt, encryptedContent
            );
            localCache.insertNote(note);

            notifyRealtimeUpdate(id, decryptedContent);
            logger.info("Realtime update for note " + id + " completed successfully");
        } catch (Exception e) {
            logger.warning("Failed to process realtime update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleNewNoteAck(JsonObject json) {
        long id = json.getLong("id", -1L);
        boolean success = json.getBoolean("success", false);
        if (success) {
            logger.info("Server acknowledged new note with id=" + id);
        }
    }

    private void handleError(JsonObject json) {
        String errorMsg = json.getString("message", "Unknown error");
        logger.warning("Server error: " + errorMsg);
        notifyError(errorMsg);
    }

    /**
     * 启动心跳 - 使用ScheduledExecutorService
     */
    private void startHeartbeat() {
        stopHeartbeat();

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WebSocket-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (isConnected.get() && webSocket != null) {
                try {
                    webSocket.send("{\"type\":\"ping\"}");
                } catch (Exception e) {
                    logger.warning("Failed to send ping: " + e.getMessage());
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            try {
                if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warning("Heartbeat scheduler did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            heartbeatScheduler = null;
        }
    }

    /**
     * 安排重连 - 使用ScheduledExecutorService
     */
    private void scheduleReconnect() {
        // 如果正在关闭或未初始化，不要重连
        if (isShuttingDown.get() || !isInitialized.get()) {
            return;
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.warning("Max reconnect attempts reached");
            notifyError("Max reconnect attempts reached");
            return;
        }

        // 取消之前的重连任务
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }

        int delay = RECONNECT_BASE_DELAY_MS * (int) Math.pow(2, reconnectAttempts);
        reconnectAttempts++;

        logger.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");

        if (reconnectScheduler == null) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebSocket-Reconnect");
                t.setDaemon(true);
                return t;
            });
        }

        reconnectTask = reconnectScheduler.schedule(() -> {
            reconnectTask = null;
            if (!isShuttingDown.get()) {
                logger.info("Attempting reconnect...");
                connect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        stopHeartbeat();

        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }

        syncBatchBuffer.clear();
        expectedBatches = 0;
        receivedBatches = 0;
        isConnecting.set(false);
    }

    /**
     * 完全关闭服务
     */
    public void shutdown() {
        logger.info("Shutting down WebSocket service...");
        isShuttingDown.set(true);

        // 取消所有定时任务
        stopHeartbeat();

        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }

        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }

        // 关闭 WebSocket
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Shutdown");
                logger.info("WebSocket close initiated");
            } catch (Exception e) {
                // ignore
            }
            webSocket = null;
        }

        isConnected.set(false);

        // 关闭 OkHttp - 关键：需要关闭 dispatcher 的线程池
        if (httpClient != null) {
            try {
                // 关闭连接池
                httpClient.connectionPool().evictAll();

                // 关闭 dispatcher 的线程池
                httpClient.dispatcher().executorService().shutdownNow();

                // 关闭缓存（如果有）
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }
            } catch (Exception e) {
                // ignore
            }
            httpClient = null;
            logger.info("OkHttp closed");
        }

        isInitialized.set(false);
        logger.info("WebSocket service shutdown complete");
    }

    private String generateClientId() {
        return UUID.randomUUID().toString();
    }

    // 监听器管理
    public void addListener(SyncListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SyncListener listener) {
        listeners.remove(listener);
    }

    private void notifyConnectionStatus(boolean connected) {
        listeners.forEach(l -> l.onConnectionStatus(connected));
    }

    private void notifySyncProgress(int current, int total) {
        listeners.forEach(l -> l.onSyncProgress(current, total));
    }

    private void notifySyncComplete(int total, long lastSyncId) {
        listeners.forEach(l -> l.onSyncComplete(total, lastSyncId));
    }

    private void notifyRealtimeUpdate(long id, String content) {
        listeners.forEach(l -> l.onRealtimeUpdate(id, content));
    }

    private void notifyError(String message) {
        listeners.forEach(l -> l.onError(message));
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    /**
     * 同步监听器接口
     */
    public interface SyncListener {
        void onConnectionStatus(boolean connected);
        void onSyncProgress(int current, int total);
        void onSyncComplete(int total, long lastSyncId);
        void onRealtimeUpdate(long id, String content);
        void onError(String message);
    }

    /**
     * 简单日志包装
     */
    static class Logger {
        public static Logger getLogger(String name) {
            return new Logger();
        }

        public void info(String msg) { System.out.println("[INFO] " + msg); }
        public void warning(String msg) { System.err.println("[WARN] " + msg); }
        public void severe(String msg) { System.err.println("[ERROR] " + msg); }
        public void fine(String msg) { /* debug level, skip */ }
    }
}
