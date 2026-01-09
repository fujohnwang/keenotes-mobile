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
    
    // Batch sync buffer - use synchronized list for thread safety
    private val syncBatchBuffer = java.util.Collections.synchronizedList(mutableListOf<Note>())
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
        
        scope.launch {
            try {
                val endpoint = settingsRepository.getEndpointUrl()
                val token = settingsRepository.getToken()
                
                if (endpoint.isBlank() || token.isBlank()) {
                    Log.w(TAG, "Not configured, skipping connection")
                    isConnecting = false
                    return@launch
                }
                
                // CRITICAL: Cache the encryption password BEFORE WebSocket connection
                // This avoids nested runBlocking calls in onMessage callback
                cachedPassword = settingsRepository.getEncryptionPassword().takeIf { it.isNotBlank() }
                Log.i(TAG, "Cached encryption password: ${if (cachedPassword != null) "yes (${cachedPassword!!.length} chars)" else "no"}")
                
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
                
                // CRITICAL: Load last sync ID BEFORE creating WebSocket
                // OkHttp's onOpen callback runs on a different thread, so we must
                // ensure lastSyncId is set before newWebSocket() is called
                // If no sync_state record exists, use -1 (means never synced)
                val syncState = syncStateDao.getSyncState()
                lastSyncId = syncState?.lastSyncId ?: -1
                Log.i(TAG, "Loaded lastSyncId: $lastSyncId (syncState exists: ${syncState != null})")
                
                // Build request with headers (match JavaFX)
                val origin = "${if (ssl) "https" else "http"}://$host"
                val request = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Origin", origin)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                Log.i(TAG, "Adding Authorization header: Bearer ${token.take(4)}...")
                
                // Create WebSocket - onOpen callback will use the lastSyncId we just set
                // @Volatile ensures lastSyncId is visible to OkHttp's callback thread
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
        _syncState.value = SyncState.IDLE
    }
    
    /**
     * Reset internal state for reconnection with new configuration
     */
    fun resetState() {
        lastSyncId = -1
        cachedPassword = null
        synchronized(syncBatchBuffer) {
            syncBatchBuffer.clear()
        }
        expectedBatches = 0
        receivedBatches = 0
        _syncState.value = SyncState.IDLE
    }
    
    private fun createWebSocketListener() = object : WebSocketListener() {
        
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket onOpen called, response code: ${response.code}")
            isConnecting = false
            webSocket = ws  // Store the WebSocket reference
            _connectionState.value = ConnectionState.CONNECTED
            
            // Send handshake (matching JavaFX)
            Log.i(TAG, "About to send handshake...")
            sendHandshake()
            Log.i(TAG, "Handshake sent, waiting for server response...")
        }
        
        override fun onMessage(ws: WebSocket, text: String) {
            Log.i(TAG, "onMessage received, length=${text.length}")
            // Use single-threaded dispatcher to maintain message order
            // This avoids blocking OkHttp's callback thread while ensuring sequential processing
            scope.launch(messageDispatcher) {
                handleMessage(text)
            }
        }
        
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            ws.close(1000, null)
        }
        
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            isConnecting = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
        
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            isConnecting = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
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
        Log.i(TAG, "handleSyncBatch: raw JSON keys = ${json.keys().asSequence().toList()}")
        
        // Set syncing state
        _syncState.value = SyncState.SYNCING
        
        if (expectedBatches == 0) {
            expectedBatches = totalBatches
            synchronized(syncBatchBuffer) {
                syncBatchBuffer.clear()
            }
            Log.i(TAG, "Starting new sync, expecting $totalBatches batches")
        }
        
        val notesArray = json.optJSONArray("notes")
        if (notesArray == null) {
            Log.e(TAG, "CRITICAL: No notes array in sync_batch! JSON: $json")
            return
        }
        
        Log.i(TAG, "Processing ${notesArray.length()} notes in batch")
        
        var successCount = 0
        var failCount = 0
        for (i in 0 until notesArray.length()) {
            try {
                val noteJson = notesArray.getJSONObject(i)
                Log.i(TAG, "Parsing note $i: id=${noteJson.optLong("id")}")
                val note = parseNote(noteJson)
                if (note != null) {
                    synchronized(syncBatchBuffer) {
                        syncBatchBuffer.add(note)
                    }
                    successCount++
                    Log.i(TAG, "Note $i parsed successfully: id=${note.id}")
                } else {
                    failCount++
                    Log.e(TAG, "Failed to parse note at index $i: ${noteJson}")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Exception parsing note at index $i: ${e.message}", e)
            }
        }
        
        receivedBatches++
        Log.i(TAG, "Batch $batchId/$totalBatches complete: success=$successCount, fail=$failCount, buffer size=${syncBatchBuffer.size}")
    }
    
    private suspend fun handleSyncComplete(json: JSONObject) {
        val totalSynced = json.optInt("total_synced", 0)
        val newLastSyncId = json.optLong("last_sync_id", -1L)
        
        Log.i(TAG, "handleSyncComplete: totalSynced=$totalSynced, newLastSyncId=$newLastSyncId, bufferSize=${syncBatchBuffer.size}")
        
        try {
            // Copy buffer to avoid concurrent modification
            val notesToInsert = synchronized(syncBatchBuffer) {
                syncBatchBuffer.toList()
            }
            
            if (notesToInsert.isNotEmpty()) {
                Log.i(TAG, "Inserting ${notesToInsert.size} notes to database...")
                
                // CRITICAL: Insert notes BEFORE setting COMPLETED state
                // This ensures UI sees data when state changes to COMPLETED
                noteDao.insertAll(notesToInsert)
                
                Log.i(TAG, "SUCCESS: Inserted ${notesToInsert.size} notes to database")
                
                // Verify insertion
                val count = noteDao.getNoteCount()
                Log.i(TAG, "Database now has $count notes total")
                
                // Small delay to ensure Room's Flow has time to emit the update
                // Room uses a background thread to notify observers
                delay(100)
            } else {
                Log.w(TAG, "WARNING: syncBatchBuffer is empty, nothing to insert!")
            }
            
            // Match JavaFX: only update if totalSynced > 0 and newLastSyncId > 0
            if (totalSynced > 0 && newLastSyncId > 0) {
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                syncStateDao.updateSyncState(SyncState(lastSyncId = newLastSyncId, lastSyncTime = now))
                lastSyncId = newLastSyncId
                Log.i(TAG, "Updated lastSyncId to: $newLastSyncId")
            } else {
                Log.i(TAG, "Not updating lastSyncId: totalSynced=$totalSynced, newLastSyncId=$newLastSyncId")
            }
            
            // CRITICAL: Set COMPLETED state AFTER database insertion and delay
            // This prevents "No notes yet" flash in UI
            _syncState.value = SyncState.COMPLETED
            
            Log.i(TAG, "Sync complete: $totalSynced notes processed, state set to COMPLETED")
            
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to save synced notes: ${e.message}", e)
            e.printStackTrace()
            
            // Set IDLE state on error
            _syncState.value = SyncState.IDLE
        } finally {
            // Clean up buffer
            synchronized(syncBatchBuffer) {
                syncBatchBuffer.clear()
            }
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
            
            val password = cachedPassword
            Log.i(TAG, "parseNote: id=$id, createdAt=$createdAt, hasPassword=${password != null}")
            
            val content = if (password != null) {
                try {
                    // Use decryptWithPassword to avoid nested runBlocking
                    val decrypted = cryptoService.decryptWithPassword(encryptedContent, password)
                    Log.i(TAG, "Decrypted note $id successfully, length=${decrypted.length}")
                    decrypted
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt note $id: ${e.message}", e)
                    e.printStackTrace()
                    "[Decryption failed: ${e.message}]"
                }
            } else {
                Log.w(TAG, "No encryption password, using raw content for note $id")
                encryptedContent
            }
            
            Note(id = id, content = content, createdAt = createdAt)
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
