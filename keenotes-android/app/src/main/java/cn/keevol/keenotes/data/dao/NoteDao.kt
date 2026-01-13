package cn.keevol.keenotes.data.dao

import androidx.room.*
import cn.keevol.keenotes.data.entity.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes WHERE content LIKE '%' || :query || '%' ORDER BY id DESC LIMIT 100")
    suspend fun searchNotes(query: String): List<Note>
    
    @Query("SELECT * FROM notes WHERE createdAt >= :since ORDER BY id DESC")
    suspend fun getNotesForReview(since: String): List<Note>
    
    /**
     * Flow version - auto-updates when database changes
     */
    @Query("SELECT * FROM notes WHERE createdAt >= :since ORDER BY id DESC")
    fun getNotesForReviewFlow(since: String): Flow<List<Note>>
    
    @Query("SELECT * FROM notes ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentNotes(limit: Int = 100): List<Note>
    
    @Query("SELECT COUNT(*) FROM notes")
    suspend fun getNoteCount(): Int
    
    @Query("SELECT COUNT(*) FROM notes")
    fun getNoteCountFlow(): Flow<Int>
    
    @Query("SELECT MIN(createdAt) FROM notes")
    suspend fun getOldestNoteDate(): String?
    
    @Query("SELECT MAX(id) FROM notes")
    suspend fun getMaxId(): Long?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>)
    
    @Query("DELETE FROM notes")
    suspend fun deleteAll()
    
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
