package cn.keevol.keenotes.ui.note

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentNoteBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NoteFragment : Fragment() {
    
    private var _binding: FragmentNoteBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNoteBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupOverviewCard()
        setupNoteInput()
        setupSendButton()
        setupSendChannelStatus()
    }
    
    private fun setupOverviewCard() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe showOverviewCard setting - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.showOverviewCard.collectLatest { show ->
                if (_binding != null) {
                    binding.overviewCardInclude.root.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        
        // Observe note count - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.noteDao().getNoteCountFlow().collectLatest { count ->
                if (_binding != null) {
                    binding.overviewCardInclude.totalNotesValue.text = count.toString()
                    
                    // Initialize first note date if needed
                    if (count > 0) {
                        val firstDate = app.settingsRepository.getFirstNoteDate()
                        if (firstDate == null) {
                            val oldestDate = app.database.noteDao().getOldestNoteDate()
                            if (oldestDate != null) {
                                app.settingsRepository.setFirstNoteDate(oldestDate)
                            }
                        }
                    }
                }
            }
        }
        
        // Observe first note date and calculate days - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.firstNoteDate.collectLatest { firstDate ->
                if (_binding != null) {
                    if (firstDate != null) {
                        val days = calculateDaysUsing(firstDate)
                        binding.overviewCardInclude.daysUsingValue.text = days.toString()
                    } else {
                        binding.overviewCardInclude.daysUsingValue.text = "0"
                    }
                }
            }
        }
    }
    
    private fun calculateDaysUsing(firstDateStr: String): Int {
        return try {
            val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
            val firstDate = java.time.LocalDateTime.parse(firstDateStr, formatter).toLocalDate()
            val today = java.time.LocalDate.now()
            java.time.temporal.ChronoUnit.DAYS.between(firstDate, today).toInt() + 1
        } catch (e: Exception) {
            0
        }
    }
    
    private fun setupToolbar() {
        // Add menu to toolbar
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.clear()
                menuInflater.inflate(R.menu.note_menu, menu)
            }
            
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_search -> {
                        findNavController().navigate(R.id.action_note_to_search)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }
    
    private fun setupNoteInput() {
        binding.noteInput.addTextChangedListener {
            updateSendButtonState(!it.isNullOrBlank())
        }
    }
    
    private fun setupSendButton() {
        updateSendButtonState(false)
        
        binding.btnSend.setOnClickListener {
            val content = binding.noteInput.text.toString().trim()
            if (content.isNotEmpty()) {
                saveNote(content)
            }
        }
    }
    
    private fun updateSendButtonState(enabled: Boolean) {
        binding.btnSend.isEnabled = enabled
        binding.btnSend.alpha = if (enabled) 1.0f else 0.5f
    }
    
    private fun setupSendChannelStatus() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe settings changes for send channel status - use viewLifecycleOwner
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.isConfigured.collectLatest { configured ->
                if (_binding != null) {
                    updateSendChannelStatus(configured)
                }
            }
        }
    }
    
    private fun updateSendChannelStatus(configured: Boolean) {
        // Check if view is still attached
        if (_binding == null || !isAdded) return
        
        val (text, color) = if (!configured) {
            "Not Configured" to requireContext().getColor(R.color.warning)
        } else {
            // TODO: Add network connectivity check
            "✓" to requireContext().getColor(R.color.success)
        }
        
        binding.sendIndicator.setColorFilter(color)
        binding.sendStatusText.text = text
        binding.sendStatusText.setTextColor(color)
    }
    
    private fun saveNote(content: String) {
        val app = requireActivity().application as KeeNotesApp
        
        // Disable button and show sending state
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.7f
        binding.sendIcon.visibility = View.GONE
        binding.sendProgress.visibility = View.VISIBLE
        binding.sendText.text = "Sending..."
        
        viewLifecycleOwner.lifecycleScope.launch {
            val result = app.apiService.postNote(content)
            
            // Check if view still exists before updating UI
            if (_binding != null) {
                // Restore button state
                binding.sendProgress.visibility = View.GONE
                binding.sendIcon.visibility = View.VISIBLE
                binding.sendText.text = "Send"
                
                if (result.success) {
                    // Copy to clipboard if enabled
                    if (app.settingsRepository.getCopyToClipboardOnPost()) {
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("note", content)
                        clipboard.setPrimaryClip(clip)
                    }
                    
                    // Show echo
                    binding.echoCard.visibility = View.VISIBLE
                    binding.echoContent.text = result.echoContent
                    
                    // Clear input
                    binding.noteInput.text?.clear()
                } else {
                    // Show error (could add a toast or snackbar here)
                    binding.echoCard.visibility = View.VISIBLE
                    binding.echoContent.text = "Error: ${result.message}"
                }
                
                updateSendButtonState(binding.noteInput.text?.isNotBlank() == true)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
