package cn.keevol.keenotes.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
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
    
    private fun setupStatusBar() {
        val app = application as KeeNotesApp
        
        // Observe WebSocket connection state for sync channel
        lifecycleScope.launch {
            app.webSocketService.connectionState.collectLatest { state ->
                updateSyncChannelStatus(state)
            }
        }
        
        // Update send channel status based on configuration
        updateSendChannelStatus()
    }
    
    private fun updateSendChannelStatus() {
        val app = application as KeeNotesApp
        val settings = app.settingsService
        
        val (text, color) = if (settings.endpointUrl.isBlank() || settings.token.isBlank()) {
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
    
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        // Handle navigation visibility
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.noteFragment -> {
                    binding.headerLayout.isVisible = true
                    binding.statusBar.isVisible = true
                }
                R.id.reviewFragment -> {
                    binding.headerLayout.isVisible = false
                    binding.statusBar.isVisible = true
                }
                R.id.settingsFragment -> {
                    binding.headerLayout.isVisible = false
                    binding.statusBar.isVisible = false
                }
            }
        }
        
        // Header button clicks
        binding.btnReview.setOnClickListener {
            navController.navigate(R.id.reviewFragment)
        }
        
        binding.btnSettings.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
        }
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
