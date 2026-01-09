package cn.keevol.keenotes.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.ActivityMainBinding
import cn.keevol.keenotes.network.WebSocketService
import cn.keevol.keenotes.ui.review.NotesAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val searchAdapter = NotesAdapter()
    private var searchJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupSearchOverlay()
        setupSearchField()
        setupStatusBar()
        setupNavigation()
        connectWebSocket()
    }
    
    private fun setupSearchOverlay() {
        binding.searchResultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
        }
    }
    
    private fun setupSearchField() {
        binding.searchField.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            
            // Show/hide clear button
            binding.searchClearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Cancel previous search
            searchJob?.cancel()
            
            if (query.isEmpty()) {
                // Hide search overlay
                binding.searchOverlay.visibility = View.GONE
            } else {
                // Show search overlay and perform search with debounce
                binding.searchOverlay.visibility = View.VISIBLE
                binding.searchLoading.visibility = View.VISIBLE
                binding.searchResultsRecyclerView.visibility = View.GONE
                binding.searchEmptyText.visibility = View.GONE
                
                searchJob = lifecycleScope.launch {
                    delay(500) // 500ms debounce
                    performSearch(query)
                }
            }
        }
        
        // Clear button click
        binding.searchClearButton.setOnClickListener {
            binding.searchField.text?.clear()
            binding.searchField.clearFocus()
        }
    }
    
    private suspend fun performSearch(query: String) {
        val app = application as KeeNotesApp
        
        try {
            val results = app.database.noteDao().searchNotes(query)
            
            binding.searchLoading.visibility = View.GONE
            
            if (results.isEmpty()) {
                binding.searchEmptyText.visibility = View.VISIBLE
                binding.searchEmptyText.text = "No results found for \"$query\""
                binding.searchResultsRecyclerView.visibility = View.GONE
            } else {
                binding.searchEmptyText.visibility = View.GONE
                binding.searchResultsRecyclerView.visibility = View.VISIBLE
                searchAdapter.submitList(results)
            }
        } catch (e: Exception) {
            binding.searchLoading.visibility = View.GONE
            binding.searchEmptyText.visibility = View.VISIBLE
            binding.searchEmptyText.text = "Search failed: ${e.message}"
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
