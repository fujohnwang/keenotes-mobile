package cn.keevol.keenotes.ui.note

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentNoteBinding
import cn.keevol.keenotes.ui.MainActivity
import cn.keevol.keenotes.util.ZeroWidthSteganography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteFragment : Fragment() {
    companion object {
        const val REVISION_DRAFT_REQUEST_KEY = "revision_draft_request"
        const val REVISION_DRAFT_CONTENT_KEY = "revision_draft_content"
        const val REVISION_DRAFT_OVERWRITE_CONFIRMED_KEY = "revision_draft_overwrite_confirmed"
    }

    private var _binding: FragmentNoteBinding? = null
    private val binding get() = _binding!!

    private var shouldShowOverviewCard = false // Track if overview card should be shown
    private var isPreparingNote = false

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

        setupOnThisDayButton()
        setupSearchButton()
        setupOverviewCard()
        setupNoteInput()
        restoreNoteDraft()
        setupRevisionDraftListener()
        setupSendButton()
        setupAutoFocusInput()
        setupKeyboardListener()
        setupPendingBanner()
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

    private fun setupPendingBanner() {
        val app = requireActivity().application as KeeNotesApp

        viewLifecycleOwner.lifecycleScope.launch {
            app.pendingNoteService.pendingCountFlow.collect { count ->
                if (_binding != null) {
                    if (count > 0) {
                        binding.pendingBanner.visibility = View.VISIBLE
                        binding.pendingLabel.text = "📤 ${count} note(s) pending"
                        binding.pendingViewButton.setOnClickListener {
                            findNavController().navigate(R.id.action_note_to_pending)
                        }
                    } else {
                        binding.pendingBanner.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupOnThisDayButton() {
        val app = requireActivity().application as KeeNotesApp

        binding.btnOnThisDay.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (app.settingsRepository.showOnThisDayInYearsPast.first()) {
                    findNavController().navigate(R.id.action_note_to_onThisDay)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                app.settingsRepository.showOnThisDayInYearsPast,
                app.database.noteDao().getNotesOnThisDayFlow(),
                app.settingsRepository.debugMockOnThisDay
            ) { isEnabled, notes, debugMockEnabled ->
                isEnabled && (debugMockEnabled || notes.isNotEmpty())
            }.collectLatest { shouldShow ->
                if (_binding != null) {
                    binding.btnOnThisDay.visibility = if (shouldShow) View.VISIBLE else View.GONE
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
        binding.noteInput.addTextChangedListener { text ->
            val draft = text?.toString().orEmpty()
            (activity as? MainActivity)?.updateNoteDraftText(draft)
            updateSendButtonState(draft.isNotBlank())
        }
    }

    private fun restoreNoteDraft() {
        val draft = (activity as? MainActivity)?.getNoteDraftText().orEmpty()
        if (draft.isEmpty()) {
            return
        }

        binding.noteInput.setText(draft)
        binding.noteInput.setSelection(binding.noteInput.text?.length ?: 0)
        updateSendButtonState(true)
    }

    private fun setupRevisionDraftListener() {
        parentFragmentManager.setFragmentResultListener(
            REVISION_DRAFT_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val content = bundle.getString(REVISION_DRAFT_CONTENT_KEY).orEmpty()
            if (content.isEmpty() || _binding == null) return@setFragmentResultListener
            val overwriteConfirmed = bundle.getBoolean(REVISION_DRAFT_OVERWRITE_CONFIRMED_KEY, false)
            requestApplyRevisionDraft(content, overwriteConfirmed)
        }
    }

    private fun requestApplyRevisionDraft(content: String, overwriteConfirmed: Boolean) {
        if (overwriteConfirmed || binding.noteInput.text?.toString().orEmpty().isBlank()) {
            applyRevisionDraft(content)
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.overwrite_current_draft_title)
            .setMessage(R.string.overwrite_current_draft_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.overwrite) { _, _ ->
                applyRevisionDraft(content)
            }
            .show()
    }

    private fun applyRevisionDraft(content: String) {
        binding.noteInput.setText(content)
        binding.noteInput.setSelection(binding.noteInput.text?.length ?: 0)
        (activity as? MainActivity)?.updateNoteDraftText(content)
        binding.noteInput.requestFocus()
        binding.noteInput.post {
            if (_binding == null || !isAdded) return@post
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.noteInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSendButton() {
        updateSendButtonState(binding.noteInput.text?.toString().orEmpty().isNotBlank())

        binding.btnSend.setOnClickListener {
            val content = binding.noteInput.text.toString().trim()
            if (content.isNotEmpty()) {
                saveNote(content)
            }
        }
    }

    private fun updateSendButtonState(enabled: Boolean) {
        val canSend = enabled && !isPreparingNote
        binding.btnSend.isEnabled = canSend
        binding.btnSend.alpha = if (canSend) 1.0f else 0.5f
    }

    private fun saveNote(content: String) {
        if (isPreparingNote) return
        isPreparingNote = true
        updateSendButtonState(binding.noteInput.text?.toString().orEmpty().isNotBlank())

        val app = requireActivity().application as KeeNotesApp

        viewLifecycleOwner.lifecycleScope.launch {
            val preparedNote = try {
                app.apiService.prepareNote(content)
            } catch (e: Exception) {
                resetPreparingNoteState()
                if (_binding != null) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        e.message ?: "Failed to prepare note",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            if (_binding == null) {
                resetPreparingNoteState()
                return@launch
            }

            // Optimistic UI: clear input, confetti, clipboard immediately
            binding.noteInput.text?.clear()
            (activity as? MainActivity)?.clearNoteDraftText()
            binding.noteInput.clearFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.noteInput.windowToken, 0)
            resetPreparingNoteState()

            // Copy to clipboard & fire confetti immediately (optimistic UI)
            if (app.settingsRepository.getCopyToClipboardOnPost()) {
                val hiddenMessage = app.settingsRepository.getHiddenMessage()
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("note", ZeroWidthSteganography.embedIfNeeded(content, hiddenMessage))
                clipboard.setPrimaryClip(clip)
            }
            if (app.settingsRepository.confettiOnPostSuccess.first()) {
                cn.keevol.keenotes.ui.widget.ConfettiHelper.fire(requireActivity())
            }

            // 网络不可用：直接暂存到本地
            if (!app.pendingNoteService.isNetworkAvailable()) {
                app.pendingNoteService.savePendingNote(preparedNote)
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, "📤 Saved locally, will auto-send when network restores", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }

            // Send in background silently
            val result = app.apiService.postPreparedNote(preparedNote)

            if (_binding != null && !result.success) {
                // 发送失败：暂存到本地
                app.pendingNoteService.savePendingNote(preparedNote)
                if (result.networkError) {
                    app.webSocketService.markConnectionSuspect("http-post-network-error")
                }
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, "📤 Send failed, saved locally", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetPreparingNoteState() {
        isPreparingNote = false
        if (_binding != null) {
            updateSendButtonState(binding.noteInput.text?.toString().orEmpty().isNotBlank())
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开 NoteFragment 时主动隐藏键盘并清除焦点，防止切换到其他 tab 时出现键盘残影
        if (_binding != null) {
            binding.noteInput.clearFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.noteInput.windowToken, 0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isPreparingNote = false
        _binding = null
    }
}
