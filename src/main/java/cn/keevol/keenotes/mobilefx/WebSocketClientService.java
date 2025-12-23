package cn.keevol.keenotes.mobilefx;

import jakarta.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket客户端服务，处理与服务器的实时同步
 * - 连接管理
 * - 数据同步
 * - 心跳和重连
 */
public class WebSocketClientService extends Endpoint {
    private static final Logger logger = Logger.getLogger(WebSocketClientService.class.getName());

    private Session session;
    private Map<String, Object> sessionProperties;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

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

    // 心跳和重连定时器
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
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
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * 连接到WebSocket服务器
     */
    public void connect() {
        if (isConnected.get() || isConnecting.get()) {
            logger.info("Already connected or connecting");
            return;
        }

        if (!isConnecting.compareAndSet(false, true)) {
            return; // Another thread is connecting
        }

        String wsUrl = settings.getEndpointUrl();
        if (wsUrl == null || wsUrl.isEmpty()) {
            logger.warning("WebSocket URL not configured");
            return;
        }

        // 转换HTTP URL为WebSocket URL
        String wsEndpointUrl = wsUrl.replace("http://", "ws://").replace("https://", "wss://");
        if (!wsEndpointUrl.endsWith("/ws")) {
            wsEndpointUrl = wsEndpointUrl + "/ws";
        }

        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            String authToken = settings.getToken();

            // 设置客户端ID和最后同步ID
            lastSyncId = localCache.getLastSyncId();

            // Store properties for use in onOpen
            this.sessionProperties = new HashMap<>();
            sessionProperties.put("auth_token", authToken);
            sessionProperties.put("client_id", clientId);
            sessionProperties.put("last_sync_id", lastSyncId);

            // Configure endpoint with Authorization header
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .configurator(new ClientEndpointConfig.Configurator() {
                        @Override
                        public void beforeRequest(Map<String, List<String>> headers) {
                            if (authToken != null && !authToken.isEmpty()) {
                                headers.put("Authorization", List.of("Bearer " + authToken));
                            }
                        }
                    })
                    .build();
            container.connectToServer(this, config, URI.create(wsEndpointUrl));
            logger.info("WebSocket connecting to: " + wsEndpointUrl);

        } catch (Exception e) {
            logger.warning("Failed to connect WebSocket: " + e.getMessage());
            isConnecting.set(false);
            scheduleReconnect();
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (session != null && session.isOpen()) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client disconnect"));
            } catch (IOException e) {
                logger.warning("Error closing session: " + e.getMessage());
            }
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

        // 加密内容
        String encryptedContent = cryptoService.encrypt(content);

        // 构建消息
        String message = String.format(
            "{\"type\":\"new_note\",\"content\":%s,\"channel\":\"mobile\",\"timestamp\":\"%s\"}",
            escapeJson(encryptedContent),
            java.time.LocalDateTime.now().toString()
        );

        session.getBasicRemote().sendText(message);
        logger.info("Sent new note to server");
    }

    /**
     * WebSocket连接打开回调 - Endpoint override
     */
    @Override
    public void onOpen(Session session, EndpointConfig config) {
        this.session = session;
        isConnected.set(true);
        isConnecting.set(false);  // Reset connecting flag
        reconnectAttempts = 0;  // 重置重连计数

        // Add message handler for text messages
        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                handleTextMessage(message);
            }
        });

        // 获取认证信息 (从sessionProperties)
        String clientId = (String) sessionProperties.get("client_id");
        Long lastSyncId = (Long) sessionProperties.get("last_sync_id");

        // 发送握手消息（认证已在HTTP头中完成）
        String handshake = String.format(
            "{\"type\":\"handshake\",\"client_id\":\"%s\",\"last_sync_id\":%d}",
            clientId, lastSyncId != null ? lastSyncId : -1
        );

        try {
            session.getBasicRemote().sendText(handshake);
            logger.info("WebSocket connected, sent handshake");
        } catch (IOException e) {
            logger.warning("Failed to send handshake: " + e.getMessage());
        }

        // 启动心跳
        startHeartbeat();

        // 通知监听器
        notifyConnectionStatus(true);
    }

    /**
     * Handle text messages from server
     */
    private void handleTextMessage(String message) {
        try {
            // 简单JSON解析
            if (message.contains("\"type\":\"sync_batch\"")) {
                handleSyncBatch(message);
            } else if (message.contains("\"type\":\"sync_complete\"")) {
                handleSyncComplete(message);
            } else if (message.contains("\"type\":\"realtime_update\"")) {
                handleRealtimeUpdate(message);
            } else if (message.contains("\"type\":\"pong\"")) {
                // 收到pong，更新最后活动时间
                logger.fine("Received pong");
            } else if (message.contains("\"type\":\"error\"")) {
                handleError(message);
            } else if (message.contains("\"type\":\"new_note_ack\"")) {
                handleNewNoteAck(message);
            }
        } catch (Exception e) {
            logger.warning("Failed to handle message: " + e.getMessage());
        }
    }

    /**
     * 处理同步批次
     */
    private void handleSyncBatch(String message) throws Exception {
        isSyncing.set(true);
        notifySyncProgress(0, 1);  // 开始同步

        logger.info("DEBUG: handleSyncBatch - raw message: " + message);

        // 解析JSON（简化版）
        int batchId = extractInt(message, "batch_id");
        int totalBatches = extractInt(message, "total_batches");

        logger.info("DEBUG: batchId=" + batchId + ", totalBatches=" + totalBatches + ", expectedBatches=" + expectedBatches);

        if (expectedBatches == 0) {
            expectedBatches = totalBatches;
            syncBatchBuffer.clear();
        }

        // 解析notes数组
        logger.info("DEBUG: Message contains 'notes': " + message.contains("\"notes\":["));
        List<LocalCacheService.NoteData> batchNotes = parseNotesArray(message);
        logger.info("DEBUG: Parsed " + batchNotes.size() + " notes from batch " + batchId);

        // Additional debug: check if message has the right structure
        if (message.contains("\"notes\":") && batchNotes.isEmpty()) {
            logger.warning("DEBUG: Message has notes field but parseNotesArray returned empty! Message: " + message);
        }

        // 解密并添加到buffer
        for (LocalCacheService.NoteData note : batchNotes) {
            try {
                logger.info("DEBUG: Processing note id=" + note.id + ", content=" + note.content.substring(0, Math.min(30, note.content.length())));
                String decryptedContent = cryptoService.decrypt(note.content);
                syncBatchBuffer.add(new LocalCacheService.NoteData(
                    note.id, decryptedContent, note.channel, note.createdAt, note.content
                ));
                logger.info("DEBUG: Added to buffer - id=" + note.id);
            } catch (Exception e) {
                logger.warning("Failed to decrypt note " + note.id + ": " + e.getMessage());
            }
        }

        receivedBatches++;
        notifySyncProgress(receivedBatches, totalBatches);
        logger.info("Received batch " + batchId + "/" + totalBatches + ", buffer size=" + syncBatchBuffer.size());
    }

    /**
     * 处理同步完成
     */
    private void handleSyncComplete(String message) throws Exception {
        int totalSynced = extractInt(message, "total_synced");
        long lastSyncId = extractLong(message, "last_sync_id");

        logger.info("DEBUG: Raw message: " + message);
        logger.info("DEBUG: totalSynced=" + totalSynced + ", lastSyncId=" + lastSyncId);

        if (!syncBatchBuffer.isEmpty()) {
            // 批量插入到本地缓存
            logger.info("DEBUG: About to batch insert " + syncBatchBuffer.size() + " notes");
            for (LocalCacheService.NoteData note : syncBatchBuffer) {
                logger.info("DEBUG: Buffer contains - id=" + note.id + ", content=" + note.content.substring(0, Math.min(30, note.content.length())));
            }
            localCache.batchInsertNotes(syncBatchBuffer);
            logger.info("Batch inserted " + syncBatchBuffer.size() + " notes");
        } else {
            logger.warning("DEBUG: syncBatchBuffer is EMPTY! No notes to insert");
        }

        // 更新最后同步ID - 只在有笔记被同步时才更新
        if (totalSynced > 0 && lastSyncId > 0) {
            localCache.updateLastSyncId(lastSyncId);
            this.lastSyncId = lastSyncId;
            logger.info("Updated lastSyncId to: " + lastSyncId);
        } else if (totalSynced == 0) {
            logger.info("No notes synced (totalSynced=0), keeping lastSyncId=" + this.lastSyncId);
        } else {
            logger.warning("Invalid lastSyncId: " + lastSyncId + ", skipping update");
        }

        // 清理buffer
        syncBatchBuffer.clear();
        expectedBatches = 0;
        receivedBatches = 0;
        isSyncing.set(false);

        notifySyncComplete(totalSynced, lastSyncId);
        logger.info("Sync complete: " + totalSynced + " notes, lastSyncId=" + lastSyncId);
    }

    /**
     * 处理实时更新
     */
    private void handleRealtimeUpdate(String message) throws Exception {
        logger.info("DEBUG: Received realtime_update message: " + message);

        // 解析单个note
        int noteStart = message.indexOf("\"note\":{");
        if (noteStart == -1) {
            logger.warning("DEBUG: Could not find note in message");
            return;
        }

        int noteEnd = findMatchingBrace(message, noteStart + 7);
        if (noteEnd == -1) {
            logger.warning("DEBUG: Could not find note end");
            return;
        }

        String noteStr = message.substring(noteStart + 7, noteEnd + 1);
        long id = extractLong(noteStr, "id");
        String encryptedContent = extractString(noteStr, "content");
        String channel = extractString(noteStr, "channel");
        String createdAt = extractString(noteStr, "created_at");

        logger.info("DEBUG: Parsed note - id=" + id + ", channel=" + channel + ", createdAt=" + createdAt);

        try {
            // 解密
            String decryptedContent = cryptoService.decrypt(encryptedContent);

            // 插入到本地缓存
            LocalCacheService.NoteData note = new LocalCacheService.NoteData(
                id, decryptedContent, channel, createdAt, encryptedContent
            );
            localCache.insertNote(note);

            notifyRealtimeUpdate(id, decryptedContent);
            logger.info("Realtime update received for note " + id + ", content: " + decryptedContent.substring(0, Math.min(50, decryptedContent.length())));

        } catch (Exception e) {
            logger.warning("Failed to process realtime update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理新笔记确认
     */
    private void handleNewNoteAck(String message) {
        long id = extractLong(message, "id");
        boolean success = message.contains("\"success\":true");
        if (success) {
            logger.info("Server acknowledged new note with id=" + id);
        }
    }

    /**
     * 处理错误
     */
    private void handleError(String message) {
        String errorMsg = extractString(message, "message");
        logger.warning("Server error: " + errorMsg);
        notifyError(errorMsg);
    }

    /**
     * WebSocket关闭回调 - Endpoint override
     */
    @Override
    public void onClose(Session session, CloseReason closeReason) {
        logger.info("WebSocket closed: " + closeReason.getReasonPhrase() + " (code: " + closeReason.getCloseCode() + ")");
        isConnected.set(false);
        isConnecting.set(false);
        cleanup();
        notifyConnectionStatus(false);

        // 尝试重连（除非是正常关闭）
        if (closeReason.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE) {
            scheduleReconnect();
        }
    }

    /**
     * WebSocket错误回调 - Endpoint override
     */
    @Override
    public void onError(Session session, Throwable throwable) {
        logger.warning("WebSocket error: " + throwable.getMessage());
        isConnected.set(false);
        isConnecting.set(false);
        notifyError(throwable.getMessage());
        cleanup();
        scheduleReconnect();
    }

    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(false);
        }

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (isConnected.get() && session != null && session.isOpen()) {
                try {
                    session.getBasicRemote().sendText("{\"type\":\"ping\"}");
                } catch (IOException e) {
                    logger.warning("Heartbeat failed: " + e.getMessage());
                    // 连接可能已断开
                    isConnected.set(false);
                    scheduleReconnect();
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 安排重连
     */
    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.warning("Max reconnect attempts reached, giving up");
            notifyError("Max reconnect attempts reached");
            return;
        }

        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }

        int delay = RECONNECT_BASE_DELAY_MS * (int)Math.pow(2, reconnectAttempts);
        reconnectAttempts++;

        logger.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");

        reconnectTask = scheduler.schedule(() -> {
            logger.info("Attempting reconnect...");
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        syncBatchBuffer.clear();
        expectedBatches = 0;
        receivedBatches = 0;
        isConnecting.set(false);
    }

    /**
     * 完全关闭服务，释放所有资源
     */
    public void shutdown() {
        disconnect();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 生成客户端ID
     */
    private String generateClientId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 监听器相关
     */
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

    /**
     * 工具方法：JSON解析
     */
    private String extractString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos == -1) {
            logger.fine("DEBUG extractString(" + key + "): key not found in " + json);
            return null;
        }

        int colonPos = json.indexOf(":", keyPos);
        if (colonPos == -1) return null;

        // Skip whitespace after colon
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        // Check if value starts with quote
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            logger.fine("DEBUG extractString(" + key + "): value is not a quoted string");
            return null;
        }

        int valueEnd = findStringEnd(json, valueStart + 1);
        if (valueEnd == -1) return null;

        String result = json.substring(valueStart + 1, valueEnd)
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
        logger.fine("DEBUG extractString(" + key + "): result='" + result + "'");
        return result;
    }

    private long extractLong(String json, String key) {
        String value = extractString(json, key);
        if (value != null) {
            try {
                long result = Long.parseLong(value);
                logger.fine("DEBUG extractLong(" + key + "): found quoted value '" + value + "' -> " + result);
                return result;
            } catch (NumberFormatException e) {
                logger.warning("DEBUG extractLong(" + key + "): quoted value '" + value + "' is not a number");
            }
        }

        // 尝试不带引号的数字
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos == -1) {
            logger.warning("DEBUG extractLong(" + key + "): key not found in " + json);
            return -1;
        }
        int colonPos = json.indexOf(":", keyPos);
        if (colonPos == -1) return -1;
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        try {
            long result = Long.parseLong(json.substring(valueStart, valueEnd));
            logger.fine("DEBUG extractLong(" + key + "): found unquoted value '" + json.substring(valueStart, valueEnd) + "' -> " + result);
            return result;
        } catch (NumberFormatException e2) {
            logger.warning("DEBUG extractLong(" + key + "): unquoted value is not a number");
            return -1;
        }
    }

    private int extractInt(String json, String key) {
        return (int) extractLong(json, key);
    }

    private int findStringEnd(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
            } else if (c == '\"') {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int start) {
        if (start >= json.length() || json.charAt(start) != '{') return -1;
        int depth = 1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"') {
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

    private List<LocalCacheService.NoteData> parseNotesArray(String message) {
        List<LocalCacheService.NoteData> notes = new ArrayList<>();
        int arrayStart = message.indexOf("\"notes\":[");
        if (arrayStart == -1) {
            logger.warning("DEBUG parseNotesArray: Could not find \"notes\":[ in message");
            return notes;
        }

        // "notes":[" is 9 characters, so arrayStart + 8 points to the '['
        int bracketStart = arrayStart + 8;
        int arrayEnd = findMatchingBracket(message, bracketStart);
        if (arrayEnd == -1) {
            logger.warning("DEBUG parseNotesArray: Could not find matching bracket, start=" + bracketStart + ", char=" + message.charAt(bracketStart));
            return notes;
        }

        String arrayContent = message.substring(bracketStart + 1, arrayEnd);
        logger.info("DEBUG parseNotesArray: arrayContent=" + arrayContent);

        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf("{", pos);
            if (objStart == -1) break;

            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd == -1) break;

            String objStr = arrayContent.substring(objStart, objEnd + 1);
            logger.info("DEBUG parseNotesArray: objStr=" + objStr);

            long id = extractLong(objStr, "id");
            String content = extractString(objStr, "content");
            String channel = extractString(objStr, "channel");
            String createdAt = extractString(objStr, "created_at");

            logger.info("DEBUG parseNotesArray: parsed - id=" + id + ", content=" + content + ", channel=" + channel + ", createdAt=" + createdAt);

            notes.add(new LocalCacheService.NoteData(id, content, channel, createdAt, content));
            pos = objEnd + 1;
        }

        logger.info("DEBUG parseNotesArray: returning " + notes.size() + " notes");
        return notes;
    }

    private int findMatchingBracket(String json, int start) {
        if (start >= json.length() || json.charAt(start) != '[') return -1;
        int depth = 1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"') {
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
        public void fine(String msg) { System.out.println("[DEBUG] " + msg); }
    }
}
