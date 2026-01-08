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
        
        lifecycleScope.launch {
            app.webSocketService.connectionState.collectLatest { state ->
                updateConnectionStatus(state)
            }
        }
    }
    
    private fun updateConnectionStatus(state: WebSocketService.ConnectionState) {
        val (text, color) = when (state) {
            WebSocketService.ConnectionState.CONNECTED -> 
                getString(R.string.status_connected) to getColor(R.color.success)
            WebSocketService.ConnectionState.CONNECTING -> 
                getString(R.string.status_connecting) to getColor(R.color.warning)
            WebSocketService.ConnectionState.DISCONNECTED -> 
                getString(R.string.status_disconnected) to getColor(R.color.error)
        }
        
        binding.statusIndicator.setColorFilter(color)
        binding.statusText.text = text
        binding.statusText.setTextColor(color)
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
