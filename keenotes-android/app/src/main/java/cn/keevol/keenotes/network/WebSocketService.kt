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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * WebSocket service for real-time sync
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
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // No timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun connect() {
        scope.launch {
            try {
                val endpoint = settingsRepository.getEndpointUrl()
                val token = settingsRepository.getToken()
                
                if (endpoint.isBlank() || token.isBlank()) {
                    Log.w(TAG, "Not configured, skipping connection")
                    return@launch
                }
                
                // Convert HTTP URL to WebSocket URL
                val wsUrl = endpoint
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")
                    .replace("/notes", "/ws")
                
                _connectionState.value = ConnectionState.CONNECTING
                
                val request = Request.Builder()
                    .url("$wsUrl?token=$token")
                    .build()
                
                webSocket = client.newWebSocket(request, createWebSocketListener())
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.DISCONNECTED
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
            Log.i(TAG, "WebSocket connected")
            _connectionState.value = ConnectionState.CONNECTED
            
            // Request sync after connection
            scope.launch {
                requestSync()
            }
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
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            _connectionState.value = ConnectionState.DISCONNECTED
            
            // Auto-reconnect after delay
            scope.launch {
                delay(5000)
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    connect()
                }
            }
        }
    }
    
    private suspend fun requestSync() {
        val syncState = syncStateDao.getSyncState()
        val lastId = syncState?.lastSyncId ?: 0
        
        val message = JSONObject().apply {
            put("type", "sync_request")
            put("lastId", lastId)
        }
        
        webSocket?.send(message.toString())
    }
    
    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "sync_response" -> handleSyncResponse(json)
                "realtime_update" -> handleRealtimeUpdate(json)
                else -> Log.w(TAG, "Unknown message type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    private suspend fun handleSyncResponse(json: JSONObject) {
        val notesArray = json.optJSONArray("notes") ?: return
        val notes = mutableListOf<Note>()
        
        for (i in 0 until notesArray.length()) {
            val noteJson = notesArray.getJSONObject(i)
            val note = parseNote(noteJson)
            if (note != null) {
                notes.add(note)
            }
        }
        
        if (notes.isNotEmpty()) {
            noteDao.insertAll(notes)
            
            val maxId = notes.maxOf { it.id }
            val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            syncStateDao.updateSyncState(SyncState(lastSyncId = maxId, lastSyncTime = now))
            
            Log.i(TAG, "Synced ${notes.size} notes, lastId=$maxId")
        }
    }
    
    private suspend fun handleRealtimeUpdate(json: JSONObject) {
        val noteJson = json.optJSONObject("note") ?: return
        val note = parseNote(noteJson)
        
        if (note != null) {
            noteDao.insert(note)
            
            val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            syncStateDao.updateSyncState(SyncState(lastSyncId = note.id, lastSyncTime = now))
            
            Log.i(TAG, "Realtime update: note ${note.id}")
        }
    }
    
    private fun parseNote(json: JSONObject): Note? {
        return try {
            val id = json.getLong("id")
            val encryptedContent = json.getString("content")
            val createdAt = json.getString("createdAt")
            val isEncrypted = json.optBoolean("encrypted", false)
            
            val content = if (isEncrypted && cryptoService.isEncryptionEnabled()) {
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
    
    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}
