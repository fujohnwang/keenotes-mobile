package cn.keevol.keenotes.ui.settings

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import cn.keevol.keenotes.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    // Easter egg counter
    private var copyrightTapCount = 0
    private var lastTapTime = 0L
    
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
        
        setupCopyToClipboardToggle()
        setupSaveButton()
        setupCopyrightEasterEgg()
        loadSettings()
    }
    
    private fun setupCopyToClipboardToggle() {
        val app = requireActivity().application as KeeNotesApp
        
        // Load initial state
        lifecycleScope.launch {
            binding.copyToClipboardSwitch.isChecked = app.settingsRepository.copyToClipboardOnPost.first()
        }
        
        // Auto-save on toggle change
        binding.copyToClipboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.settingsRepository.setCopyToClipboardOnPost(isChecked)
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
            findNavController().navigate(R.id.action_settings_to_debug)
        }
    }
    
    private fun loadSettings() {
        val app = requireActivity().application as KeeNotesApp
        
        lifecycleScope.launch {
            binding.endpointInput.setText(app.settingsRepository.endpointUrl.first())
            binding.tokenInput.setText(app.settingsRepository.token.first())
            val password = app.settingsRepository.encryptionPassword.first()
            binding.passwordInput.setText(password)
            binding.passwordConfirmInput.setText(password)
            // copyToClipboardSwitch is loaded in setupCopyToClipboardToggle()
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
        
        // Branch 1: Start navigation timer immediately (independent of save logic)
        // Use Handler for Android 10 compatibility and UI thread safety
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            DebugLogger.log("Settings", "Navigation timer fired, isAdded=$isAdded")
            if (isAdded && activity != null && _binding != null) {
                try {
                    DebugLogger.log("Settings", "Navigating to noteFragment via bottom nav")
                    val bottomNav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                    bottomNav?.selectedItemId = R.id.noteFragment
                    DebugLogger.log("Settings", "Navigation complete")
                } catch (e: Exception) {
                    DebugLogger.error("Settings", "Navigation failed", e)
                }
            } else {
                DebugLogger.log("Settings", "Skipped navigation: fragment not attached")
            }
        }, 500)
        
        // Branch 2: Save settings and connect WebSocket in background
        lifecycleScope.launch(Dispatchers.IO) {
            DebugLogger.log("Settings", "Background save started")
            
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
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (_binding != null && context != null) {
                        context?.let { ctx ->
                            binding.statusText.setTextColor(ctx.getColor(R.color.success))
                            binding.statusText.text = msg
                        }
                    }
                }
                
                // Handle WebSocket connection based on configuration state
                if (configurationChanged && wasConfigured) {
                    DebugLogger.log("Settings", "Branch: configurationChanged && wasConfigured")
                    
                    app.webSocketService.disconnect()
                    app.webSocketService.resetState()
                    app.database.syncStateDao().clearSyncState()
                    
                    if (endpointChanged || tokenChanged) {
                        app.database.noteDao().deleteAll()
                    }
                    
                    if (endpoint.isNotBlank() && token.isNotBlank()) {
                        app.webSocketService.connect()
                        withContext(Dispatchers.Main) {
                            if (_binding != null && context != null) {
                                binding.statusText.text = "$msg (Reconnected)"
                            }
                        }
                    }
                    
                } else if (!wasConfigured && endpoint.isNotBlank() && token.isNotBlank()) {
                    DebugLogger.log("Settings", "Branch: First time configuration")
                    app.webSocketService.connect()
                    DebugLogger.log("Settings", "webSocketService.connect() called")
                    
                } else {
                    DebugLogger.log("Settings", "Branch: No critical configuration change")
                }
                
                DebugLogger.log("Settings", "Background save completed")
                
            } catch (e: Exception) {
                DebugLogger.error("Settings", "Save failed", e)
                withContext(Dispatchers.Main) {
                    if (_binding != null && context != null) {
                        context?.let { ctx ->
                            binding.statusText.text = "Save failed: ${e.message}"
                            binding.statusText.setTextColor(ctx.getColor(R.color.error))
                        }
                    }
                }
            }
        }
        
        DebugLogger.log("Settings", "saveSettings() returning")
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
