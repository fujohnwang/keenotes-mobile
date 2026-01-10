package cn.keevol.keenotes.ui.review

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        
        fun bind(note: Note) {
            // Date (just date part, not time)
            binding.dateText.text = note.createdAt.take(10)
            
            // Channel with bullet separator
            val channelText = if (note.channel.isNotEmpty()) note.channel else "default"
            binding.channelText.text = "• $channelText"
            
            // Full content (always visible, no truncation)
            binding.contentText.text = note.content
            
            // Click to copy
            binding.root.setOnClickListener {
                copyToClipboard(note.content)
            }
        }
        
        private fun copyToClipboard(content: String) {
            val context = binding.root.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", content)
            clipboard.setPrimaryClip(clip)
            
            // Show popup with fade animation
            binding.copiedPopup.visibility = View.VISIBLE
            binding.copiedPopup.alpha = 0f
            binding.copiedPopup.animate()
                .alpha(1f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Fade out after delay
                        binding.copiedPopup.postDelayed({
                            binding.copiedPopup.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .setListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        binding.copiedPopup.visibility = View.GONE
                                    }
                                })
                        }, 1500)
                    }
                })
        }
    }
    
    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
