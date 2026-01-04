package cn.keevol.keenotes.data.dao

import androidx.room.*
import cn.keevol.keenotes.data.entity.SyncState

@Dao
interface SyncStateDao {
    
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getSyncState(): SyncState?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncState(state: SyncState)
    
    @Query("UPDATE sync_state SET lastSyncId = :lastId, lastSyncTime = :time WHERE id = 1")
    suspend fun updateLastSync(lastId: Long, time: String)
    
    @Query("DELETE FROM sync_state")
    suspend fun reset()
    
    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()
}
