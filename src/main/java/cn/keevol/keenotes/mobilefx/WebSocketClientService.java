package cn.keevol.keenotes.mobilefx;

import okhttp3.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * WebSocket客户端服务 - 基于OkHttp实现
 * 处理与服务器的实时同步：连接管理、数据同步、心跳和重连
 */
public class WebSocketClientService {
    private static final Logger logger = Logger.getLogger(WebSocketClientService.class.getName());

    private volatile OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private final Object connectionLock = new Object();
    private long connectionGeneration = 0;

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
    private volatile long lastSyncId = -1;
    private final String clientId;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int RECONNECT_BASE_DELAY_MS = 1000;
    // 重连定时器
    private ScheduledExecutorService reconnectScheduler;
    private ScheduledFuture<?> reconnectTask;

    // OkHttp 使用 WebSocket 协议级 ping/pong；不再以业务消息是否到达判断连接存活。
    private static final int HEARTBEAT_INTERVAL_SEC = 30;

    // 回调监听器
    private final List<SyncListener> listeners = new CopyOnWriteArrayList<>();

    // 批量同步进度追踪
    private int expectedBatches = 0;
    private int receivedBatches = 0;
    private int pendingBatchWrites = 0;
    private boolean syncCompletionPending = false;
    private int completedSyncTotal = 0;
    private long completedSyncLastSyncId = -1;
    private boolean syncDataChanged = false;
    private boolean syncWriteFailed = false;
    private long syncEpoch = 0;
    private final Object syncStateLock = new Object();

    // 专用线程：解密 + DB 写入（避免阻塞 OkHttp WebSocket 线程）
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WebSocket-CryptoDB");
        t.setDaemon(true);
        return t;
    });

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
    private void ensureInitialized(boolean ssl) {
        synchronized (connectionLock) {
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
                    .pingInterval(HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .connectionPool(new ConnectionPool(0, 5, TimeUnit.SECONDS));

            if (ssl) {
                // 保留现有 endpoint 兼容行为；证书策略不属于本轮连接生命周期改造。
                builder.hostnameVerifier((hostname, session) -> true);
            }

            this.httpClient = builder.build();
            isInitialized.set(true);
            logger.info("OkHttp initialized with protocol heartbeat interval="
                    + HEARTBEAT_INTERVAL_SEC + "s");
        }
    }

    /**
     * 连接到WebSocket服务器
     */
    public void connect() {
        connect(null);
    }

    private void connect(Long requiredGeneration) {
        final long generation;
        synchronized (connectionLock) {
            if (isShuttingDown.get()) {
                return;
            }
            if (requiredGeneration != null && requiredGeneration != connectionGeneration) {
                logger.fine("Ignoring stale scheduled reconnect for generation=" + requiredGeneration);
                return;
            }
            if (isConnected.get() || isConnecting.get()) {
                logger.info("Already connected or connecting");
                return;
            }
            cancelPendingReconnectLocked();
            isConnecting.set(true);
            isOffline.set(false);
            generation = ++connectionGeneration;
        }

        String wsUrl = settings.getEndpointUrl();
        if (wsUrl == null || wsUrl.isEmpty()) {
            logger.warning("WebSocket URL not configured");
            failConnectionAttempt(generation);
            return;
        }

        // 解析URL
        String host;
        int port;
        boolean ssl;
        String path = "/ws";

        try {
            java.net.URI uri = new java.net.URI(wsUrl);
            String scheme = uri.getScheme();
            host = uri.getHost();
            if (host == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "ws".equalsIgnoreCase(scheme)
                    || "wss".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Endpoint must use http(s) or ws(s) with a host");
            }
            ssl = "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
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
            logger.warning("Invalid WebSocket endpoint configuration: " + describeThrowableChain(e));
            failConnectionAttempt(generation);
            return;
        }

        // 延迟初始化OkHttp
        ensureInitialized(ssl);

        if (!isConnectionAttemptCurrent(generation)) {
            failConnectionAttempt(generation);
            return;
        }

        lastSyncId = localCache.getLastSyncId();
        final long handshakeSyncId = lastSyncId;
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
        } else {
            logger.warning("No auth token configured!");
        }

        logger.info("WebSocket connect request: generation=" + generation
                + ", host=" + host
                + ", port=" + port
                + ", ssl=" + ssl
                + ", auth=" + authState(authToken)
                + ", lastSyncId=" + handshakeSyncId
                + ", reconnectAttempts=" + reconnectAttemptSnapshot());

        // 创建WebSocket监听器
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket openedSocket, Response response) {
                if (!activateConnection(generation, openedSocket)) {
                    logger.fine("Ignoring stale WebSocket onOpen for generation=" + generation);
                    cancelSocketSafely(openedSocket, "stale-open");
                    return;
                }

                if (!sendHandshake(openedSocket, handshakeSyncId)) {
                    forceReconnect("handshake-send-failed");
                    return;
                }
                notifyConnectionStatus(true);

                logger.info("WebSocket connected successfully: generation=" + generation
                        + ", " + describeResponse(response));
            }

            @Override
            public void onMessage(WebSocket sourceSocket, String text) {
                if (!isCurrentSocket(generation, sourceSocket)) {
                    logger.fine("Ignoring stale WebSocket message for generation=" + generation);
                    return;
                }
                handleTextMessage(generation, sourceSocket, text);
            }

            @Override
            public void onClosed(WebSocket closedSocket, int code, String reason) {
                if (isShuttingDown.get()) {
                    logger.info("WebSocket closed during shutdown: code=" + code);
                    return;
                }
                if (!deactivateConnection(generation, closedSocket)) {
                    logger.fine("Ignoring stale WebSocket onClosed for generation=" + generation);
                    return;
                }
                logger.info("WebSocket closed: generation=" + generation + ", code=" + code);
                notifyConnectionStatus(false);
                requestReconnect("closed");
            }

            @Override
            public void onFailure(WebSocket failedSocket, Throwable t, Response response) {
                if (isShuttingDown.get()) {
                    logger.info("WebSocket failure during shutdown: " + describeThrowableChain(t));
                    closeFailureResponse(response);
                    return;
                }
                if (!deactivateConnection(generation, failedSocket)) {
                    logger.fine("Ignoring stale WebSocket onFailure for generation=" + generation);
                    closeFailureResponse(response);
                    return;
                }
                String failureKind = classifyFailure(t, response);
                logger.warning("WebSocket failure [" + failureKind + "]: generation=" + generation
                        + ", causes=" + describeThrowableChain(t)
                        + (response == null ? "" : ", response={" + describeResponse(response) + "}"));
                closeFailureResponse(response);
                notifyConnectionStatus(false);
                notifyError("WebSocket " + failureKind + " error");
                requestReconnect("failure");
            }
        };

        // 发起WebSocket连接
        try {
            OkHttpClient client = httpClient;
            if (client == null) {
                throw new IllegalStateException("OkHttp client unavailable");
            }
            WebSocket createdSocket = client.newWebSocket(requestBuilder.build(), listener);
            if (!registerConnectingSocket(generation, createdSocket)) {
                cancelSocketSafely(createdSocket, "stale-created-socket");
            }
        } catch (Exception e) {
            logger.warning("WebSocket connection exception while creating call: " + describeThrowableChain(e));
            if (failConnectionAttempt(generation)) {
                requestReconnect("connect-exception");
            }
        }
    }

    private boolean registerConnectingSocket(long generation, WebSocket socket) {
        synchronized (connectionLock) {
            if (isShuttingDown.get() || generation != connectionGeneration) {
                return false;
            }
            if (webSocket != null && webSocket != socket) {
                return false;
            }
            webSocket = socket;
            return true;
        }
    }

    private boolean activateConnection(long generation, WebSocket socket) {
        synchronized (connectionLock) {
            if (isShuttingDown.get() || generation != connectionGeneration) {
                return false;
            }
            if (webSocket != null && webSocket != socket) {
                return false;
            }
            webSocket = socket;
            isConnected.set(true);
            isConnecting.set(false);
            isOffline.set(false);
            cancelPendingReconnectLocked();
            return true;
        }
    }

    private void markConnectionHealthy(long generation, WebSocket socket) {
        synchronized (connectionLock) {
            if (!isShuttingDown.get()
                    && generation == connectionGeneration
                    && webSocket == socket
                    && isConnected.get()) {
                reconnectAttempts = 0;
                isOffline.set(false);
            }
        }
    }

    private boolean isConnectionAttemptCurrent(long generation) {
        synchronized (connectionLock) {
            return !isShuttingDown.get()
                    && generation == connectionGeneration
                    && isConnecting.get();
        }
    }

    private boolean isCurrentSocket(long generation, WebSocket socket) {
        synchronized (connectionLock) {
            return !isShuttingDown.get()
                    && generation == connectionGeneration
                    && webSocket == socket
                    && isConnected.get();
        }
    }

    private boolean isConnectionGenerationCurrent(long generation) {
        synchronized (connectionLock) {
            return !isShuttingDown.get()
                    && generation == connectionGeneration
                    && isConnected.get();
        }
    }

    private boolean failConnectionAttempt(long generation) {
        synchronized (connectionLock) {
            if (isShuttingDown.get() || generation != connectionGeneration) {
                return false;
            }
            connectionGeneration++;
            webSocket = null;
            isConnected.set(false);
            isConnecting.set(false);
            return true;
        }
    }

    private boolean deactivateConnection(long generation, WebSocket socket) {
        synchronized (connectionLock) {
            if (isShuttingDown.get() || generation != connectionGeneration) {
                return false;
            }
            if (webSocket != null && webSocket != socket) {
                return false;
            }
            connectionGeneration++;
            webSocket = null;
            isConnected.set(false);
            isConnecting.set(false);
            resetSyncStateForConnectionChangeLocked();
            return true;
        }
    }

    private void resetSyncStateForConnectionChangeLocked() {
        synchronized (syncStateLock) {
            syncEpoch++;
            resetSyncStateLocked();
        }
        isSyncing.set(false);
    }

    private int reconnectAttemptSnapshot() {
        synchronized (connectionLock) {
            return reconnectAttempts;
        }
    }

    private String authState(String token) {
        return token == null || token.isBlank() ? "missing" : "configured";
    }

    private void closeFailureResponse(Response response) {
        if (response != null) {
            try {
                response.close();
            } catch (RuntimeException closeError) {
                logger.warning("Failed to close WebSocket failure response: "
                        + describeThrowableChain(closeError));
            }
        }
    }

    private void cancelSocketSafely(WebSocket socket, String context) {
        if (socket == null) {
            return;
        }
        try {
            socket.cancel();
        } catch (RuntimeException cancelError) {
            logger.warning("Failed to cancel WebSocket (" + safeReasonCode(context) + "): "
                    + describeThrowableChain(cancelError));
        }
    }

    private void closeSocketSafely(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            if (!socket.close(1000, "Client disconnect")) {
                cancelSocketSafely(socket, "disconnect-close-rejected");
            }
        } catch (RuntimeException closeError) {
            logger.warning("Failed to close WebSocket: " + describeThrowableChain(closeError));
            cancelSocketSafely(socket, "disconnect-close-failed");
        }
    }

    private boolean sendHandshake(WebSocket socket, long syncId) {
        JsonObject handshake = new JsonObject()
                .put("type", "handshake")
                .put("client_id", clientId)
                .put("last_sync_id", syncId);

        try {
            boolean sent = socket.send(handshake.encode());
            if (sent) {
                logger.info("Sent handshake with lastSyncId=" + syncId);
            } else {
                logger.warning("Failed to send handshake");
            }
            return sent;
        } catch (Exception e) {
            logger.warning("Exception sending handshake: " + describeThrowableChain(e));
            return false;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        final WebSocket socket;
        final boolean notifyDisconnected;
        synchronized (connectionLock) {
            socket = webSocket;
            notifyDisconnected = isConnected.get() || isConnecting.get();
            connectionGeneration++;
            webSocket = null;
            isConnected.set(false);
            isConnecting.set(false);
            cancelPendingReconnectLocked();
            resetSyncStateForConnectionChangeLocked();
        }
        closeSocketSafely(socket);
        if (notifyDisconnected) {
            notifyConnectionStatus(false);
        }
    }
    /**
     * 手动重连 - 用户主动触发，重置重试计数器后发起连接
     */
    public void manualReconnect() {
        synchronized (connectionLock) {
            if (isShuttingDown.get()) {
                return;
            }
            reconnectAttempts = 0;
            isOffline.set(false);
            cancelPendingReconnectLocked();
        }
        connect();
    }

    public void markConnectionSuspect(String reason) {
        if (isShuttingDown.get()) {
            return;
        }

        String safeReason = safeReasonCode(reason);
        logger.warning("Marking WebSocket connection suspect: " + safeReason);

        synchronized (connectionLock) {
            reconnectAttempts = 0;
            isOffline.set(false);
        }

        forceReconnect(safeReason);
    }

    /**
     * 发送新笔记到服务器
     */
    public void sendNewNote(String content) throws Exception {
        final WebSocket socket;
        synchronized (connectionLock) {
            socket = isConnected.get() ? webSocket : null;
            if (socket == null) {
                throw new IllegalStateException("Not connected to server");
            }
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

        boolean sent = socket.send(message.encode());
        if (sent) {
            logger.info("Sent new note to server");
        } else {
            throw new IllegalStateException("Failed to send message");
        }
    }

    /**
     * 处理服务器消息
     */
    private void handleTextMessage(long generation, WebSocket sourceSocket, String message) {
        // 如果正在关闭，忽略所有消息
        if (isShuttingDown.get()) {
            logger.fine("Ignoring WebSocket message during shutdown");
            return;
        }

        try {
            JsonObject json = new JsonObject(message);
            markConnectionHealthy(generation, sourceSocket);
            String type = json.getString("type");

            if (type == null) {
                logger.warning("WebSocket message without type; payloadChars=" + message.length());
                return;
            }

            switch (type) {
                case "sync_batch":
                    handleSyncBatch(generation, json);
                    break;
                case "sync_complete":
                    handleSyncComplete(json);
                    break;
                case "realtime_update":
                    handleRealtimeUpdate(generation, json);
                    break;
                case "ping":
                    // 服务器发送的应用层ping，我们回复pong
                    // 这样可以兼容服务器的心跳机制
                    if (isCurrentSocket(generation, sourceSocket)) {
                        sourceSocket.send("{\"type\":\"pong\"}");
                        logger.fine("Responded to server ping with pong");
                    }
                    break;
                case "pong":
                    // 兼容服务器的应用层 pong；客户端存活检测由 OkHttp control ping/pong 负责。
                    break;
                case "error":
                    handleError(json);
                    break;
                case "new_note_ack":
                    handleNewNoteAck(json);
                    break;
                default:
                    logger.warning("Unknown WebSocket message type; typeChars=" + type.length());
            }
        } catch (Exception e) {
            logger.warning("Failed to handle WebSocket message: " + describeThrowableChain(e));
        }
    }

    private void handleSyncBatch(long connectionGen, JsonObject json) {
        isSyncing.set(true);

        int batchId = json.getInteger("batch_id", 0);
        int totalBatches = json.getInteger("total_batches", 1);

        JsonArray notes = json.getJsonArray("notes");
        if (notes == null) {
            int currentBatch = markBatchReceived(totalBatches);
            notifySyncProgress(currentBatch, totalBatches);
            logger.info("Received empty batch " + batchId + "/" + totalBatches);
            return;
        }

        // Snapshot note data from JSON (lightweight, on OkHttp thread)
        List<JsonObject> noteSnapshots = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            noteSnapshots.add(notes.getJsonObject(i));
        }

        final long currentSyncEpoch;
        synchronized (syncStateLock) {
            pendingBatchWrites++;
            currentSyncEpoch = syncEpoch;
        }

        // Decrypt + DB write on dedicated thread (avoid blocking OkHttp WebSocket thread)
        try {
            cryptoExecutor.submit(() -> {
                boolean batchChangedData = false;
                boolean batchWriteFailed = false;
                try {
                    if (!isConnectionGenerationCurrent(connectionGen)) {
                        logger.fine("Discarding stale sync batch for generation=" + connectionGen);
                        return;
                    }
                    List<LocalCacheService.NoteData> batchNotes = new ArrayList<>();
                    for (JsonObject note : noteSnapshots) {
                        try {
                            long id = note.getLong("id");
                            String encryptedContent = note.getString("content");
                            String channel = note.getString("channel");
                            String createdAt = note.getString("created_at");

                            String decryptedContent;
                            try {
                                decryptedContent = cryptoService.decrypt(encryptedContent);
                            } catch (Exception e) {
                                logger.warning("Failed to decrypt note " + id + ": " + describeThrowableChain(e)
                                        + ", storing encrypted content");
                                decryptedContent = encryptedContent;
                            }

                            batchNotes.add(new LocalCacheService.NoteData(
                                    id, decryptedContent, channel, createdAt, encryptedContent));
                        } catch (Exception e) {
                            logger.warning("Failed to parse note: " + describeThrowableChain(e));
                        }
                    }

                    if (!batchNotes.isEmpty()) {
                        try {
                            if (!isConnectionGenerationCurrent(connectionGen)) {
                                logger.fine("Discarding stale parsed batch for generation=" + connectionGen);
                                return;
                            }
                            localCache.batchInsertNotes(batchNotes, false);
                            batchChangedData = true;
                            logger.info("Batch " + batchId + ": inserted " + batchNotes.size() + " notes to DB");
                        } catch (Exception e) {
                            batchWriteFailed = true;
                            logger.warning("Failed to insert batch " + batchId + ": " + describeThrowableChain(e));
                        }
                    }
                } finally {
                    onBatchWriteFinished(currentSyncEpoch, batchChangedData, batchWriteFailed);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warning("Rejected batch " + batchId + " because crypto executor is shutting down");
            onBatchWriteFinished(currentSyncEpoch, false, true);
        }

        int currentBatch = markBatchReceived(totalBatches);
        notifySyncProgress(currentBatch, totalBatches);
        logger.info("Received batch " + batchId + "/" + totalBatches);
    }

    private void handleSyncComplete(JsonObject json) {
        int totalSynced = json.getInteger("total_synced", 0);
        long newLastSyncId = json.getLong("last_sync_id", -1L);
        final long currentSyncEpoch;
        final int remainingWrites;
        final boolean finalizeNow;

        synchronized (syncStateLock) {
            currentSyncEpoch = syncEpoch;
            syncCompletionPending = true;
            completedSyncTotal = totalSynced;
            completedSyncLastSyncId = newLastSyncId;
            remainingWrites = pendingBatchWrites;
            finalizeNow = pendingBatchWrites == 0;
        }

        if (finalizeNow) {
            finalizeSyncRound(currentSyncEpoch);
        } else {
            logger.info("sync_complete received; waiting for " + remainingWrites + " batch write(s) to finish");
        }
    }

    private void handleRealtimeUpdate(long connectionGen, JsonObject json) {
        JsonObject noteJson = json.getJsonObject("note");

        if (noteJson == null) {
            logger.warning("Realtime update is missing note object");
            return;
        }

        // Decrypt + DB write on dedicated thread (avoid blocking OkHttp WebSocket thread)
        cryptoExecutor.submit(() -> {
            try {
                if (!isConnectionGenerationCurrent(connectionGen)) {
                    logger.fine("Discarding stale realtime update for generation=" + connectionGen);
                    return;
                }
                long id = noteJson.getLong("id");
                String encryptedContent = noteJson.getString("content");
                String channel = noteJson.getString("channel");
                String createdAt = noteJson.getString("created_at");

                logger.info("Parsed realtime note metadata: id=" + id);

                String decryptedContent;
                try {
                    decryptedContent = cryptoService.decrypt(encryptedContent);
                } catch (Exception e) {
                    logger.warning("Failed to decrypt note " + id + ": " + describeThrowableChain(e)
                            + ", storing encrypted content");
                    decryptedContent = encryptedContent;
                }

                LocalCacheService.NoteData note = new LocalCacheService.NoteData(
                        id, decryptedContent, channel, createdAt, encryptedContent);
                if (!isConnectionGenerationCurrent(connectionGen)) {
                    logger.fine("Discarding stale decrypted realtime update for generation=" + connectionGen);
                    return;
                }
                localCache.insertNote(note);

                if (id > lastSyncId) {
                    lastSyncId = id;
                    localCache.updateLastSyncId(lastSyncId);
                    logger.info("Updated lastSyncId to " + lastSyncId + " after realtime update");
                }

                notifyRealtimeUpdate(id, decryptedContent);
                logger.info("Realtime update for note " + id + " completed successfully");
            } catch (Exception e) {
                logger.warning("Failed to process realtime update: " + describeThrowableChain(e));
            }
        });
    }

    private void handleNewNoteAck(JsonObject json) {
        long id = json.getLong("id", -1L);
        boolean success = json.getBoolean("success", false);
        if (success) {
            logger.info("Server acknowledged new note with id=" + id);
        }
    }

    private void handleError(JsonObject json) {
        logger.warning("WebSocket server reported an application error");
        notifyError("WebSocket server reported an application error");
    }

    /**
     * 强制断开当前代际并触发重连。
     */
    private void forceReconnect(String reason) {
        final WebSocket socket;
        final boolean notifyDisconnected;
        synchronized (connectionLock) {
            if (isShuttingDown.get()) {
                return;
            }
            cancelPendingReconnectLocked();
            socket = webSocket;
            notifyDisconnected = isConnected.get() || isConnecting.get();
            connectionGeneration++;
            webSocket = null;
            isConnected.set(false);
            isConnecting.set(false);
            resetSyncStateForConnectionChangeLocked();
        }
        cancelSocketSafely(socket, "force-reconnect");
        if (notifyDisconnected) {
            notifyConnectionStatus(false);
        }
        requestReconnect(reason);
    }

    /**
     * 在 connectionLock 下以单个 ScheduledFuture 合并重复重连请求。
     */
    private void requestReconnect(String reason) {
        final int outcome;
        synchronized (connectionLock) {
            outcome = scheduleReconnectLocked(safeReasonCode(reason));
        }
        if (outcome < 0) {
            notifyOffline();
        } else if (outcome > 0) {
            notifyReconnecting(outcome, MAX_RECONNECT_ATTEMPTS);
        }
    }

    private void cancelPendingReconnectLocked() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    /**
     * @return 正数表示安排的 attempt，0 表示忽略，-1 表示进入 offline。
     */
    private int scheduleReconnectLocked(String reason) {
        if (isShuttingDown.get() || !isInitialized.get()
                || isConnected.get() || isConnecting.get()) {
            return 0;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            logger.info("Ignoring duplicate reconnect request (" + reason + ")");
            return 0;
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (isOffline.compareAndSet(false, true)) {
                logger.warning("Max reconnect attempts reached, entering offline mode");
                return -1;
            }
            return 0;
        }

        long delay = RECONNECT_BASE_DELAY_MS * (1L << reconnectAttempts);
        reconnectAttempts++;
        int scheduledAttempt = reconnectAttempts;
        long scheduledGeneration = connectionGeneration;

        logger.info("Scheduling reconnect in " + delay + "ms (attempt " + scheduledAttempt + "/"
                + MAX_RECONNECT_ATTEMPTS + ", reason=" + reason + ")");

        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebSocket-Reconnect");
                t.setDaemon(true);
                return t;
            });
        }

        reconnectTask = reconnectScheduler.schedule(
                () -> runScheduledReconnect(scheduledGeneration), delay, TimeUnit.MILLISECONDS);
        return scheduledAttempt;
    }

    private void runScheduledReconnect(long scheduledGeneration) {
        synchronized (connectionLock) {
            if (isShuttingDown.get() || scheduledGeneration != connectionGeneration) {
                return;
            }
            reconnectTask = null;
        }
        logger.info("Attempting reconnect...");
        connect(scheduledGeneration);
    }

    private String safeReasonCode(String reason) {
        if (reason == null || !reason.matches("[a-z0-9-]{1,48}")) {
            return "unspecified";
        }
        return reason;
    }

    private int markBatchReceived(int totalBatches) {
        synchronized (syncStateLock) {
            if (expectedBatches == 0) {
                expectedBatches = totalBatches;
            }
            receivedBatches++;
            return receivedBatches;
        }
    }

    private void onBatchWriteFinished(long batchSyncEpoch, boolean batchChangedData, boolean batchWriteFailed) {
        final boolean finalizeNow;

        synchronized (syncStateLock) {
            if (batchSyncEpoch != syncEpoch) {
                return;
            }

            if (batchChangedData) {
                syncDataChanged = true;
            }
            if (batchWriteFailed) {
                syncWriteFailed = true;
            }

            pendingBatchWrites = Math.max(0, pendingBatchWrites - 1);
            finalizeNow = syncCompletionPending && pendingBatchWrites == 0;
        }

        if (finalizeNow) {
            finalizeSyncRound(batchSyncEpoch);
        }
    }

    private void finalizeSyncRound(long batchSyncEpoch) {
        final int totalSynced;
        final long newLastSyncId;
        final boolean shouldNotifyBatchReload;
        final boolean shouldAdvanceSyncCursor;

        synchronized (syncStateLock) {
            if (batchSyncEpoch != syncEpoch || !syncCompletionPending || pendingBatchWrites != 0) {
                return;
            }

            totalSynced = completedSyncTotal;
            newLastSyncId = completedSyncLastSyncId;
            shouldNotifyBatchReload = syncDataChanged;
            shouldAdvanceSyncCursor = !syncWriteFailed;
            resetSyncStateLocked();
        }

        try {
            // 以服务器返回的 last_sync_id 为准做最终更新
            if (shouldAdvanceSyncCursor && totalSynced > 0 && newLastSyncId > 0) {
                localCache.updateLastSyncId(newLastSyncId);
                this.lastSyncId = newLastSyncId;
                logger.info("Updated lastSyncId to: " + newLastSyncId);
            } else if (!shouldAdvanceSyncCursor) {
                logger.warning("Sync round had batch write failures; keeping previous lastSyncId so the server can replay");
            }
        } catch (Exception e) {
            logger.warning("Failed to update lastSyncId on sync complete: " + describeThrowableChain(e));
        }

        isSyncing.set(false);

        if (shouldNotifyBatchReload) {
            localCache.notifyBatchSyncApplied();
        }

        notifySyncComplete(totalSynced, newLastSyncId);
        logger.info("Sync complete after DB drain: " + totalSynced + " notes");
    }

    private void resetSyncStateLocked() {
        expectedBatches = 0;
        receivedBatches = 0;
        pendingBatchWrites = 0;
        syncCompletionPending = false;
        completedSyncTotal = 0;
        completedSyncLastSyncId = -1L;
        syncDataChanged = false;
        syncWriteFailed = false;
    }

    /**
     * 完全关闭服务 - 立即返回，强制断开连接
     * 优化策略：先关闭底层OkHttp资源，使用cancel()而非close()避免等待
     */
    public void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return;
        }
        logger.info("Starting immediate shutdown...");

        final WebSocket socket;
        final OkHttpClient client;
        final ScheduledExecutorService scheduler;
        synchronized (connectionLock) {
            connectionGeneration++;
            socket = webSocket;
            webSocket = null;
            isConnected.set(false);
            isConnecting.set(false);
            cancelPendingReconnectLocked();
            scheduler = reconnectScheduler;
            reconnectScheduler = null;
            reconnectAttempts = 0;
            resetSyncStateForConnectionChangeLocked();
            client = httpClient;
            httpClient = null;
            isInitialized.set(false);
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (socket != null) {
            cancelSocketSafely(socket, "shutdown");
            logger.info("WebSocket cancelled immediately");
        }

        // 关闭解密专用线程
        cryptoExecutor.shutdownNow();

        // 立即强制关闭OkHttp底层资源（这会强制断开所有连接）
        if (client != null) {
            try {
                client.dispatcher().executorService().shutdownNow();
                client.connectionPool().evictAll();
                if (client.cache() != null) {
                    client.cache().close();
                }
                logger.info("OkHttp resources closed");
            } catch (Exception e) {
                logger.warning("OkHttp cleanup error: " + describeThrowableChain(e));
            }
        }

        logger.info("Shutdown completed");
    }

    private String generateClientId() {
        return UUID.randomUUID().toString();
    }

    // 监听器管理
    public void addListener(SyncListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(SyncListener listener) {
        listeners.remove(listener);
    }

    private void notifyConnectionStatus(boolean connected) {
        notifyListeners("connection-status", listener -> listener.onConnectionStatus(connected));
    }

    private void notifySyncProgress(int current, int total) {
        notifyListeners("sync-progress", listener -> listener.onSyncProgress(current, total));
    }

    private void notifySyncComplete(int total, long lastSyncId) {
        notifyListeners("sync-complete", listener -> listener.onSyncComplete(total, lastSyncId));
    }

    private void notifyRealtimeUpdate(long id, String content) {
        notifyListeners("realtime-update", listener -> listener.onRealtimeUpdate(id, content));
    }

    private void notifyError(String message) {
        notifyListeners("error", listener -> listener.onError(message));
    }

    private void notifyOffline() {
        notifyListeners("offline", SyncListener::onOffline);
    }

    private void notifyReconnecting(int attempt, int maxAttempts) {
        notifyListeners("reconnecting", listener -> listener.onReconnecting(attempt, maxAttempts));
    }

    private void notifyListeners(String event, Consumer<SyncListener> notification) {
        for (SyncListener listener : listeners) {
            try {
                notification.accept(listener);
            } catch (Throwable error) {
                logger.warning("WebSocket listener failed during " + event + ": "
                        + describeThrowableChain(error));
            }
        }
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

    String buildDiagnosticsState() {
        synchronized (connectionLock) {
            return "ws.generation=" + connectionGeneration + System.lineSeparator()
                    + "ws.initialized=" + isInitialized.get() + System.lineSeparator()
                    + "ws.connected=" + isConnected.get() + System.lineSeparator()
                    + "ws.connecting=" + isConnecting.get() + System.lineSeparator()
                    + "ws.syncing=" + isSyncing.get() + System.lineSeparator()
                    + "ws.offline=" + isOffline.get() + System.lineSeparator()
                    + "ws.reconnectAttempts=" + reconnectAttempts + System.lineSeparator()
                    + "ws.reconnectScheduled="
                    + (reconnectTask != null && !reconnectTask.isDone()) + System.lineSeparator()
                    + "ws.shuttingDown=" + isShuttingDown.get();
        }
    }

    private String classifyFailure(Throwable throwable, Response response) {
        if (hasCause(throwable, java.net.UnknownHostException.class)) {
            return "dns";
        }
        if (hasCause(throwable, javax.net.ssl.SSLException.class)) {
            return "tls";
        }
        if (hasCause(throwable, java.net.SocketTimeoutException.class)) {
            return "timeout";
        }
        if (hasCause(throwable, java.net.ConnectException.class)) {
            return "connect";
        }
        if (response != null) {
            return "http_" + response.code();
        }
        return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String describeThrowableChain(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }

        List<String> parts = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            parts.add(current.getClass().getName());
            current = current.getCause();
        }
        return String.join(" <- ", parts);
    }

    private String describeResponse(Response response) {
        if (response == null) {
            return "none";
        }

        List<String> headers = new ArrayList<>();
        appendHeader(headers, response, "server");
        appendHeader(headers, response, "cf-ray");
        appendHeader(headers, response, "content-type");

        String headerSummary = headers.isEmpty() ? "" : ", headers={" + String.join(", ", headers) + "}";
        return "code=" + response.code()
                + headerSummary;
    }

    private void appendHeader(List<String> headers, Response response, String name) {
        String value = response.header(name);
        if (value != null && !value.isBlank()) {
            headers.add(name + "=" + value);
        }
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

        /** 正在重连中 */
        default void onReconnecting(int attempt, int maxAttempts) {}
    }

    /**
     * 简单日志包装
     */
    static class Logger {
        private final java.util.logging.Logger delegate;

        private Logger(java.util.logging.Logger delegate) {
            this.delegate = delegate;
        }

        public static Logger getLogger(String name) {
            return new Logger(AppLogger.getLogger(name));
        }

        public void info(String msg) {
            delegate.info(msg);
        }

        public void warning(String msg) {
            delegate.warning(msg);
        }

        public void severe(String msg) {
            delegate.severe(msg);
        }

        public void fine(String msg) {
            delegate.fine(msg);
        }
    }
}
