package cn.keevol.keenotes.ui.debug

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import kotlinx.coroutines.launch

/**
 * Debug view for development and troubleshooting
 */
class DebugFragment : Fragment() {
    
    private lateinit var statusText: TextView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.background))
            
            // Header
            addView(createHeader())
            
            // Debug buttons container
            addView(createDebugButtons())
        }
    }
    
    private fun createHeader(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(requireContext().getColor(R.color.surface))
            setPadding(16, 16, 16, 16)
            
            // Back button
            addView(Button(requireContext()).apply {
                text = "←"
                setOnClickListener { findNavController().popBackStack() }
            })
            
            // Title
            addView(TextView(requireContext()).apply {
                text = "Debug View"
                textSize = 18f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(16, 0, 0, 0)
            })
        }
    }
    
    private fun createDebugButtons(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            
            // Status text in a ScrollView
            val scrollView = android.widget.ScrollView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    400  // Fixed height for scrollable area
                )
            }
            statusText = TextView(requireContext()).apply {
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, 0, 0, 16)
                setTextIsSelectable(true)
            }
            scrollView.addView(statusText)
            addView(scrollView)
            
            // Debug buttons
            addView(createDebugButton("View Debug Logs") { viewDebugLogs() })
            addView(createDebugButton("Clear Debug Logs") { clearDebugLogs() })
            addView(createDebugButton("Check DB Count") { checkDbCount() })
            addView(createDebugButton("Dump All Notes") { dumpAllNotes() })
            addView(createDebugButton("Reset Sync State") { resetSyncState() })
            addView(createDebugButton("Clear All Notes") { clearAllNotes() })
            addView(createDebugButton("Test WebSocket") { testWebSocket() })
        }
    }
    
    private fun createDebugButton(text: String, action: () -> Unit): Button {
        return Button(requireContext()).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            setOnClickListener { action() }
        }
    }
    
    private fun checkDbCount() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            val count = app.database.noteDao().getNoteCount()
            val syncState = app.database.syncStateDao().getSyncState()
            val msg = "DB has $count notes\nLast sync ID: ${syncState?.lastSyncId ?: "N/A"}\nLast sync time: ${syncState?.lastSyncTime ?: "Never"}"
            statusText.text = msg
            Log.i("DebugFragment", msg)
            Toast.makeText(requireContext(), "Count: $count", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun viewDebugLogs() {
        findNavController().navigate(R.id.action_debug_to_logs)
    }
    
    private fun clearDebugLogs() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            app.database.debugLogDao().clearAll()
            statusText.text = "Debug logs cleared"
            Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun dumpAllNotes() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            val notes = app.database.noteDao().getRecentNotes(20)
            val sb = StringBuilder("=== Recent 20 Notes ===\n")
            notes.forEach { note ->
                sb.append("ID: ${note.id}, Content: ${note.content.take(50)}...\n")
            }
            statusText.text = sb.toString()
            Log.i("DebugFragment", sb.toString())
        }
    }
    
    private fun resetSyncState() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            app.database.syncStateDao().clearSyncState()
            statusText.text = "Sync state reset, reconnecting..."
            
            // Trigger re-sync by reconnecting WebSocket
            app.webSocketService.disconnect()
            kotlinx.coroutines.delay(500)
            app.webSocketService.connect()
            statusText.text = "Sync state reset, reconnected"
            Toast.makeText(requireContext(), "Sync state reset", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearAllNotes() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            app.database.noteDao().deleteAll()
            app.database.syncStateDao().clearSyncState()
            statusText.text = "All notes cleared, reconnecting..."
            
            // Trigger re-sync by reconnecting WebSocket
            app.webSocketService.disconnect()
            kotlinx.coroutines.delay(500)
            app.webSocketService.connect()
            statusText.text = "All notes cleared, reconnected"
            Toast.makeText(requireContext(), "All notes cleared", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testWebSocket() {
        val app = requireActivity().application as KeeNotesApp
        val state = app.webSocketService.connectionState.value
        statusText.text = "WebSocket state: $state"
        
        if (state.name == "DISCONNECTED") {
            app.webSocketService.connect()
            statusText.text = "Attempting to connect..."
        } else if (state.name == "CONNECTED") {
            // Force reconnect to trigger sync
            app.webSocketService.disconnect()
            statusText.text = "Disconnected, reconnecting in 1s..."
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                app.webSocketService.connect()
                statusText.text = "Reconnecting..."
            }
        }
    }
}
