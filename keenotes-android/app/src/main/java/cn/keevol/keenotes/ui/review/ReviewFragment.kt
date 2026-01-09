package cn.keevol.keenotes.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
        
        setupHeader()
        setupPeriodSelector()
        setupRecyclerView()
        observeNotes(currentPeriod)
    }
    
    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
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
                observeNotes(currentPeriod)
            }
        }
    }
    
    private fun setupRecyclerView() {
        binding.notesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
        }
    }
    
    /**
     * Observe notes from database using Flow - auto-updates when data changes
     * Simplified logic: primarily rely on notes data, use syncState only for empty message
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
                if (notes.isEmpty()) {
                    // Hide loading, show empty state
                    binding.loadingText.visibility = View.GONE
                    binding.emptyText.visibility = View.VISIBLE
                    binding.notesRecyclerView.visibility = View.GONE
                    
                    // Show different message based on sync state
                    when (syncState) {
                        WebSocketService.SyncState.SYNCING -> {
                            startDotsAnimation("Notes syncing")
                        }
                        WebSocketService.SyncState.IDLE -> {
                            stopDotsAnimation()
                            binding.emptyText.text = "Waiting for sync..."
                        }
                        WebSocketService.SyncState.COMPLETED -> {
                            stopDotsAnimation()
                            binding.emptyText.text = "No notes found for $period"
                        }
                    }
                    binding.countText.text = "0 note(s)"
                } else {
                    // Show notes list
                    stopDotsAnimation()
                    binding.loadingText.visibility = View.GONE
                    binding.emptyText.visibility = View.GONE
                    binding.notesRecyclerView.visibility = View.VISIBLE
                    binding.countText.text = "${notes.size} note(s)"
                    notesAdapter.submitList(notes)
                }
            }
        }
    }
    
    private fun startDotsAnimation(baseText: String) {
        stopDotsAnimation()
        dotsAnimationJob = lifecycleScope.launch {
            var dotCount = 0
            while (true) {
                val dots = ".".repeat(dotCount)
                binding.emptyText.text = "$baseText$dots"
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
