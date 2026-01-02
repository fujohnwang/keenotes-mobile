package cn.keevol.keenotes.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.databinding.ItemNoteBinding

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
        
        private var isExpanded = false
        
        fun bind(note: Note) {
            binding.dateText.text = note.createdAt.take(10) // Just date part
            binding.previewText.text = note.content.take(100).replace("\n", " ")
            binding.fullContent.text = note.content
            
            // Initial state
            isExpanded = false
            binding.previewText.visibility = View.VISIBLE
            binding.expandedLayout.visibility = View.GONE
            
            // Click to expand/collapse
            binding.root.setOnClickListener {
                isExpanded = !isExpanded
                binding.previewText.visibility = if (isExpanded) View.GONE else View.VISIBLE
                binding.expandedLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
            
            // Copy button
            binding.btnCopy.setOnClickListener {
                val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("note", note.content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(it.context, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
