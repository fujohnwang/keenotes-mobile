package cn.keevol.keenotes.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentSearchBinding
import cn.keevol.keenotes.network.WebSocketService
import cn.keevol.keenotes.ui.review.NotesAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    
    private val notesAdapter = NotesAdapter()
    private var searchJob: Job? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupHeader()
        setupSearchField()
        setupRecyclerView()
        setupSyncChannelStatus()
        
        // Auto-focus search input
        binding.searchInput.requestFocus()
    }
    
    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
    }
    
    private fun setupSearchField() {
        binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            
            // Show/hide clear button
            binding.searchClearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Cancel previous search
            searchJob?.cancel()
            
            if (query.isEmpty()) {
                // Show empty state
                binding.loadingIndicator.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
                binding.emptyText.text = "Enter keywords to search notes"
                binding.searchResultsRecyclerView.visibility = View.GONE
                binding.headerRow.visibility = View.GONE
            } else {
                // Show loading and perform search with debounce
                binding.emptyState.visibility = View.GONE
                binding.loadingIndicator.visibility = View.VISIBLE
                binding.searchResultsRecyclerView.visibility = View.GONE
                binding.headerRow.visibility = View.GONE
                
                searchJob = lifecycleScope.launch {
                    delay(500) // 500ms debounce
                    performSearch(query)
                }
            }
        }
        
        // Clear button click
        binding.searchClearButton.setOnClickListener {
            binding.searchInput.text?.clear()
        }
    }
    
    private fun setupRecyclerView() {
        binding.searchResultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
        }
    }
    
    private fun setupSyncChannelStatus() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe WebSocket connection state
        lifecycleScope.launch {
            app.webSocketService.connectionState.collectLatest { state ->
                updateSyncChannelStatus(state)
            }
        }
    }
    
    private suspend fun performSearch(query: String) {
        val app = requireActivity().application as KeeNotesApp
        
        try {
            val results = app.database.noteDao().searchNotes(query)
            
            binding.loadingIndicator.visibility = View.GONE
            
            if (results.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.emptyText.text = "No results found"
                binding.searchResultsRecyclerView.visibility = View.GONE
                binding.headerRow.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.searchResultsRecyclerView.visibility = View.VISIBLE
                binding.headerRow.visibility = View.VISIBLE
                binding.countText.text = "${results.size} note(s) - Search results"
                notesAdapter.submitList(results)
            }
        } catch (e: Exception) {
            binding.loadingIndicator.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyText.text = "Search failed: ${e.message}"
            binding.searchResultsRecyclerView.visibility = View.GONE
            binding.headerRow.visibility = View.GONE
        }
    }
    
    private fun updateSyncChannelStatus(state: WebSocketService.ConnectionState) {
        val (text, color) = when (state) {
            WebSocketService.ConnectionState.CONNECTED -> 
                "✓" to requireContext().getColor(R.color.success)
            WebSocketService.ConnectionState.CONNECTING -> 
                "..." to requireContext().getColor(R.color.warning)
            WebSocketService.ConnectionState.DISCONNECTED -> 
                "✗" to requireContext().getColor(R.color.text_secondary)
        }
        
        binding.syncIndicator.setColorFilter(color)
        binding.syncStatusText.text = text
        binding.syncStatusText.setTextColor(color)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        _binding = null
    }
}
