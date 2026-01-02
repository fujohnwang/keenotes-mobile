package cn.keevol.keenotes.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.entity.Note
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
        val periods = arrayOf("7 days", "30 days", "90 days", "All")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.periodSpinner.adapter = adapter
        
        binding.periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentPeriod = periods[position]
                observeNotes(currentPeriod)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
     * Also observes sync state to show appropriate empty message
     */
    private fun observeNotes(period: String) {
        val app = requireActivity().application as KeeNotesApp
        
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
                binding.loadingText.visibility = View.GONE
                
                if (notes.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                    binding.notesRecyclerView.visibility = View.GONE
                    
                    // Show different message based on sync state
                    binding.emptyText.text = when (syncState) {
                        WebSocketService.SyncState.SYNCING -> "Notes syncing..."
                        WebSocketService.SyncState.IDLE -> "Waiting for sync..."
                        WebSocketService.SyncState.COMPLETED -> "No notes found for $period"
                    }
                    binding.countText.text = "0 note(s)"
                } else {
                    binding.emptyText.visibility = View.GONE
                    binding.notesRecyclerView.visibility = View.VISIBLE
                    binding.countText.text = "${notes.size} note(s)"
                    notesAdapter.submitList(notes)
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        notesJob?.cancel()
        _binding = null
    }
}
