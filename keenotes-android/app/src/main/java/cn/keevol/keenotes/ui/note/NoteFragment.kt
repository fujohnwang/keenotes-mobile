package cn.keevol.keenotes.ui.note

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentNoteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteFragment : Fragment() {
    
    private var _binding: FragmentNoteBinding? = null
    private val binding get() = _binding!!
    
    private var shouldShowOverviewCard = false // Track if overview card should be shown
    
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
        
        setupSearchButton()
        setupOverviewCard()
        setupNoteInput()
        setupSendButton()
        setupSendChannelStatus()
        setupAutoFocusInput()
        setupKeyboardListener()
    }
    
    private fun setupKeyboardListener() {
        // Listen for keyboard visibility changes using layout changes
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (_binding == null || !isAdded) return@addOnGlobalLayoutListener
            
            val rect = android.graphics.Rect()
            binding.root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = binding.root.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            
            // If keyboard height is more than 15% of screen height, consider it visible
            val isKeyboardVisible = keypadHeight > screenHeight * 0.15
            
            // Update overview card visibility based on keyboard state
            if (shouldShowOverviewCard) {
                val newVisibility = if (isKeyboardVisible) View.GONE else View.VISIBLE
                if (binding.overviewCardInclude.root.visibility != newVisibility) {
                    binding.overviewCardInclude.root.visibility = newVisibility
                }
            }
        }
    }
    
    private fun setupAutoFocusInput() {
        val app = requireActivity().application as KeeNotesApp
        
        // Check if auto-focus is enabled and focus input
        viewLifecycleOwner.lifecycleScope.launch {
            val autoFocus = app.settingsRepository.autoFocusInputOnLaunch.first()
            if (autoFocus && _binding != null) {
                // Post to ensure view is fully laid out
                binding.noteInput.post {
                    if (_binding != null && isAdded) {
                        binding.noteInput.requestFocus()
                        // Show keyboard
                        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(binding.noteInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
    }
    
    private fun setupOverviewCard() {
        val app = requireActivity().application as KeeNotesApp
        
        // Observe showOverviewCard setting - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.showOverviewCard.collect { show ->
                if (_binding != null) {
                    shouldShowOverviewCard = show
                    // Show/hide based on setting (keyboard listener will handle dynamic hiding)
                    binding.overviewCardInclude.root.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        
        // Observe note count - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.noteDao().getNoteCountFlow().collect { count ->
                // Ensure UI updates happen on Main thread
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.overviewCardInclude.totalNotesValue.text = count.toString()
                    }
                }
                
                // Always update first note date when note count changes
                // Execute in IO context for database operations
                if (count > 0) {
                    withContext(Dispatchers.IO) {
                        val oldestDate = app.database.noteDao().getOldestNoteDate()
                        if (oldestDate != null) {
                            app.settingsRepository.setFirstNoteDate(oldestDate)
                        }
                    }
                }
            }
        }
        
        // Observe first note date and calculate days - use viewLifecycleOwner to auto-cancel when view is destroyed
        viewLifecycleOwner.lifecycleScope.launch {
            app.settingsRepository.firstNoteDate.collect { firstDate ->
                // Ensure UI updates happen on Main thread
                withContext(Dispatchers.Main) {
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
    }
    
    private fun calculateDaysUsing(firstDateStr: String): Int {
        return try {
            // Try multiple formats to handle both "2024-10-24 11:11:01" and "2024-10-24T11:11:01"
            val firstDate = try {
                // Try space-separated format first (from database)
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                java.time.LocalDateTime.parse(firstDateStr, formatter).toLocalDate()
            } catch (e: Exception) {
                // Fallback to ISO format
                val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
                java.time.LocalDateTime.parse(firstDateStr, formatter).toLocalDate()
            }
            
            val today = java.time.LocalDate.now()
            java.time.temporal.ChronoUnit.DAYS.between(firstDate, today).toInt() + 1
        } catch (e: Exception) {
            0
        }
    }
    
    private fun setupSearchButton() {
        binding.btnSearch.setOnClickListener {
            findNavController().navigate(R.id.action_note_to_search)
        }
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
            app.settingsRepository.isConfigured.collect { configured ->
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
        
        // Start rotation animation
        val rotateAnimation = android.view.animation.AnimationUtils.loadAnimation(requireContext(), cn.keevol.keenotes.R.anim.rotate_spinner)
        binding.sendProgress.startAnimation(rotateAnimation)
        
        viewLifecycleOwner.lifecycleScope.launch {
            val result = app.apiService.postNote(content)
            
            // Check if view still exists before updating UI
            if (_binding != null) {
                // Stop animation and restore button state
                binding.sendProgress.clearAnimation()
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
                    
                    // Clear input on success
                    binding.noteInput.text?.clear()
                    
                    // Clear focus to restore overview card if enabled
                    binding.noteInput.clearFocus()
                    
                    // Hide keyboard
                    val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(binding.noteInput.windowToken, 0)
                } else {
                    // Show error in button temporarily
                    binding.sendText.text = "Failed"
                    binding.btnSend.postDelayed({
                        if (_binding != null) {
                            binding.sendText.text = "Send"
                        }
                    }, 2000)
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
