package cn.keevol.keenotes.data.dao

import androidx.room.*
import cn.keevol.keenotes.data.entity.PendingNote
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingNoteDao {

    @Insert
    suspend fun insert(note: PendingNote)

    @Query("SELECT * FROM pending_notes ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingNote>

    @Query("SELECT * FROM pending_notes ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PendingNote>>

    @Query("SELECT COUNT(*) FROM pending_notes")
    fun getCountFlow(): Flow<Int>

    @Query("DELETE FROM pending_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
