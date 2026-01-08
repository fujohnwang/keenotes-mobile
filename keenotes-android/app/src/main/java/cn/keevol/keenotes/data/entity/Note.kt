package cn.keevol.keenotes.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Note entity for Room database
 * Stores decrypted note content locally for search and review
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["createdAt"])]
)
data class Note(
    @PrimaryKey
    val id: Long,
    val content: String,
    val createdAt: String,  // ISO 8601 format
    val syncedAt: Long = System.currentTimeMillis()
)
