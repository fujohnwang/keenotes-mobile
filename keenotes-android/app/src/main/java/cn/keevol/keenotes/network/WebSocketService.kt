package cn.keevol.keenotes.network

import android.util.Log
import cn.keevol.keenotes.crypto.CryptoService
import cn.keevol.keenotes.data.dao.NoteDao
import cn.keevol.keenotes.data.dao.SyncStateDao
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.data.entity.SyncState
import cn.keevol.keenotes.data.repository.SettingsRepository
import cn.keevol.keenotes.util.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebSocket service for real-time sync
 * Matches JavaFX WebSocketClientService logic
 */
class WebSocketService(
    private val settingsRepository: SettingsRepository,
    private val cryptoService: CryptoService,
    private val noteDao: NoteDao,
    private val syncStateDao: SyncStateDao
) {
    companion object {
        private const val TAG = "WebSocketService"
    }
    
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }
    
    enum class SyncState {
        IDLE, SYNCING, COMPLETED
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState
    
    private val client: OkHttpClient = createClient()
    
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Use single-threaded dispatcher for message processing to maintain order
    private val messageDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    private val clientId = UUID.randomUUID().toString()
    
    // Use @Volatile to ensure visibility across threads (OkHttp callbacks run on different thread)
    @Volatile
    private var lastSyncId: Long = -1  // Match JavaFX: -1 means sync all
    
    // Cached encryption password to avoid nested runBlocking calls
    @Volatile
    private var cachedPassword: String? = null
    
    // Batch sync progress tracking
    @Volatile
    private var expectedBatches = 0
    private var receivedBatches = 0
    
    private fun createClient(): OkHttpClient {
        return try {
            // Trust all certificates (for development)
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .retryOnConnectionFailure(false)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "SSL config failed", e)
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }
    
    private var isConnecting = false
    
    fun connect() {
        // Prevent duplicate connections (match JavaFX logic)
        if (_connectionState.value == ConnectionState.CONNECTED || isConnecting) {
            Log.i(TAG, "Already connected or connecting, skipping")
            return
        }
        
        isConnecting = true
        DebugLogger.log("WebSocket", "connect() called, starting scope.launch")
        
        scope.launch {
            try {
                DebugLogger.log("WebSocket", "Inside scope.launch, about to get endpoint")
                val endpoint = settingsRepository.getEndpointUrl()
                DebugLogger.log("WebSocket", "Got endpoint: ${endpoint.take(30)}...")
                val token = settingsRepository.getToken()
                DebugLogger.log("WebSocket", "Got token: ${token.take(10)}...")
                
                if (endpoint.isBlank() || token.isBlank()) {
                    Log.w(TAG, "Not configured, skipping connection")
                    isConnecting = false
                    return@launch
                }
                
                // CRITICAL: Cache the encryption password BEFORE WebSocket connection
                DebugLogger.log("WebSocket", "About to get encryption password")
                cachedPassword = settingsRepository.getEncryptionPassword().takeIf { it.isNotBlank() }
                DebugLogger.log("WebSocket", "Cached password: ${if (cachedPassword != null) "yes" else "no"}")
                
                // Parse URL and build WebSocket URL
                DebugLogger.log("WebSocket", "Parsing URL")
                val uri = URI(endpoint)
                val host = uri.host
                val ssl = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)
                val port = if (uri.port == -1) (if (ssl) 443 else 80) else uri.port
                
                // Build path - append /ws if needed
                var path = uri.path ?: ""
                if (path.isEmpty() || path == "/") {
                    path = "/ws"
                } else if (!path.endsWith("/ws")) {
                    path = "$path/ws"
                }
                
                val protocol = if (ssl) "wss" else "ws"
                val wsUrl = "$protocol://$host:$port$path"
                
                DebugLogger.log("WebSocket", "Connecting to: $wsUrl")
                _connectionState.value = ConnectionState.CONNECTING
                
                // CRITICAL: Load last sync ID BEFORE creating WebSocket
                DebugLogger.log("WebSocket", "About to get sync state from DB")
                val syncState = syncStateDao.getSyncState()
                lastSyncId = syncState?.lastSyncId ?: -1
                DebugLogger.log("WebSocket", "Loaded lastSyncId: $lastSyncId")
                
                // Build request with headers (match JavaFX)
                val origin = "${if (ssl) "https" else "http"}://$host"
                val request = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Origin", origin)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                DebugLogger.log("WebSocket", "About to create WebSocket")
                
                // Create WebSocket with try-catch
                try {
                    webSocket = client.newWebSocket(request, createWebSocketListener())
                    DebugLogger.log("WebSocket", "WebSocket created successfully")
                } catch (e: Exception) {
                    DebugLogger.error("WebSocket", "newWebSocket() threw exception", e)
                    throw e
                }
                
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "Connection error", e)
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                isConnecting = false
                scheduleReconnect()
            }
        }
    }
    
    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _syncState.value = SyncState.IDLE
    }
    
    /**
     * Reset internal state for reconnection with new configuration
     */
    fun resetState() {
        lastSyncId = -1
        cachedPassword = null
        expectedBatches = 0
        receivedBatches = 0
        _syncState.value = SyncState.IDLE
    }
    
    private fun createWebSocketListener() = object : WebSocketListener() {
        
        override fun onOpen(ws: WebSocket, response: Response) {
            try {
                DebugLogger.log("WebSocket", "onOpen START")
                Log.i(TAG, "WebSocket onOpen called, response code: ${response.code}")
                isConnecting = false
                webSocket = ws  // Store the WebSocket reference
                DebugLogger.log("WebSocket", "onOpen: webSocket assigned")
                _connectionState.value = ConnectionState.CONNECTED
                DebugLogger.log("WebSocket", "onOpen: state set to CONNECTED")
                
                // Send handshake (matching JavaFX)
                DebugLogger.log("WebSocket", "onOpen: about to send handshake...")
                sendHandshake()
                DebugLogger.log("WebSocket", "onOpen: handshake sent")
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "onOpen EXCEPTION", e)
            }
        }
        
        override fun onMessage(ws: WebSocket, text: String) {
            try {
                DebugLogger.log("WebSocket", "onMessage received, length=${text.length}")
                // Use single-threaded dispatcher to maintain message order
                scope.launch(messageDispatcher) {
                    handleMessage(text)
                }
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "onMessage EXCEPTION", e)
            }
        }
        
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            try {
                DebugLogger.log("WebSocket", "onClosing: $code $reason")
                ws.close(1000, null)
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "onClosing EXCEPTION", e)
            }
        }
        
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            try {
                DebugLogger.log("WebSocket", "onClosed: $code $reason")
                isConnecting = false
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "onClosed EXCEPTION", e)
            }
        }
        
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            try {
                DebugLogger.error("WebSocket", "onFailure: ${t.message}", t)
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnecting = false
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            } catch (e: Exception) {
                DebugLogger.error("WebSocket", "onFailure handler EXCEPTION", e)
            }
        }
    }
    
    private fun sendHandshake() {
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "sendHandshake: webSocket is null!")
            return
        }
        
        // Log the current lastSyncId value
        Log.i(TAG, "sendHandshake: current lastSyncId = $lastSyncId (type: ${lastSyncId::class.simpleName})")
        
        val handshake = JSONObject().apply {
            put("type", "handshake")
            put("client_id", clientId)
            put("last_sync_id", lastSyncId)
        }
        
        val message = handshake.toString()
        Log.i(TAG, "Sending handshake JSON: $message")
        
        val sent = ws.send(message)
        if (sent) {
            Log.i(TAG, "Handshake sent successfully")
        } else {
            Log.e(TAG, "Failed to send handshake!")
        }
    }
    
    private suspend fun handleMessage(text: String) {
        try {
            Log.i(TAG, "Received message: $text")
            val json = JSONObject(text)
            val type = json.optString("type")
            
            Log.i(TAG, "Processing message type: $type")
            
            when (type) {
                "sync_batch" -> {
                    Log.i(TAG, "Handling sync_batch")
                    handleSyncBatch(json)
                }
                "sync_complete" -> {
                    Log.i(TAG, "Handling sync_complete")
                    handleSyncComplete(json)
                }
                "realtime_update" -> {
                    Log.i(TAG, "Handling realtime_update")
                    handleRealtimeUpdate(json)
                }
                "ping" -> {
                    // Respond to server ping
                    webSocket?.send("{\"type\":\"pong\"}")
                    Log.i(TAG, "Responded to ping with pong")
                }
                "pong" -> Log.i(TAG, "Received pong")
                "error" -> {
                    val errorMsg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Server error: $errorMsg")
                }
                "new_note_ack" -> {
                    val id = json.optLong("id", -1)
                    Log.i(TAG, "Server acknowledged new note with id=$id")
                }
                else -> Log.w(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }
    
    private suspend fun handleSyncBatch(json: JSONObject) {
        val batchId = json.optInt("batch_id", 0)
        val totalBatches = json.optInt("total_batches", 1)
        
        Log.i(TAG, "handleSyncBatch: batch $batchId of $totalBatches")
        
        // Set syncing state
        _syncState.value = SyncState.SYNCING
        
        if (expectedBatches == 0) {
            expectedBatches = totalBatches
            Log.i(TAG, "Starting new sync, expecting $totalBatches batches")
        }
        
        val notesArray = json.optJSONArray("notes")
        if (notesArray == null) {
            Log.e(TAG, "CRITICAL: No notes array in sync_batch! JSON: $json")
            return
        }
        
        Log.i(TAG, "Processing ${notesArray.length()} notes in batch")
        
        val batchNotes = mutableListOf<Note>()
        var maxNoteId = -1L
        var failCount = 0
        
        for (i in 0 until notesArray.length()) {
            try {
                val noteJson = notesArray.getJSONObject(i)
                val note = parseNote(noteJson)
                if (note != null) {
                    batchNotes.add(note)
                    if (note.id > maxNoteId) {
                        maxNoteId = note.id
                    }
                } else {
                    failCount++
                    Log.e(TAG, "Failed to parse note at index $i")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Exception parsing note at index $i: ${e.message}", e)
            }
        }
        
        // 立即写入 DB（增量持久化）
        if (batchNotes.isNotEmpty()) {
            try {
                noteDao.insertAll(batchNotes)
                Log.i(TAG, "Batch $batchId: inserted ${batchNotes.size} notes to DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert batch $batchId: ${e.message}", e)
            }
        }
        
        // 更新 last_sync_id 为该 batch 中最大的 note ID（断点续传）
        if (maxNoteId > 0) {
            try {
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                syncStateDao.updateSyncState(cn.keevol.keenotes.data.entity.SyncState(lastSyncId = maxNoteId, lastSyncTime = now))
                lastSyncId = maxNoteId
                Log.i(TAG, "Batch $batchId: updated lastSyncId to $maxNoteId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update lastSyncId after batch $batchId: ${e.message}", e)
            }
        }
        
        receivedBatches++
        Log.i(TAG, "Batch $batchId/$totalBatches complete: success=${batchNotes.size}, fail=$failCount")
    }
    
    private suspend fun handleSyncComplete(json: JSONObject) {
        val totalSynced = json.optInt("total_synced", 0)
        val newLastSyncId = json.optLong("last_sync_id", -1L)
        
        Log.i(TAG, "handleSyncComplete: totalSynced=$totalSynced, newLastSyncId=$newLastSyncId")
        
        try {
            // 以服务器返回的 last_sync_id 为准做最终更新
            if (totalSynced > 0 && newLastSyncId > 0) {
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                syncStateDao.updateSyncState(cn.keevol.keenotes.data.entity.SyncState(lastSyncId = newLastSyncId, lastSyncTime = now))
                lastSyncId = newLastSyncId
                Log.i(TAG, "Updated lastSyncId to: $newLastSyncId")
            }
            
            _syncState.value = SyncState.COMPLETED
            Log.i(TAG, "Sync complete: $totalSynced notes processed, state set to COMPLETED")
            
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to update lastSyncId on sync complete: ${e.message}", e)
            _syncState.value = SyncState.IDLE
        } finally {
            expectedBatches = 0
            receivedBatches = 0
        }
    }
    
    private suspend fun handleRealtimeUpdate(json: JSONObject) {
        val noteJson = json.optJSONObject("note") ?: return
        val note = parseNote(noteJson)
        
        if (note != null) {
            noteDao.insert(note)
            
            // Update lastSyncId after realtime update
            if (note.id > lastSyncId) {
                lastSyncId = note.id
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                syncStateDao.updateSyncState(SyncState(lastSyncId = note.id, lastSyncTime = now))
                Log.i(TAG, "Updated lastSyncId to ${note.id} after realtime update")
            }
            
            Log.i(TAG, "Realtime update: note ${note.id}")
        }
    }
    
    private fun parseNote(json: JSONObject): Note? {
        return try {
            val id = json.getLong("id")
            val encryptedContent = json.getString("content")
            val createdAt = json.optString("created_at", json.optString("createdAt", ""))
            val channel = json.optString("channel", "default")
            
            val password = cachedPassword
            Log.i(TAG, "parseNote: id=$id, createdAt=$createdAt, channel=$channel, hasPassword=${password != null}")
            
            val content = if (password != null) {
                try {
                    // Use decryptWithPassword to avoid nested runBlocking
                    val decrypted = cryptoService.decryptWithPassword(encryptedContent, password)
                    Log.i(TAG, "Decrypted note $id successfully, length=${decrypted.length}")
                    decrypted
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt note $id: ${e.message}, storing encrypted content", e)
                    e.printStackTrace()
                    encryptedContent
                }
            } else {
                Log.w(TAG, "No encryption password, using raw content for note $id")
                encryptedContent
            }
            
            Note(id = id, content = content, channel = channel, createdAt = createdAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing note JSON: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
    
    private fun scheduleReconnect() {
        scope.launch {
            delay(5000)
            if (_connectionState.value == ConnectionState.DISCONNECTED) {
                Log.i(TAG, "Attempting reconnect...")
                connect()
            }
        }
    }
    
    fun shutdown() {
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.cancel()
    }
}
