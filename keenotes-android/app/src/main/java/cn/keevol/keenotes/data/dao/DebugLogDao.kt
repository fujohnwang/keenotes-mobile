package cn.keevol.keenotes.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.keevol.keenotes.data.entity.DebugLog

@Dao
interface DebugLogDao {
    
    @Insert
    suspend fun insert(log: DebugLog)
    
    @Query("SELECT * FROM debug_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 100): List<DebugLog>
    
    @Query("DELETE FROM debug_logs")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM debug_logs")
    suspend fun getCount(): Int
}
