package cn.keevol.keenotes.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentSettingsBinding
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
        
        setupHeader()
        setupSaveButton()
        setupCopyrightEasterEgg()
        loadSettings()
    }
    
    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
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
            // TODO: Navigate to debug view
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
        }
    }
    
    private fun saveSettings() {
        val endpoint = binding.endpointInput.text.toString().trim()
        val token = binding.tokenInput.text.toString()
        val password = binding.passwordInput.text.toString()
        val confirmPassword = binding.passwordConfirmInput.text.toString()
        
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
            app.settingsRepository.saveSettings(endpoint, token, password)
            
            val msg = if (password.isNotEmpty()) {
                "Settings saved ✓ (E2E encryption enabled)"
            } else {
                "Settings saved ✓"
            }
            
            binding.statusText.text = msg
            binding.statusText.setTextColor(requireContext().getColor(R.color.success))
            
            // Reconnect WebSocket with new settings
            app.webSocketService.disconnect()
            if (endpoint.isNotBlank() && token.isNotBlank()) {
                app.webSocketService.connect()
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
