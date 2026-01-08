package cn.keevol.keenotes.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cn.keevol.keenotes.data.dao.NoteDao
import cn.keevol.keenotes.data.dao.SyncStateDao
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.data.entity.SyncState

@Database(
    entities = [Note::class, SyncState::class],
    version = 2,  // Incremented for index addition
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun noteDao(): NoteDao
    abstract fun syncStateDao(): SyncStateDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "keenotes.db"
                )
                    .fallbackToDestructiveMigration()  // Allow schema changes
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
