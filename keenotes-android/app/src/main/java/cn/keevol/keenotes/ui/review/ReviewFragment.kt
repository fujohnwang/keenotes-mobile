package cn.keevol.keenotes.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentReviewBinding
import cn.keevol.keenotes.network.WebSocketService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class ReviewFragment : Fragment() {
    
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    
    private val notesAdapter = NotesAdapter()
    private var currentPeriod = "7 days"
    private var notesJob: Job? = null
    private var dotsAnimationJob: Job? = null
    
    // Pagination state
    private val loadedNotes = mutableListOf<cn.keevol.keenotes.data.entity.Note>()
    private var isLoadingMore = false
    private var hasMoreData = true
    private val pageSize = 20
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupPeriodSelector()
        setupRecyclerView()
        setupSyncChannelStatus()
        loadInitialNotes()
    }
    
    private fun setupPeriodSelector() {
        // Set default selection to 7 days
        binding.period7Days.isChecked = true
        
        binding.periodToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPeriod = when (checkedId) {
                    R.id.period7Days -> "7 days"
                    R.id.period30Days -> "30 days"
                    R.id.period90Days -> "90 days"
                    R.id.periodAll -> "All"
                    else -> "7 days"
                }
                // Reset and reload
                loadedNotes.clear()
                hasMoreData = true
                loadInitialNotes()
            }
        }
    }
    
    private fun setupRecyclerView() {
        binding.notesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
            
            // Add scroll listener for pagination
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
                    
                    // Load more when scrolled to bottom
                    if (!isLoadingMore && hasMoreData && dy > 0) {
                        if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5) {
                            loadMoreNotes()
                        }
                    }
                }
            })
        }
    }
    
    private fun setupSyncChannelStatus() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe WebSocket connection state
        viewLifecycleOwner.lifecycleScope.launch {
            app.webSocketService.connectionState.collectLatest { state ->
                if (_binding != null) {
                    updateSyncChannelStatus(state)
                }
            }
        }
        
        // Observe sync state for syncing indicator
        viewLifecycleOwner.lifecycleScope.launch {
            app.webSocketService.syncState.collectLatest { syncState ->
                if (_binding != null) {
                    updateSyncingIndicator(syncState)
                    // Reload when sync completes
                    if (syncState == WebSocketService.SyncState.COMPLETED) {
                        loadedNotes.clear()
                        hasMoreData = true
                        loadInitialNotes()
                    }
                }
            }
        }
        
        // Observe note count changes for realtime updates
        var previousCount = 0
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.noteDao().getNoteCountFlow().collectLatest { count ->
                if (_binding != null && previousCount > 0 && count > previousCount) {
                    // New notes arrived - load and add them at top
                    loadNewNotesAtTop(previousCount, count)
                }
                previousCount = count
            }
        }
    }
    
    private fun loadInitialNotes() {
        val app = requireActivity().application as KeeNotesApp
        val days = getDaysForPeriod(currentPeriod)
        val since = getSinceDate(days)
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get total count
                val totalCount = app.database.noteDao().getNotesCountForReview(since)
                
                // Load first page
                val notes = app.database.noteDao().getNotesForReviewPaged(since, pageSize, 0)
                
                loadedNotes.clear()
                loadedNotes.addAll(notes)
                hasMoreData = loadedNotes.size < totalCount
                
                if (loadedNotes.isEmpty()) {
                    binding.notesRecyclerView.visibility = View.GONE
                } else {
                    binding.notesRecyclerView.visibility = View.VISIBLE
                    notesAdapter.submitList(loadedNotes.toList())
                }
                
                updateCountText(totalCount, currentPeriod)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun loadNewNotesAtTop(previousCount: Int, newCount: Int) {
        val app = requireActivity().application as KeeNotesApp
        val days = getDaysForPeriod(currentPeriod)
        val since = getSinceDate(days)
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Calculate how many new notes
                val newNotesCount = newCount - previousCount
                
                // Load the newest notes (they should be at the top)
                val newNotes = app.database.noteDao().getNotesForReviewPaged(since, newNotesCount, 0)
                
                if (newNotes.isNotEmpty()) {
                    // Filter out notes that are already in the list
                    val existingIds = loadedNotes.map { it.id }.toSet()
                    val trulyNewNotes = newNotes.filter { it.id !in existingIds }
                    
                    if (trulyNewNotes.isNotEmpty()) {
                        // Add new notes at the beginning
                        loadedNotes.addAll(0, trulyNewNotes)
                        
                        // Update adapter
                        notesAdapter.submitList(loadedNotes.toList()) {
                            // Scroll to top to show new notes
                            binding.notesRecyclerView.scrollToPosition(0)
                        }
                        
                        // Update count
                        val totalCount = app.database.noteDao().getNotesCountForReview(since)
                        updateCountText(totalCount, currentPeriod)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun loadMoreNotes() {
        if (isLoadingMore || !hasMoreData) return
        
        isLoadingMore = true
        val app = requireActivity().application as KeeNotesApp
        val days = getDaysForPeriod(currentPeriod)
        val since = getSinceDate(days)
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val notes = app.database.noteDao().getNotesForReviewPaged(
                    since, 
                    pageSize, 
                    loadedNotes.size
                )
                
                if (notes.isEmpty()) {
                    hasMoreData = false
                } else {
                    loadedNotes.addAll(notes)
                    notesAdapter.submitList(loadedNotes.toList())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingMore = false
            }
        }
    }
    
    private fun getDaysForPeriod(period: String): Int {
        return when (period) {
            "30 days" -> 30
            "90 days" -> 90
            "All" -> 3650
            else -> 7
        }
    }
    
    private fun getSinceDate(days: Int): String {
        return Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString()
    }
    
    private fun updateSyncChannelStatus(state: WebSocketService.ConnectionState) {
        // Check if view is still attached
        if (_binding == null || !isAdded) return
        
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
    
    private fun updateSyncingIndicator(syncState: WebSocketService.SyncState) {
        if (_binding == null || !isAdded) return
        
        when (syncState) {
            WebSocketService.SyncState.SYNCING -> {
                startDotsAnimation()
                binding.syncingText.visibility = View.VISIBLE
            }
            else -> {
                stopDotsAnimation()
                binding.syncingText.visibility = View.GONE
            }
        }
    }
    

    
    private fun updateCountText(count: Int, period: String) {
        val periodInfo = when (period) {
            "7 days" -> " - Last 7 days"
            "30 days" -> " - Last 30 days"
            "90 days" -> " - Last 90 days"
            "All" -> " - All"
            else -> ""
        }
        binding.countText.text = "$count note(s)$periodInfo"
    }
    
    private fun startDotsAnimation() {
        stopDotsAnimation()
        dotsAnimationJob = lifecycleScope.launch {
            var dotCount = 0
            while (true) {
                val dots = ".".repeat(dotCount)
                binding.syncingText.text = "Syncing$dots"
                dotCount = (dotCount + 1) % 4  // 0, 1, 2, 3, then back to 0
                kotlinx.coroutines.delay(500)  // Update every 500ms
            }
        }
    }
    
    private fun stopDotsAnimation() {
        dotsAnimationJob?.cancel()
        dotsAnimationJob = null
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        notesJob?.cancel()
        stopDotsAnimation()
        _binding = null
    }
}
