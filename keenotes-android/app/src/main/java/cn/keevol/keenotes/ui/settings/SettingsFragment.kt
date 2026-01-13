package cn.keevol.keenotes.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentSettingsBinding
import cn.keevol.keenotes.ui.MainActivity
import cn.keevol.keenotes.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    // Easter egg counter
    private var copyrightTapCount = 0
    private var lastTapTime = 0L
    
    // StateFlows for reactive binding
    private val endpointFlow = MutableStateFlow("")
    private val tokenFlow = MutableStateFlow("")
    private val passwordFlow = MutableStateFlow("")
    private val confirmPasswordFlow = MutableStateFlow("")
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupSaveButtonBinding()
        setupCopyToClipboardToggle()
        setupSaveButton()
        setupCopyrightEasterEgg()
        loadSettings()
    }
    
    private var textWatcher: android.text.TextWatcher? = null
    
    private fun setupSaveButtonBinding() {
        // Setup TextWatchers to update StateFlows
        binding.endpointInput.addTextChangedListener(createTextWatcher { endpointFlow.value = it })
        binding.tokenInput.addTextChangedListener(createTextWatcher { tokenFlow.value = it })
        binding.passwordInput.addTextChangedListener(createTextWatcher { passwordFlow.value = it })
        binding.passwordConfirmInput.addTextChangedListener(createTextWatcher { confirmPasswordFlow.value = it })
        
        // Reactive binding: combine all StateFlows to determine button enabled state
        lifecycleScope.launch {
            combine(
                endpointFlow,
                tokenFlow,
                passwordFlow,
                confirmPasswordFlow
            ) { endpoint, token, password, confirmPassword ->
                endpoint.trim().isNotEmpty() &&
                token.trim().isNotEmpty() &&
                password.trim().isNotEmpty() &&
                confirmPassword.trim().isNotEmpty() &&
                password == confirmPassword
            }.collect { isEnabled ->
                binding.btnSave.isEnabled = isEnabled
            }
        }
    }
    
    private fun createTextWatcher(onTextChanged: (String) -> Unit): android.text.TextWatcher {
        return object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onTextChanged(s?.toString() ?: "")
            }
        }
    }
    
    private fun setupCopyToClipboardToggle() {
        val app = requireActivity().application as KeeNotesApp
        
        // Load initial state
        lifecycleScope.launch {
            binding.copyToClipboardSwitch.isChecked = app.settingsRepository.copyToClipboardOnPost.first()
            binding.showOverviewCardSwitch.isChecked = app.settingsRepository.showOverviewCard.first()
            binding.autoFocusInputSwitch.isChecked = app.settingsRepository.autoFocusInputOnLaunch.first()
        }
        
        // Auto-save on toggle change
        binding.copyToClipboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setCopyToClipboardOnPost(isChecked)
            }
        }
        
        binding.showOverviewCardSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setShowOverviewCard(isChecked)
            }
        }
        
        binding.autoFocusInputSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setAutoFocusInputOnLaunch(isChecked)
            }
        }
    }
    
    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            hideKeyboard()
            saveSettings()
        }
    }
    
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view?.windowToken?.let { token ->
            imm.hideSoftInputFromWindow(token, 0)
        }
    }
    
    private fun setupCopyrightEasterEgg() {
        binding.copyrightText.setOnClickListener {
            val now = System.currentTimeMillis()
            
            // Reset if more than 1 second since last tap
            if (now - lastTapTime > 1000) {
                copyrightTapCount = 0
            }
            lastTapTime = now
            copyrightTapCount++
            
            if (copyrightTapCount >= 7 && binding.debugSection.visibility != View.VISIBLE) {
                binding.debugSection.visibility = View.VISIBLE
                binding.statusText.text = "Debug mode enabled!"
                binding.statusText.setTextColor(requireContext().getColor(R.color.success))
            } else if (copyrightTapCount >= 4 && copyrightTapCount < 7) {
                val remaining = 7 - copyrightTapCount
                binding.statusText.text = "$remaining more tap(s) to enable debug mode"
                binding.statusText.setTextColor(requireContext().getColor(R.color.text_secondary))
            }
        }
        
        binding.btnDebug.setOnClickListener {
            // Navigate to debug logs fragment
            try {
                findNavController().navigate(R.id.action_settings_to_debug)
            } catch (e: Exception) {
                DebugLogger.error("Settings", "Failed to navigate to debug", e)
            }
        }
    }
    
    private fun loadSettings() {
        val app = requireActivity().application as KeeNotesApp
        
        lifecycleScope.launch {
            val endpoint = app.settingsRepository.endpointUrl.first()
            val token = app.settingsRepository.token.first()
            val password = app.settingsRepository.encryptionPassword.first()
            
            // Update UI on main thread
            withContext(Dispatchers.Main) {
                binding.endpointInput.setText(endpoint)
                binding.tokenInput.setText(token)
                binding.passwordInput.setText(password)
                binding.passwordConfirmInput.setText(password)
            }
        }
    }
    
    private fun saveSettings() {
        DebugLogger.log("Settings", "saveSettings() called")
        
        val endpoint = binding.endpointInput.text.toString().trim()
        val token = binding.tokenInput.text.toString()
        val password = binding.passwordInput.text.toString()
        val confirmPassword = binding.passwordConfirmInput.text.toString()
        
        DebugLogger.log("Settings", "endpoint=${endpoint.take(30)}..., token=${token.take(10)}..., hasPassword=${password.isNotEmpty()}")
        
        // Validate password match
        if (password != confirmPassword) {
            binding.passwordInput.text?.clear()
            binding.passwordConfirmInput.text?.clear()
            binding.passwordInput.requestFocus()
            binding.statusText.text = "Passwords do not match"
            binding.statusText.setTextColor(requireContext().getColor(R.color.error))
            DebugLogger.log("Settings", "Password mismatch, returning")
            return
        }
        
        val app = requireActivity().application as KeeNotesApp
        
        // Show saving status immediately on UI thread
        binding.statusText.text = "Saving..."
        binding.statusText.setTextColor(requireContext().getColor(R.color.text_secondary))
        
        // BRANCH 1: Background save + WebSocket (completely async, no Fragment dependency)
        lifecycleScope.launch(Dispatchers.IO) {
            DebugLogger.log("Settings", "Branch 1: Background save started")
            
            try {
                // Get old settings to detect changes
                val oldEndpoint = app.settingsRepository.endpointUrl.first()
                val oldToken = app.settingsRepository.token.first()
                val oldPassword = app.settingsRepository.encryptionPassword.first()
                val wasConfigured = oldEndpoint.isNotBlank() && oldToken.isNotBlank()
                
                DebugLogger.log("Settings", "wasConfigured=$wasConfigured")
                
                // Check if configuration changed
                val endpointChanged = oldEndpoint != endpoint
                val tokenChanged = oldToken != token
                val passwordChanged = oldPassword != password
                val configurationChanged = endpointChanged || tokenChanged || passwordChanged
                
                DebugLogger.log("Settings", "configurationChanged=$configurationChanged")
                
                // Save new settings (IO operation)
                app.settingsRepository.saveSettings(endpoint, token, password)
                DebugLogger.log("Settings", "Settings saved to repository")
                
                val msg = if (password.isNotEmpty()) {
                    "Settings saved ✓ (E2E encryption enabled)"
                } else {
                    "Settings saved ✓"
                }
                
                // Update UI safely - check lifecycle
                withContext(Dispatchers.Main) {
                    DebugLogger.log("Settings", "Updating UI, isAdded=$isAdded, _binding=${_binding != null}")
                    if (isAdded && _binding != null && activity != null) {
                        try {
                            binding.statusText.setTextColor(requireContext().getColor(R.color.success))
                            binding.statusText.text = msg
                            DebugLogger.log("Settings", "UI updated successfully")
                        } catch (e: Exception) {
                            DebugLogger.error("Settings", "UI update failed", e)
                        }
                    }
                }
                
                // Handle WebSocket connection based on configuration state
                if (configurationChanged && wasConfigured) {
                    DebugLogger.log("Settings", "WebSocket: Reconfiguration detected")
                    
                    app.webSocketService.disconnect()
                    app.webSocketService.resetState()
                    app.database.syncStateDao().clearSyncState()
                    
                    if (endpointChanged || tokenChanged) {
                        app.database.noteDao().deleteAll()
                    }
                    
                    if (endpoint.isNotBlank() && token.isNotBlank()) {
                        app.webSocketService.connect()
                    }
                    
                } else if (!wasConfigured && endpoint.isNotBlank() && token.isNotBlank()) {
                    DebugLogger.log("Settings", "WebSocket: First time configuration")
                    app.webSocketService.connect()
                    
                } else {
                    DebugLogger.log("Settings", "WebSocket: No action needed")
                }
                
                DebugLogger.log("Settings", "Branch 1: Completed")
                
            } catch (e: Exception) {
                DebugLogger.error("Settings", "Branch 1: Save failed", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null && activity != null) {
                        try {
                            binding.statusText.text = "Save failed: ${e.message}"
                            binding.statusText.setTextColor(requireContext().getColor(R.color.error))
                        } catch (ex: Exception) {
                            DebugLogger.error("Settings", "Error UI update failed", ex)
                        }
                    }
                }
            }
        }
        
        // BRANCH 2: Delayed navigation (UI thread safe, independent of Branch 1)
        view?.postDelayed({
            DebugLogger.log("Settings", "Branch 2: Navigation delay completed")
            if (isAdded && activity != null) {
                try {
                    DebugLogger.log("Settings", "Branch 2: Navigating to Note")
                    (activity as? MainActivity)?.navigateToNote()
                    DebugLogger.log("Settings", "Branch 2: Navigation triggered")
                } catch (e: Exception) {
                    DebugLogger.error("Settings", "Branch 2: Navigation failed", e)
                }
            } else {
                DebugLogger.log("Settings", "Branch 2: Fragment not attached, skipping navigation")
            }
        }, 500)
        
        DebugLogger.log("Settings", "saveSettings() returning (both branches started)")
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
