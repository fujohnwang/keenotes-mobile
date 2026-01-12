package cn.keevol.keenotes.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentSettingsBinding
import cn.keevol.keenotes.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            saveSettings()
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
        val endpoint = binding.endpointInput.text.toString().trim()
        val token = binding.tokenInput.text.toString()
        val password = binding.passwordInput.text.toString()
        val confirmPassword = binding.passwordConfirmInput.text.toString()
        // copyToClipboard is auto-saved, no need to save here
        
        // Validate password match
        if (password != confirmPassword) {
            binding.passwordInput.text?.clear()
            binding.passwordConfirmInput.text?.clear()
            binding.passwordInput.requestFocus()
            binding.statusText.text = "Passwords do not match"
            binding.statusText.setTextColor(requireContext().getColor(R.color.error))
            return
        }
        
        val app = requireActivity().application as KeeNotesApp
        
        lifecycleScope.launch {
            // Get old settings to detect changes
            val oldEndpoint = app.settingsRepository.endpointUrl.first()
            val oldToken = app.settingsRepository.token.first()
            val oldPassword = app.settingsRepository.encryptionPassword.first()
            val wasConfigured = oldEndpoint.isNotBlank() && oldToken.isNotBlank()
            
            // Check if configuration changed
            val endpointChanged = oldEndpoint != endpoint
            val tokenChanged = oldToken != token
            val passwordChanged = oldPassword != password
            val configurationChanged = endpointChanged || tokenChanged || passwordChanged
            
            // Save new settings
            app.settingsRepository.saveSettings(endpoint, token, password)
            // copyToClipboard is auto-saved on toggle change
            
            val msg = if (password.isNotEmpty()) {
                "Settings saved ✓ (E2E encryption enabled)"
            } else {
                "Settings saved ✓"
            }
            
            if (configurationChanged && wasConfigured) {
                // Configuration changed: need to reset state and reconnect
                binding.statusText.text = "Configuration changed, reconnecting..."
                binding.statusText.setTextColor(requireContext().getColor(R.color.success))
                
                // 1. Disconnect old WebSocket and reset internal state
                app.webSocketService.disconnect()
                app.webSocketService.resetState()
                
                // 2. Reset sync state (set lastSyncId to -1 to trigger full re-sync)
                app.database.syncStateDao().clearSyncState()
                
                // 3. Clear notes if endpoint or token changed (different server or account = different data)
                if (endpointChanged || tokenChanged) {
                    app.database.noteDao().deleteAll()
                }
                
                // 4. Reconnect with new settings
                if (endpoint.isNotBlank() && token.isNotBlank()) {
                    app.webSocketService.connect()
                    binding.statusText.text = "$msg (Reconnected)"
                } else {
                    binding.statusText.text = msg
                }
                
                // Navigate back to Note fragment after 1 second delay
                Handler(Looper.getMainLooper()).postDelayed({
                    (requireActivity() as? MainActivity)?.selectNoteTab()
                }, 1000)
                
            } else if (!wasConfigured && endpoint.isNotBlank() && token.isNotBlank()) {
                // First time configuration
                binding.statusText.text = msg
                binding.statusText.setTextColor(requireContext().getColor(R.color.success))
                app.webSocketService.connect()
                
                // Navigate back to Note fragment after 500ms delay
                Handler(Looper.getMainLooper()).postDelayed({
                    (requireActivity() as? MainActivity)?.selectNoteTab()
                }, 500)
                
            } else {
                // No critical configuration change
                binding.statusText.text = msg
                binding.statusText.setTextColor(requireContext().getColor(R.color.success))
                
                // Still reconnect if configured (in case connection was lost)
                app.webSocketService.disconnect()
                if (endpoint.isNotBlank() && token.isNotBlank()) {
                    app.webSocketService.connect()
                }
                
                // Navigate back to Note fragment after 500ms delay
                Handler(Looper.getMainLooper()).postDelayed({
                    (requireActivity() as? MainActivity)?.selectNoteTab()
                }, 500)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
