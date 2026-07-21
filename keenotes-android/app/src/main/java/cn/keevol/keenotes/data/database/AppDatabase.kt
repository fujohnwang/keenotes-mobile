package cn.keevol.keenotes.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.keevol.keenotes.data.dao.DebugLogDao
import cn.keevol.keenotes.data.dao.NoteDao
import cn.keevol.keenotes.data.dao.PendingNoteDao
import cn.keevol.keenotes.data.dao.SyncStateDao
import cn.keevol.keenotes.data.entity.DebugLog
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.data.entity.PendingNote
import cn.keevol.keenotes.data.entity.SyncState

@Database(
    entities = [Note::class, SyncState::class, DebugLog::class, PendingNote::class],
    version = 5,  // Incremented for pending note idempotency payload columns
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun debugLogDao(): DebugLogDao
    abstract fun pendingNoteDao(): PendingNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "pending_notes", "encryptedContent", "TEXT")
                addColumnIfMissing(db, "pending_notes", "requestId", "TEXT")
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            val exists = db.query("PRAGMA table_info($tableName)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (nameIndex >= 0 && cursor.moveToNext()) {
                    if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                found
            }

            if (!exists) {
                db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "keenotes.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()  // Allow schema changes
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
