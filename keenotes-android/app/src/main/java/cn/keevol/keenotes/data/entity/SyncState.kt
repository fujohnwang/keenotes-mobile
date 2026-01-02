package cn.keevol.keenotes.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync state entity - tracks the last synced note ID
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey
    val id: Int = 1,  // Single row
    val lastSyncId: Long = 0,
    val lastSyncTime: String? = null
)
