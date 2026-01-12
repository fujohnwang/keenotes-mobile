package cn.keevol.keenotes.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.databinding.ItemNoteBinding
import com.google.android.material.snackbar.Snackbar

class NotesAdapter : ListAdapter<Note, NotesAdapter.NoteViewHolder>(NoteDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NoteViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class NoteViewHolder(
        private val binding: ItemNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(note: Note) {
            // Date and time (yyyy-MM-dd HH:mm:ss format)
            // Take first 19 characters to get "yyyy-MM-dd HH:mm:ss"
            binding.dateText.text = if (note.createdAt.length >= 19) {
                note.createdAt.take(19)
            } else {
                note.createdAt
            }
            
            // Channel with bullet separator
            val channelText = if (note.channel.isNotEmpty()) note.channel else "default"
            binding.channelText.text = "• $channelText"
            
            // Full content (always visible, no truncation)
            binding.contentText.text = note.content
            
            // Click on card to copy (works on header and margins)
            binding.root.setOnClickListener {
                copyToClipboard(note.content)
            }
            
            // Click on content text to copy (when not selecting text)
            binding.contentText.setOnClickListener {
                copyToClipboard(note.content)
            }
        }
        
        private fun copyToClipboard(content: String) {
            val context = binding.root.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", content)
            clipboard.setPrimaryClip(clip)
            
            // Show Snackbar (Android standard way)
            Snackbar.make(binding.root, "✓ Copied to clipboard", Snackbar.LENGTH_SHORT).show()
        }
    }
    
    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
