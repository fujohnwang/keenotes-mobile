package cn.keevol.keenotes.data.dao

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import cn.keevol.keenotes.data.entity.Note
import kotlinx.coroutines.flow.Flow
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes WHERE content LIKE '%' || :query || '%' ORDER BY id DESC LIMIT 100")
    suspend fun searchNotes(query: String): List<Note>
    
    @Query("SELECT * FROM notes WHERE createdAt >= datetime('now', '-' || :days || ' days') ORDER BY id DESC")
    suspend fun getNotesForReview(days: Int): List<Note>
    
    /**
     * Flow version - auto-updates when database changes
     */
    @Query("SELECT * FROM notes WHERE createdAt >= datetime('now', '-' || :days || ' days') ORDER BY id DESC")
    fun getNotesForReviewFlow(days: Int): Flow<List<Note>>
    
    /**
     * Paginated version for memory optimization
     */
    @Query("SELECT * FROM notes WHERE createdAt >= datetime('now', '-' || :days || ' days') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun getNotesForReviewPaged(days: Int, limit: Int, offset: Int): List<Note>
    
    /**
     * Get count of notes for review period
     */
    @Query("SELECT COUNT(*) FROM notes WHERE createdAt >= datetime('now', '-' || :days || ' days')")
    suspend fun getNotesCountForReview(days: Int): Int
    
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

    @RawQuery(observedEntities = [Note::class])
    fun observeNotesByQuery(query: SupportSQLiteQuery): Flow<List<Note>>

    @RawQuery
    suspend fun getNotesByQuery(query: SupportSQLiteQuery): List<Note>

    fun getNotesOnThisDayFlow(
        localDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Flow<List<Note>> = observeNotesByQuery(buildOnThisDayQuery(localDate, zoneId))

    suspend fun getNotesOnThisDay(
        localDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<Note> = getNotesByQuery(buildOnThisDayQuery(localDate, zoneId))
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>)
    
    @Query("DELETE FROM notes")
    suspend fun deleteAll()
    
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    companion object {
        private val UTC_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private fun buildOnThisDayQuery(localDate: LocalDate, zoneId: ZoneId): SupportSQLiteQuery {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<Any>()

            for (year in 2000 until localDate.year) {
                try {
                    val startUtc = localDate
                        .withYear(year)
                        .atStartOfDay(zoneId)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime()
                        .format(UTC_TIMESTAMP_FORMATTER)
                    val endUtc = localDate
                        .withYear(year)
                        .atTime(23, 59, 59)
                        .atZone(zoneId)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime()
                        .format(UTC_TIMESTAMP_FORMATTER)

                    clauses += "(createdAt >= ? AND createdAt <= ?)"
                    args += startUtc
                    args += endUtc
                } catch (_: DateTimeException) {
                    // Skip invalid local dates such as Feb 29 in non-leap years.
                }
            }

            if (clauses.isEmpty()) {
                return SimpleSQLiteQuery("SELECT * FROM notes WHERE 1 = 0")
            }

            val sql = buildString {
                append("SELECT * FROM notes WHERE ")
                append(clauses.joinToString(" OR "))
                append(" ORDER BY createdAt DESC")
            }
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}
