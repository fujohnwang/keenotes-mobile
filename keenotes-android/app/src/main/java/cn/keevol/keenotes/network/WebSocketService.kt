package cn.keevol.keenotes.network

import android.util.Log
import cn.keevol.keenotes.crypto.CryptoService
import cn.keevol.keenotes.data.dao.NoteDao
import cn.keevol.keenotes.data.dao.SyncStateDao
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.data.entity.SyncState
import cn.keevol.keenotes.data.repository.SettingsRepository
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
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val client: OkHttpClient = createClient()
    
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientId = UUID.randomUUID().toString()
    private var lastSyncId: Long = -1  // Match JavaFX: -1 means sync all
    
    // Batch sync buffer
    private val syncBatchBuffer = mutableListOf<Note>()
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
        
        scope.launch {
            try {
                val endpoint = settingsRepository.getEndpointUrl()
                val token = settingsRepository.getToken()
                
                if (endpoint.isBlank() || token.isBlank()) {
                    Log.w(TAG, "Not configured, skipping connection")
                    isConnecting = false
                    return@launch
                }
                
                // Parse URL and build WebSocket URL
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
                
                Log.i(TAG, "WebSocket connecting to: $wsUrl")
                _connectionState.value = ConnectionState.CONNECTING
                
                // Load last sync ID (default -1 if not set)
                val savedSyncId = syncStateDao.getSyncState()?.lastSyncId
                lastSyncId = savedSyncId ?: -1
                Log.i(TAG, "Loaded lastSyncId: $lastSyncId")
                
                // Build request with headers (match JavaFX)
                val origin = "${if (ssl) "https" else "http"}://$host"
                val request = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Origin", origin)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                Log.i(TAG, "Adding Authorization header: Bearer ${token.take(4)}...")
                
                webSocket = client.newWebSocket(request, createWebSocketListener())
                
            } catch (e: Exception) {
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
    }
    
    private fun createWebSocketListener() = object : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected successfully")
            isConnecting = false
            _connectionState.value = ConnectionState.CONNECTED
            
            // Send handshake (matching JavaFX)
            sendHandshake()
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                handleMessage(text)
            }
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            isConnecting = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            isConnecting = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }
    
    private fun sendHandshake() {
        val handshake = JSONObject().apply {
            put("type", "handshake")
            put("client_id", clientId)
            put("last_sync_id", lastSyncId)
        }
        
        val sent = webSocket?.send(handshake.toString()) ?: false
        Log.i(TAG, "Sent handshake with lastSyncId=$lastSyncId, success=$sent")
    }
    
    private suspend fun handleMessage(text: String) {
        try {
            Log.d(TAG, "Received message: $text")
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "sync_batch" -> handleSyncBatch(json)
                "sync_complete" -> handleSyncComplete(json)
                "realtime_update" -> handleRealtimeUpdate(json)
                "ping" -> {
                    // Respond to server ping
                    webSocket?.send("{\"type\":\"pong\"}")
                    Log.d(TAG, "Responded to ping with pong")
                }
                "pong" -> Log.d(TAG, "Received pong")
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
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    private suspend fun handleSyncBatch(json: JSONObject) {
        val batchId = json.optInt("batch_id", 0)
        val totalBatches = json.optInt("total_batches", 1)
        
        if (expectedBatches == 0) {
            expectedBatches = totalBatches
            syncBatchBuffer.clear()
        }
        
        val notesArray = json.optJSONArray("notes") ?: return
        
        for (i in 0 until notesArray.length()) {
            val noteJson = notesArray.getJSONObject(i)
            val note = parseNote(noteJson)
            if (note != null) {
                syncBatchBuffer.add(note)
            }
        }
        
        receivedBatches++
        Log.i(TAG, "Received batch $batchId/$totalBatches, buffer size=${syncBatchBuffer.size}")
    }
    
    private suspend fun handleSyncComplete(json: JSONObject) {
        val totalSynced = json.optInt("total_synced", 0)
        val newLastSyncId = json.optLong("last_sync_id", -1L)
        
        try {
            if (syncBatchBuffer.isNotEmpty()) {
                noteDao.insertAll(syncBatchBuffer)
                Log.i(TAG, "Batch inserted ${syncBatchBuffer.size} notes")
            }
            
            // Match JavaFX: only update if totalSynced > 0 and newLastSyncId > 0
            if (totalSynced > 0 && newLastSyncId > 0) {
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                syncStateDao.updateSyncState(SyncState(lastSyncId = newLastSyncId, lastSyncTime = now))
                lastSyncId = newLastSyncId
                Log.i(TAG, "Updated lastSyncId to: $newLastSyncId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save synced notes", e)
        }
        
        syncBatchBuffer.clear()
        expectedBatches = 0
        receivedBatches = 0
        
        Log.i(TAG, "Sync complete: $totalSynced notes")
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
            
            val content = if (cryptoService.isEncryptionEnabled()) {
                try {
                    cryptoService.decrypt(encryptedContent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decrypt note $id", e)
                    "[Decryption failed]"
                }
            } else {
                encryptedContent
            }
            
            Note(id = id, content = content, createdAt = createdAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing note", e)
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
