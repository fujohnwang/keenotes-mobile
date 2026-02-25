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

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isOffline = new AtomicBoolean(false);

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

    // 重连定时器
    private ScheduledExecutorService reconnectScheduler;
    private ScheduledFuture<?> reconnectTask;

    // 心跳
    private static final int HEARTBEAT_INTERVAL_SEC = 30;
    private static final int HEARTBEAT_TIMEOUT_SEC = 75; // 超过此时间没收到任何消息则判定为僵尸连接
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;
    private volatile long lastMessageTime = 0;

    // 回调监听器
    private final List<SyncListener> listeners = new CopyOnWriteArrayList<>();

    // 批量同步进度追踪
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
                .proxySelector(new java.net.ProxySelector() {
                    @Override
                    public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                        return java.util.Collections.singletonList(java.net.Proxy.NO_PROXY);
                    }
                    @Override
                    public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
                })
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS));

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
            logger.info("Adding Authorization header: Bearer " + authToken.substring(0, Math.min(4, authToken.length()))
                    + "...");
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
                    logger.info("WebSocket closed (shutdown): " + code + " " + reason);
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
                    logger.info("WebSocket failure during shutdown: " + t.getMessage());
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
        if (webSocket == null)
            return;

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
     * 手动重连 - 用户主动触发，重置重试计数器后发起连接
     */
    public void manualReconnect() {
        if (isShuttingDown.get()) {
            return;
        }
        reconnectAttempts = 0;
        isOffline.set(false);
        connect();
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
        // 如果正在关闭，忽略所有消息
        if (isShuttingDown.get()) {
            logger.fine("Ignoring message during shutdown: " + message);
            return;
        }

        try {
            logger.info("Received message: " + message);

            // 每次收到消息都更新时间戳，用于心跳超时检测
            lastMessageTime = System.currentTimeMillis();

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
                case "ping":
                    // 服务器发送的应用层ping，我们回复pong
                    // 这样可以兼容服务器的心跳机制
                    if (webSocket != null) {
                        webSocket.send("{\"type\":\"pong\"}");
                        logger.fine("Responded to server ping with pong");
                    }
                    break;
                case "pong":
                    // 服务器对我们ping的响应，lastMessageTime已在上面更新
                    logger.fine("Received pong from server");
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

        int batchId = json.getInteger("batch_id", 0);
        int totalBatches = json.getInteger("total_batches", 1);

        if (expectedBatches == 0) {
            expectedBatches = totalBatches;
        }

        JsonArray notes = json.getJsonArray("notes");
        if (notes != null) {
            List<LocalCacheService.NoteData> batchNotes = new ArrayList<>();
            long maxNoteId = -1;

            for (int i = 0; i < notes.size(); i++) {
                JsonObject note = notes.getJsonObject(i);
                try {
                    long id = note.getLong("id");
                    String encryptedContent = note.getString("content");
                    String channel = note.getString("channel");
                    String createdAt = note.getString("created_at");

                    String decryptedContent;
                    try {
                        decryptedContent = cryptoService.decrypt(encryptedContent);
                    } catch (Exception e) {
                        logger.warning("Failed to decrypt note " + id + ": " + e.getMessage() + ", storing encrypted content");
                        decryptedContent = encryptedContent;
                    }
                    
                    batchNotes.add(new LocalCacheService.NoteData(
                            id, decryptedContent, channel, createdAt, encryptedContent));
                    if (id > maxNoteId) {
                        maxNoteId = id;
                    }
                } catch (Exception e) {
                    logger.warning("Failed to parse note: " + e.getMessage());
                }
            }

            // 立即写入 DB（增量持久化）
            if (!batchNotes.isEmpty()) {
                try {
                    localCache.batchInsertNotes(batchNotes);
                    logger.info("Batch " + batchId + ": inserted " + batchNotes.size() + " notes to DB");
                } catch (Exception e) {
                    logger.warning("Failed to insert batch " + batchId + ": " + e.getMessage());
                }
            }

            // 更新 last_sync_id 为该 batch 中最大的 note ID（断点续传）
            if (maxNoteId > 0) {
                try {
                    localCache.updateLastSyncId(maxNoteId);
                    this.lastSyncId = maxNoteId;
                    logger.info("Batch " + batchId + ": updated lastSyncId to " + maxNoteId);
                } catch (Exception e) {
                    logger.warning("Failed to update lastSyncId after batch " + batchId + ": " + e.getMessage());
                }
            }
        }

        receivedBatches++;
        notifySyncProgress(receivedBatches, totalBatches);
        logger.info("Received batch " + batchId + "/" + totalBatches);
    }

    private void handleSyncComplete(JsonObject json) {
        int totalSynced = json.getInteger("total_synced", 0);
        long newLastSyncId = json.getLong("last_sync_id", -1L);

        try {
            // 以服务器返回的 last_sync_id 为准做最终更新
            if (totalSynced > 0 && newLastSyncId > 0) {
                localCache.updateLastSyncId(newLastSyncId);
                this.lastSyncId = newLastSyncId;
                logger.info("Updated lastSyncId to: " + newLastSyncId);
            }
        } catch (Exception e) {
            logger.warning("Failed to update lastSyncId on sync complete: " + e.getMessage());
        }

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

            String decryptedContent;
            try {
                decryptedContent = cryptoService.decrypt(encryptedContent);
                logger.info("Decrypted content: " + decryptedContent);
            } catch (Exception e) {
                logger.warning("Failed to decrypt note " + id + ": " + e.getMessage() + ", storing encrypted content");
                decryptedContent = encryptedContent;
            }

            LocalCacheService.NoteData note = new LocalCacheService.NoteData(
                    id, decryptedContent, channel, createdAt, encryptedContent);
            localCache.insertNote(note);

            // 关键修复：实时同步后更新lastSyncId，避免下次重复同步
            if (id > lastSyncId) {
                lastSyncId = id;
                localCache.updateLastSyncId(lastSyncId);
                logger.info("Updated lastSyncId to " + lastSyncId + " after realtime update");
            }

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
     * 启动应用层心跳：每30秒发一次ping，超过75秒没收到任何消息则判定僵尸连接并重连
     */
    private void startHeartbeat() {
        stopHeartbeat();
        lastMessageTime = System.currentTimeMillis();

        if (heartbeatScheduler == null || heartbeatScheduler.isShutdown()) {
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebSocket-Heartbeat");
                t.setDaemon(true);
                return t;
            });
        }

        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown.get() || !isConnected.get()) return;

            long silentMs = System.currentTimeMillis() - lastMessageTime;
            if (silentMs > HEARTBEAT_TIMEOUT_SEC * 1000L) {
                logger.warning("Heartbeat timeout (" + silentMs + "ms silent), forcing reconnect...");
                forceReconnect();
                return;
            }

            // 发送应用层ping
            WebSocket ws = webSocket;
            if (ws != null) {
                boolean sent = ws.send("{\"type\":\"ping\"}");
                logger.fine("Sent heartbeat ping (silent " + silentMs + "ms)");
                if (!sent) {
                    logger.warning("Failed to send heartbeat ping, forcing reconnect...");
                    forceReconnect();
                }
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        logger.info("Heartbeat started");
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    /**
     * 强制断开并触发重连（用于僵尸连接检测）
     */
    private void forceReconnect() {
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.cancel();
            webSocket = null;
        }
        isConnected.set(false);
        isConnecting.set(false);
        notifyConnectionStatus(false);
        cleanup();
        scheduleReconnect();
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
            logger.warning("Max reconnect attempts reached, entering offline mode");
            isOffline.set(true);
            notifyOffline();
            return;
        }

        // 取消之前的重连任务
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }

        int delay = RECONNECT_BASE_DELAY_MS * (int) Math.pow(2, reconnectAttempts);
        reconnectAttempts++;

        logger.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + "/"
                + MAX_RECONNECT_ATTEMPTS + ")");

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

        expectedBatches = 0;
        receivedBatches = 0;
        isConnected.set(false);
        isConnecting.set(false);
    }

    /**
     * 完全关闭服务 - 立即返回，强制断开连接
     * 优化策略：先关闭底层OkHttp资源，使用cancel()而非close()避免等待
     */
    public void shutdown() {
        logger.info("Starting immediate shutdown...");

        // 1. 立即设置关闭标志（阻止新操作）
        isShuttingDown.set(true);
        isConnected.set(false);

        // 2. 立即取消定时任务（不等待）
        stopHeartbeat();
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }

        if (reconnectTask != null) {
            reconnectTask.cancel(true); // 中断正在执行的任务
            reconnectTask = null;
        }

        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }

        // 3. 立即强制关闭OkHttp底层资源（这会强制断开所有连接）
        if (httpClient != null) {
            try {
                // 先shutdownNow调度器，立即中断所有网络操作
                httpClient.dispatcher().executorService().shutdownNow();

                // 立即驱逐所有连接（强制关闭底层Socket）
                httpClient.connectionPool().evictAll();

                // 关闭缓存
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }

                logger.info("OkHttp resources closed");
            } catch (Exception e) {
                logger.warning("OkHttp cleanup error: " + e.getMessage());
            }
            httpClient = null;
        }

        // 4. 清空WebSocket引用，使用cancel()立即断开，不等待close frame
        if (webSocket != null) {
            try {
                // 使用cancel()代替close()，立即断开连接不等待
                webSocket.cancel();
                logger.info("WebSocket cancelled immediately");
            } catch (Exception e) {
                // 忽略异常，底层连接可能已被OkHttp关闭
            }
            webSocket = null;
        }

        isInitialized.set(false);
        logger.info("Shutdown completed");
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

    private void notifyOffline() {
        listeners.forEach(SyncListener::onOffline);
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public boolean isOffline() {
        return isOffline.get();
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

        /** 重连耗尽后进入离线状态 */
        default void onOffline() {}
    }

    /**
     * 简单日志包装
     */
    static class Logger {
        public static Logger getLogger(String name) {
            return new Logger();
        }

        public void info(String msg) {
            System.out.println("[INFO] " + msg);
        }

        public void warning(String msg) {
            System.err.println("[WARN] " + msg);
        }

        public void severe(String msg) {
            System.err.println("[ERROR] " + msg);
        }

        public void fine(String msg) {
            /* debug level, skip */ }
    }
}
