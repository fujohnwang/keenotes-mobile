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
    private var newNotesIndicatorJob: Job? = null
    private var previousNotesCount = 0
    private var previousFirstNoteId: Long? = null
    
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
        observeNotes(currentPeriod)
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
                // Reset tracking when period changes
                previousNotesCount = 0
                previousFirstNoteId = null
                observeNotes(currentPeriod)
            }
        }
    }
    
    private fun setupRecyclerView() {
        binding.notesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
            // Enable item animations (default animator should handle insertions)
            itemAnimator?.apply {
                addDuration = 300
                changeDuration = 300
                moveDuration = 300
                removeDuration = 300
            }
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
    
    private fun updateSyncingIndicator(syncState: WebSocketService.SyncState) {
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
    
    /**
     * Observe notes from database using Flow - auto-updates when data changes
     * Simplified logic: primarily rely on notes data, use syncState only for syncing indicator
     */
    private fun observeNotes(period: String) {
        val app = requireActivity().application as KeeNotesApp
        
        // Show initial loading state
        binding.loadingText.visibility = View.VISIBLE
        binding.notesRecyclerView.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
        
        // Cancel previous observation
        notesJob?.cancel()
        
        val days = when (period) {
            "30 days" -> 30
            "90 days" -> 90
            "All" -> 3650
            else -> 7
        }
        
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString()
        
        notesJob = lifecycleScope.launch {
            // Combine notes flow with sync state flow
            combine(
                app.database.noteDao().getNotesForReviewFlow(since),
                app.webSocketService.syncState
            ) { notes, syncState ->
                Pair(notes, syncState)
            }.collectLatest { (notes, syncState) ->
                // Update syncing indicator in header
                updateSyncingIndicator(syncState)
                
                if (notes.isEmpty()) {
                    // Hide loading, show empty state
                    binding.loadingText.visibility = View.GONE
                    binding.emptyText.visibility = View.VISIBLE
                    binding.notesRecyclerView.visibility = View.GONE
                    
                    // Show message based on sync state
                    binding.emptyText.text = when (syncState) {
                        WebSocketService.SyncState.COMPLETED -> "No notes found for $period"
                        else -> "No notes yet"
                    }
                    updateCountText(0, period)
                    previousNotesCount = 0
                    previousFirstNoteId = null
                } else {
                    // Show notes list
                    binding.loadingText.visibility = View.GONE
                    binding.emptyText.visibility = View.GONE
                    binding.notesRecyclerView.visibility = View.VISIBLE
                    updateCountText(notes.size, period)
                    
                    // Check if new notes arrived at the top
                    val hasNewNotes = notes.isNotEmpty() && (
                        notes.size > previousNotesCount || 
                        (previousFirstNoteId != null && notes.first().id != previousFirstNoteId)
                    )
                    
                    // Submit list - create a new list instance to ensure DiffUtil detects changes
                    notesAdapter.submitList(notes.toList()) {
                        // Callback after list is submitted and animations are complete
                        if (hasNewNotes && previousNotesCount > 0) {
                            showNewNotesIndicator()
                        }
                    }
                    
                    // Update tracking variables
                    previousNotesCount = notes.size
                    previousFirstNoteId = notes.firstOrNull()?.id
                }
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
    
    private fun showNewNotesIndicator() {
        // Cancel any existing indicator job
        newNotesIndicatorJob?.cancel()
        
        // Show indicator with fade-in animation
        binding.newNotesIndicator.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
        
        // Hide after 2 seconds with fade-out animation
        newNotesIndicatorJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            binding.newNotesIndicator.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.newNotesIndicator.visibility = View.GONE
                }
                .start()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        notesJob?.cancel()
        stopDotsAnimation()
        newNotesIndicatorJob?.cancel()
        _binding = null
    }
}
