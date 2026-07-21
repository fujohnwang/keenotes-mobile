package cn.keevol.keenotes.network

import android.util.Log
import cn.keevol.keenotes.data.dao.PendingNoteDao
import cn.keevol.keenotes.data.entity.PendingNote
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/**
 * 离线暂存笔记的调度服务
 * - 暂存发送失败的笔记
 * - 定时重试发送（30分钟间隔）
 * - WebSocket 重连时立即触发重试
 */
class PendingNoteService(
    private val pendingNoteDao: PendingNoteDao,
    private val apiService: ApiService,
    private val webSocketService: WebSocketService
) {
    companion object {
        private const val TAG = "PendingNoteService"
        private const val RETRY_INTERVAL_MS = 30L * 60 * 1000 // 30 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryJob: Job? = null
    private var isRetrying = false

    val pendingCountFlow: Flow<Int> = pendingNoteDao.getCountFlow()
    val pendingNotesFlow: Flow<List<PendingNote>> = pendingNoteDao.getAllFlow()

    fun startRetryScheduler() {
        if (retryJob != null) return
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                retryPendingNotes()
            }
        }
        Log.i(TAG, "Retry scheduler started (interval: 30 min)")

        // WebSocket 重连成功时触发重试
        scope.launch {
            webSocketService.connectionState.collect { state ->
                if (state == WebSocketService.ConnectionState.CONNECTED) {
                    val notes = pendingNoteDao.getAll()
                    if (notes.isNotEmpty()) {
                        Log.i(TAG, "Network restored, retrying ${notes.size} pending notes")
                        retryPendingNotes()
                    }
                }
            }
        }
    }

    /** 暂存一条笔记到本地 */
    fun savePendingNote(content: String, channel: String = "mobile-android") {
        scope.launch {
            try {
                val preparedNote = apiService.prepareNote(content, channel)
                pendingNoteDao.insert(preparedNote.toPendingNote())
                Log.i(TAG, "Note saved to pending")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save pending note: ${e.message}", e)
            }
        }
    }

    fun savePendingNote(preparedNote: PreparedNote) {
        scope.launch {
            try {
                pendingNoteDao.insert(preparedNote.toPendingNote())
                Log.i(TAG, "Prepared note saved to pending")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save prepared pending note: ${e.message}", e)
            }
        }
    }

    /** 逐条重试发送 pending notes */
    private suspend fun retryPendingNotes() {
        if (isRetrying) return
        isRetrying = true

        try {
            val pendingNotes = pendingNoteDao.getAll()
            if (pendingNotes.isEmpty()) return

            Log.i(TAG, "Retrying ${pendingNotes.size} pending notes...")

            for (note in pendingNotes) {
                val result = if (!note.encryptedContent.isNullOrEmpty() && !note.requestId.isNullOrEmpty()) {
                    apiService.postPreparedNote(
                        PreparedNote(
                            content = note.content,
                            encryptedContent = note.encryptedContent,
                            channel = note.channel,
                            createdAt = note.createdAt,
                            requestId = note.requestId
                        )
                    )
                } else {
                    apiService.postNote(note.content)
                }
                if (result.success) {
                    pendingNoteDao.deleteById(note.id)
                    Log.i(TAG, "Pending note sent, id=${note.id}")
                } else {
                    Log.w(TAG, "Retry failed: ${result.message}, stopping")
                    if (result.networkError) {
                        webSocketService.markConnectionSuspect("pending-retry-network-error")
                    }
                    break
                }
            }
        } finally {
            isRetrying = false
        }
    }

    /** 检查网络是否可用 */
    fun isNetworkAvailable(): Boolean {
        return webSocketService.connectionState.value == WebSocketService.ConnectionState.CONNECTED
    }

    fun shutdown() {
        retryJob?.cancel()
        scope.cancel()
    }

    private fun PreparedNote.toPendingNote(): PendingNote {
        return PendingNote(
            content = content,
            channel = channel,
            createdAt = createdAt,
            encryptedContent = encryptedContent,
            requestId = requestId
        )
    }
}
