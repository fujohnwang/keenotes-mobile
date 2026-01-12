package cn.keevol.keenotes

import android.app.Application
import cn.keevol.keenotes.crypto.CryptoService
import cn.keevol.keenotes.data.database.AppDatabase
import cn.keevol.keenotes.data.repository.SettingsRepository
import cn.keevol.keenotes.network.ApiService
import cn.keevol.keenotes.network.WebSocketService
import cn.keevol.keenotes.util.DebugLogger
import kotlinx.coroutines.runBlocking

/**
 * Application class - provides dependency injection
 */
class KeeNotesApp : Application() {
    
    lateinit var database: AppDatabase
        private set
    
    lateinit var settingsRepository: SettingsRepository
        private set
    
    lateinit var cryptoService: CryptoService
        private set
    
    lateinit var apiService: ApiService
        private set
    
    lateinit var webSocketService: WebSocketService
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize dependencies
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        
        // Initialize DebugLogger
        DebugLogger.init(database.debugLogDao())
        DebugLogger.log("KeeNotesApp", "Application started")
        
        cryptoService = CryptoService {
            runBlocking { settingsRepository.getEncryptionPassword().takeIf { it.isNotBlank() } }
        }
        
        apiService = ApiService(settingsRepository, cryptoService)
        
        webSocketService = WebSocketService(
            settingsRepository,
            cryptoService,
            database.noteDao(),
            database.syncStateDao()
        )
    }
    
    override fun onTerminate() {
        super.onTerminate()
        webSocketService.shutdown()
    }
}
