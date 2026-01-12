package cn.keevol.keenotes.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.ActivityMainBinding
import cn.keevol.keenotes.network.WebSocketService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupStatusBar()
        setupNavigation()
        connectWebSocket()
    }
    
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        // Setup BottomNavigationView with NavController
        binding.bottomNavigation.setupWithNavController(navController)
        
        // Handle navigation visibility for statusBar
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.noteFragment, R.id.reviewFragment, R.id.searchFragment -> {
                    binding.statusBar.isVisible = true
                }
                R.id.settingsFragment -> {
                    binding.statusBar.isVisible = false
                }
            }
        }
    }
    
    private fun setupStatusBar() {
        val app = application as KeeNotesApp
        
        // Observe WebSocket connection state for sync channel
        lifecycleScope.launch {
            app.webSocketService.connectionState.collectLatest { state ->
                updateSyncChannelStatus(state)
            }
        }
        
        // Observe settings changes for send channel status
        lifecycleScope.launch {
            app.settingsRepository.isConfigured.collectLatest { configured ->
                updateSendChannelStatus(configured)
            }
        }
    }
    
    private fun updateSendChannelStatus(configured: Boolean) {
        val (text, color) = if (!configured) {
            "Not Configured" to getColor(R.color.warning)
        } else {
            // TODO: Add network connectivity check
            "Ready" to getColor(R.color.success)
        }
        
        binding.sendIndicator.setColorFilter(color)
        binding.sendStatusText.text = text
        binding.sendStatusText.setTextColor(color)
    }
    
    private fun updateSyncChannelStatus(state: WebSocketService.ConnectionState) {
        val (text, color) = when (state) {
            WebSocketService.ConnectionState.CONNECTED -> 
                getString(R.string.status_connected) to getColor(R.color.success)
            WebSocketService.ConnectionState.CONNECTING -> 
                getString(R.string.status_connecting) to getColor(R.color.warning)
            WebSocketService.ConnectionState.DISCONNECTED -> 
                getString(R.string.status_disconnected) to getColor(R.color.text_secondary)
        }
        
        binding.syncIndicator.setColorFilter(color)
        binding.syncStatusText.text = text
        binding.syncStatusText.setTextColor(color)
    }
    
    fun setSyncChannelSyncing() {
        val color = getColor(R.color.warning)
        binding.syncIndicator.setColorFilter(color)
        binding.syncStatusText.text = "Syncing..."
        binding.syncStatusText.setTextColor(color)
    }
    
    private fun connectWebSocket() {
        val app = application as KeeNotesApp
        
        lifecycleScope.launch {
            if (app.settingsRepository.isConfigured()) {
                app.webSocketService.connect()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        (application as KeeNotesApp).webSocketService.disconnect()
    }
}
