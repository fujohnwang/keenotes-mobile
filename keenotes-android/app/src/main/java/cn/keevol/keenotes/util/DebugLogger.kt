package cn.keevol.keenotes.util

import android.util.Log
import cn.keevol.keenotes.data.dao.DebugLogDao
import cn.keevol.keenotes.data.entity.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug logger that writes to both Logcat and SQLite database
 */
object DebugLogger {
    
    private var debugLogDao: DebugLogDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun init(dao: DebugLogDao) {
        debugLogDao = dao
    }
    
    fun log(tag: String, message: String) {
        // Log to Logcat
        Log.i(tag, message)
        
        // Log to database
        debugLogDao?.let { dao ->
            scope.launch {
                try {
                    dao.insert(DebugLog(tag = tag, message = message))
                } catch (e: Exception) {
                    Log.e("DebugLogger", "Failed to save log: ${e.message}")
                }
            }
        }
    }
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\nException: ${throwable.message}\n${throwable.stackTraceToString().take(500)}"
        } else {
            message
        }
        
        Log.e(tag, fullMessage, throwable)
        
        debugLogDao?.let { dao ->
            scope.launch {
                try {
                    dao.insert(DebugLog(tag = "$tag [ERROR]", message = fullMessage))
                } catch (e: Exception) {
                    Log.e("DebugLogger", "Failed to save error log: ${e.message}")
                }
            }
        }
    }
}
