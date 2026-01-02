package cn.keevol.keenotes.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync state entity - tracks the last synced note ID
 * When no record exists, it means client has never synced (use -1 in code)
 * 0 is a valid lastSyncId value (means synced up to note ID 0)
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey
    val id: Int = 1,  // Single row
    val lastSyncId: Long = 0,  // 0 is valid; -1 is used when no record exists
    val lastSyncTime: String? = null
)
