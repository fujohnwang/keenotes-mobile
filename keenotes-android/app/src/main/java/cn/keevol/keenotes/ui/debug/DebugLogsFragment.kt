package cn.keevol.keenotes.ui.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen view for debug logs
 */
class DebugLogsFragment : Fragment() {
    
    private lateinit var logsText: TextView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.background))
            
            // Header with back button
            addView(createHeader())
            
            // Logs content in ScrollView
            addView(createLogsView())
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadLogs()
    }
    
    private fun createHeader(): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(requireContext().getColor(R.color.surface))
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
            
            // Back button
            addView(Button(requireContext()).apply {
                text = "←"
                setOnClickListener { findNavController().popBackStack() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            
            // Title
            addView(TextView(requireContext()).apply {
                text = "Debug Logs"
                textSize = 18f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(16, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            
            // Refresh button
            addView(Button(requireContext()).apply {
                text = "↻"
                setOnClickListener { loadLogs() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            
            // Clear button
            addView(Button(requireContext()).apply {
                text = "Clear"
                setOnClickListener { clearLogs() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 8
                }
            })
        }
    }
    
    private fun createLogsView(): View {
        return ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            
            logsText = TextView(requireContext()).apply {
                textSize = 11f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(24, 16, 24, 16)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            addView(logsText)
        }
    }
    
    private fun loadLogs() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            val logs = app.database.debugLogDao().getRecentLogs(200)
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            
            if (logs.isEmpty()) {
                sb.append("No debug logs yet.")
            } else {
                sb.append("=== ${logs.size} logs ===\n\n")
                logs.forEach { log ->
                    val time = dateFormat.format(Date(log.timestamp))
                    sb.append("[$time] ${log.tag}\n")
                    sb.append("${log.message}\n")
                    sb.append("─".repeat(40))
                    sb.append("\n\n")
                }
            }
            
            logsText.text = sb.toString()
        }
    }
    
    private fun clearLogs() {
        val app = requireActivity().application as KeeNotesApp
        lifecycleScope.launch {
            app.database.debugLogDao().clearAll()
            logsText.text = "Logs cleared."
        }
    }
}
