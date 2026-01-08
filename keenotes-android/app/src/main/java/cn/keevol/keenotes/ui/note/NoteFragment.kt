package cn.keevol.keenotes.ui.note

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.FragmentNoteBinding
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
        
        setupNoteInput()
        setupSaveButton()
    }
    
    private fun setupNoteInput() {
        binding.noteInput.addTextChangedListener {
            binding.btnSave.isEnabled = !it.isNullOrBlank()
        }
    }
    
    private fun setupSaveButton() {
        binding.btnSave.isEnabled = false
        
        binding.btnSave.setOnClickListener {
            val content = binding.noteInput.text.toString().trim()
            if (content.isNotEmpty()) {
                saveNote(content)
            }
        }
    }
    
    private fun saveNote(content: String) {
        val app = requireActivity().application as KeeNotesApp
        
        binding.btnSave.isEnabled = false
        binding.statusText.text = "Encrypting and sending..."
        binding.statusText.setTextColor(requireContext().getColor(R.color.text_secondary))
        
        lifecycleScope.launch {
            val result = app.apiService.postNote(content)
            
            if (result.success) {
                binding.statusText.text = "✓ ${result.message}"
                binding.statusText.setTextColor(requireContext().getColor(R.color.success))
                
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
                binding.statusText.text = "✗ ${result.message}"
                binding.statusText.setTextColor(requireContext().getColor(R.color.error))
            }
            
            binding.btnSave.isEnabled = binding.noteInput.text?.isNotBlank() == true
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
