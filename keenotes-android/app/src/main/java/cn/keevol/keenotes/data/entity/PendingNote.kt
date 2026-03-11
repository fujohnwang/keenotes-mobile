package cn.keevol.keenotes.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pending note entity for offline cache
 * Stores notes that failed to send due to network unavailability
 */
@Entity(tableName = "pending_notes")
data class PendingNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val channel: String = "mobile-android",
    val createdAt: String
)
