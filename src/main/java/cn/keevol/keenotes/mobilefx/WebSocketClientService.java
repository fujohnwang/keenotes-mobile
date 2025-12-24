package cn.keevol.keenotes.mobilefx;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket客户端服务 - 基于Vert.x实现
 * 处理与服务器的实时同步：连接管理、数据同步、心跳和重连
 */
public class WebSocketClientService {
    private static final Logger logger = Logger.getLogger(WebSocketClientService.class.getName());

    private volatile Vertx vertx;
    private volatile HttpClient httpClient;
    private volatile WebSocket webSocket;
    
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
    private long heartbeatTimerId = -1;
    private long reconnectTimerId = -1;

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
        // 不在构造函数中初始化 Vert.x，延迟到第一次连接时
    }

    /**
     * 延迟初始化 Vert.x - 只在第一次连接时调用
     */
    private synchronized void ensureInitialized(boolean ssl) {
        if (isInitialized.get() || isShuttingDown.get()) {
            return;
        }
        
        logger.info("Initializing Vert.x...");
        
        // 使用更简单的配置，减少后台线程
        VertxOptions vertxOptions = new VertxOptions()
                .setWorkerPoolSize(1)
                .setEventLoopPoolSize(1)
                .setBlockedThreadCheckInterval(1000)
                .setMaxEventLoopExecuteTime(2000000000L);  // 2 秒
        
        this.vertx = Vertx.vertx(vertxOptions);
        
        HttpClientOptions options = new HttpClientOptions()
                .setConnectTimeout(5000)
                .setIdleTimeout(5)
                .setReadIdleTimeout(5)
                .setWriteIdleTimeout(5)
                .setMaxPoolSize(1)
                .setKeepAlive(false);
        
        if (ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        
        this.httpClient = vertx.createHttpClient(options);
        isInitialized.set(true);
        logger.info("Vert.x initialized");
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

        // 延迟初始化 Vert.x
        ensureInitialized(ssl);
        
        if (isShuttingDown.get()) {
            isConnecting.set(false);
            return;
        }

        lastSyncId = localCache.getLastSyncId();
        String authToken = settings.getToken();

        WebSocketConnectOptions connectOptions = new WebSocketConnectOptions()
                .setHost(host)
                .setPort(port)
                .setSsl(ssl)
                .setURI(path);

        // 添加 Origin 头 - Cloudflare WebSocket 要求
        String origin = (ssl ? "https" : "http") + "://" + host;
        connectOptions.addHeader("Origin", origin);

        // 添加 Authorization 头 - 复用 HTTP POST 的 token 认证方式
        if (authToken != null && !authToken.isEmpty()) {
            connectOptions.addHeader("Authorization", "Bearer " + authToken);
            logger.info("Adding Authorization header: Bearer " + authToken.substring(0, Math.min(4, authToken.length())) + "...");
        } else {
            logger.warning("No auth token configured!");
        }

        logger.info("WebSocket connecting to: " + (ssl ? "wss" : "ws") + "://" + host + ":" + port + path);

        httpClient.webSocket(connectOptions, ar -> {
            if (ar.succeeded()) {
                webSocket = ar.result();
                isConnected.set(true);
                isConnecting.set(false);
                reconnectAttempts = 0;

                setupWebSocketHandlers();
                sendHandshake();
                startHeartbeat();
                notifyConnectionStatus(true);

                logger.info("WebSocket connected successfully");
            } else {
                logger.warning("WebSocket connection failed: " + ar.cause().getMessage());
                isConnecting.set(false);
                scheduleReconnect();
            }
        });
    }


    private void setupWebSocketHandlers() {
        webSocket.textMessageHandler(this::handleTextMessage);
        
        webSocket.closeHandler(v -> {
            if (isShuttingDown.get()) {
                logger.info("WebSocket closed (shutdown)");
                return;
            }
            logger.info("WebSocket closed");
            isConnected.set(false);
            cleanup();
            notifyConnectionStatus(false);
            scheduleReconnect();
        });

        webSocket.exceptionHandler(e -> {
            if (isShuttingDown.get()) {
                return;  // 关闭时忽略异常
            }
            logger.warning("WebSocket error: " + e.getMessage());
            isConnected.set(false);
            notifyError(e.getMessage());
            cleanup();
            scheduleReconnect();
        });
    }

    private void sendHandshake() {
        JsonObject handshake = new JsonObject()
                .put("type", "handshake")
                .put("client_id", clientId)
                .put("last_sync_id", lastSyncId);
        
        webSocket.writeTextMessage(handshake.encode());
        logger.info("Sent handshake with lastSyncId=" + lastSyncId);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close();
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

        webSocket.writeTextMessage(message.encode());
        logger.info("Sent new note to server");
    }

    /**
     * 处理服务器消息
     */
    private void handleTextMessage(String message) {
        try {
            JsonObject json = new JsonObject(message);
            String type = json.getString("type");

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
                    webSocket.writeTextMessage("{\"type\":\"pong\"}");
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
        JsonObject noteJson = json.getJsonObject("note");
        if (noteJson == null) return;

        try {
            long id = noteJson.getLong("id");
            String encryptedContent = noteJson.getString("content");
            String channel = noteJson.getString("channel");
            String createdAt = noteJson.getString("created_at");

            String decryptedContent = cryptoService.decrypt(encryptedContent);

            LocalCacheService.NoteData note = new LocalCacheService.NoteData(
                    id, decryptedContent, channel, createdAt, encryptedContent
            );
            localCache.insertNote(note);

            notifyRealtimeUpdate(id, decryptedContent);
            logger.info("Realtime update for note " + id);
        } catch (Exception e) {
            logger.warning("Failed to process realtime update: " + e.getMessage());
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
     * 启动心跳
     */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimerId = vertx.setPeriodic(30000, id -> {
            if (isConnected.get() && webSocket != null) {
                webSocket.writeTextMessage("{\"type\":\"ping\"}");
            }
        });
    }

    private void stopHeartbeat() {
        if (heartbeatTimerId != -1 && vertx != null) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
    }

    /**
     * 安排重连
     */
    private void scheduleReconnect() {
        // 如果正在关闭或未初始化，不要重连
        if (isShuttingDown.get() || !isInitialized.get() || vertx == null) {
            return;
        }
        
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.warning("Max reconnect attempts reached");
            notifyError("Max reconnect attempts reached");
            return;
        }

        if (reconnectTimerId != -1) {
            vertx.cancelTimer(reconnectTimerId);
        }

        int delay = RECONNECT_BASE_DELAY_MS * (int) Math.pow(2, reconnectAttempts);
        reconnectAttempts++;

        logger.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");

        reconnectTimerId = vertx.setTimer(delay, id -> {
            reconnectTimerId = -1;
            if (!isShuttingDown.get()) {
                logger.info("Attempting reconnect...");
                connect();
            }
        });
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        if (vertx != null && isInitialized.get()) {
            stopHeartbeat();
            if (reconnectTimerId != -1) {
                vertx.cancelTimer(reconnectTimerId);
                reconnectTimerId = -1;
            }
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
        
        // 如果从未初始化，直接返回
        if (!isInitialized.get()) {
            logger.info("Vert.x was never initialized, nothing to close");
            return;
        }
        
        // 取消定时器
        if (heartbeatTimerId != -1 && vertx != null) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
        if (reconnectTimerId != -1 && vertx != null) {
            vertx.cancelTimer(reconnectTimerId);
            reconnectTimerId = -1;
        }
        
        // 主动关闭 WebSocket 连接（发送 close frame）
        if (webSocket != null) {
            try {
                webSocket.close((short) 1000, "shutdown");
            } catch (Exception e) {
                // ignore
            }
            webSocket = null;
        }
        
        isConnected.set(false);
        
        // 先关闭 HttpClient
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                // ignore
            }
            httpClient = null;
        }
        
        // 最后关闭 Vert.x
        if (vertx != null) {
            Vertx v = vertx;
            vertx = null;
            v.close();
            logger.info("Vert.x close initiated");
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
