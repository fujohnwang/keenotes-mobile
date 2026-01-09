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
import cn.keevol.keenotes.databinding.FragmentReviewBinding
import cn.keevol.keenotes.network.WebSocketService
import cn.keevol.keenotes.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ReviewFragment : Fragment() {
    
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    
    private val notesAdapter = NotesAdapter()
    private lateinit var reviewViewModel: ReviewViewModel
    
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
        
        // Get ViewModel from MainActivity
        reviewViewModel = (requireActivity() as MainActivity).getReviewViewModel()
        
        setupHeader()
        setupPeriodSelector()
        setupRecyclerView()
        observeViewModel()
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
                reviewViewModel.setSelectedPeriod(periods[position])
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
     * Observe ViewModel state changes
     */
    private fun observeViewModel() {
        val app = requireActivity().application as KeeNotesApp
        
        lifecycleScope.launch {
            // Combine notes, loading state, search query, and sync state
            combine(
                reviewViewModel.notes,
                reviewViewModel.isLoading,
                reviewViewModel.searchQuery,
                app.webSocketService.syncState
            ) { notes, isLoading, searchQuery, syncState ->
                ViewState(notes, isLoading, searchQuery, syncState)
            }.collectLatest { state ->
                updateUI(state)
            }
        }
    }
    
    private data class ViewState(
        val notes: List<cn.keevol.keenotes.data.entity.Note>,
        val isLoading: Boolean,
        val searchQuery: String,
        val syncState: WebSocketService.SyncState
    )
    
    private fun updateUI(state: ViewState) {
        // Show/hide loading
        binding.loadingText.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        
        if (state.notes.isEmpty() && !state.isLoading) {
            binding.emptyText.visibility = View.VISIBLE
            binding.notesRecyclerView.visibility = View.GONE
            
            // Show different message based on context
            binding.emptyText.text = when {
                state.searchQuery.isNotBlank() -> "No results found for \"${state.searchQuery}\""
                state.syncState == WebSocketService.SyncState.SYNCING -> "Notes syncing..."
                state.syncState == WebSocketService.SyncState.IDLE -> "Waiting for sync..."
                else -> "No notes found"
            }
            binding.countText.text = "0 note(s)"
        } else if (!state.isLoading) {
            binding.emptyText.visibility = View.GONE
            binding.notesRecyclerView.visibility = View.VISIBLE
            binding.countText.text = "${state.notes.size} note(s)"
            notesAdapter.submitList(state.notes)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
