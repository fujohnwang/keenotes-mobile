package cn.keevol.keenotes.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.databinding.FragmentSearchBinding
import cn.keevol.keenotes.network.WebSocketService
import cn.keevol.keenotes.share.NoteShareDialogFragment
import cn.keevol.keenotes.ui.MainActivity
import cn.keevol.keenotes.ui.common.EnlargedNoteDismissGesture
import cn.keevol.keenotes.ui.note.NoteFragment
import cn.keevol.keenotes.ui.review.NotesAdapter
import cn.keevol.keenotes.util.DateTimeUtil
import cn.keevol.keenotes.util.ZeroWidthSteganography
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SearchFragment : Fragment() {
    
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    
    private val notesAdapter = NotesAdapter(
        onEnlargeClick = { note -> showEnlargedNote(note) },
        onShareClick = { note -> showShareDialog(note) },
        onReviseClick = { note -> reviseAsNewNote(note) }
    )
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
        setupEnlargedCardDismissGesture()
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
                binding.enlargedNoteContainer.root.visibility = View.GONE
                binding.headerRow.visibility = View.GONE
            } else {
                // Show loading and perform search with debounce
                binding.emptyState.visibility = View.GONE
                binding.loadingIndicator.visibility = View.VISIBLE
                binding.searchResultsRecyclerView.visibility = View.GONE
                binding.enlargedNoteContainer.root.visibility = View.GONE
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

    private fun setupEnlargedCardDismissGesture() {
        EnlargedNoteDismissGesture.attach(binding.enlargedNoteContainer) {
            if (binding.enlargedNoteContainer.root.visibility == View.VISIBLE) {
                hideEnlargedNote()
            }
        }
    }
    
    private fun setupSyncChannelStatus() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe settings to control visibility
        lifecycleScope.launch {
            app.settingsRepository.showSyncChannelStatus.collectLatest { show ->
                binding.syncIndicator.visibility = if (show) View.VISIBLE else View.GONE
                binding.syncChannelLabel.visibility = if (show) View.VISIBLE else View.GONE
                binding.syncStatusText.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        
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
                binding.enlargedNoteContainer.root.visibility = View.GONE
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
            binding.enlargedNoteContainer.root.visibility = View.GONE
            binding.headerRow.visibility = View.GONE
        }
    }

    private fun showEnlargedNote(note: Note) {
        if (_binding == null) return

        val container = binding.enlargedNoteContainer.root
        binding.enlargedNoteContainer.enlargedDateText.text = DateTimeUtil.utcToLocalDisplay(note.createdAt)
        val channelText = if (note.channel.isNotEmpty()) note.channel else "default"
        binding.enlargedNoteContainer.enlargedChannelText.text = "• $channelText"
        binding.enlargedNoteContainer.enlargedContentText.text = note.content

        binding.enlargedNoteContainer.enlargedContentText.setOnClickListener {
            copyToClipboard(note.content)
        }

        binding.enlargedNoteContainer.shrinkButton.setOnClickListener {
            hideEnlargedNote()
        }

        binding.enlargedNoteContainer.enlargedShareButton.setOnClickListener {
            showShareDialog(note)
        }

        binding.enlargedNoteContainer.enlargedReviseButton.setOnClickListener {
            reviseAsNewNote(note)
        }

        binding.searchResultsRecyclerView.visibility = View.GONE
        container.visibility = View.VISIBLE
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideEnlargedNote() {
        if (_binding == null) return

        val container = binding.enlargedNoteContainer.root
        container.animate().alpha(0f).setDuration(200).withEndAction {
            if (_binding != null) {
                container.visibility = View.GONE
                if (notesAdapter.currentList.isNotEmpty()) {
                    binding.searchResultsRecyclerView.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun copyToClipboard(content: String) {
        val context = requireContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val hiddenMessage = runBlocking {
            (context.applicationContext as KeeNotesApp).settingsRepository.getHiddenMessage()
        }
        val clip = ClipData.newPlainText("note", ZeroWidthSteganography.embedIfNeeded(content, hiddenMessage))
        clipboard.setPrimaryClip(clip)

        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.toast_copied, null)
        Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            view = layout
            setGravity(Gravity.CENTER, 0, 0)
            show()
        }
    }

    private fun showShareDialog(note: Note) {
        NoteShareDialogFragment.show(parentFragmentManager, note)
    }

    private fun reviseAsNewNote(note: Note) {
        if ((activity as? MainActivity)?.getNoteDraftText().orEmpty().isBlank()) {
            applyRevisionDraft(note, overwriteConfirmed = false)
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.overwrite_current_draft_title)
            .setMessage(R.string.overwrite_current_draft_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.overwrite) { _, _ ->
                applyRevisionDraft(note, overwriteConfirmed = true)
            }
            .show()
    }

    private fun applyRevisionDraft(note: Note, overwriteConfirmed: Boolean) {
        parentFragmentManager.setFragmentResult(
            NoteFragment.REVISION_DRAFT_REQUEST_KEY,
            Bundle().apply {
                putString(NoteFragment.REVISION_DRAFT_CONTENT_KEY, note.content)
                putBoolean(NoteFragment.REVISION_DRAFT_OVERWRITE_CONFIRMED_KEY, overwriteConfirmed)
            }
        )
        (activity as? MainActivity)?.navigateToNote()
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
