package cn.keevol.keenotes.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.Layout
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.keevol.keenotes.R
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
        
        private var currentNote: Note? = null
        
        fun bind(note: Note) {
            currentNote = note
            
            // Date and time
            binding.dateText.text = if (note.createdAt.length >= 19) {
                note.createdAt.take(19)
            } else {
                note.createdAt
            }
            
            // Channel with bullet separator
            val channelText = if (note.channel.isNotEmpty()) note.channel else "default"
            binding.channelText.text = "• $channelText"
            
            // Full content
            binding.contentText.text = note.content
            
            // Setup interactions
            setupCardClick()
            setupTextViewInteraction()
        }
        
        /**
         * CardView click - copy entire content (handles clicks outside TextView)
         */
        private fun setupCardClick() {
            binding.root.setOnClickListener {
                copyToClipboard(currentNote?.content ?: "")
            }
        }
        
        /**
         * TextView interaction:
         * - Forward touch events to CardView for ripple effect
         * - Click: copy entire content
         * - Long press on text: system handles text selection (textIsSelectable=true)
         * - Long press on non-text: copy entire content
         */
        private fun setupTextViewInteraction() {
            val textView = binding.contentText
            val cardView = binding.root
            
            // Track if we're in text selection mode
            var isInSelectionMode = false
            
            // Forward touch events to CardView for ripple
            textView.setOnTouchListener { v, event ->
                val tv = v as TextView
                
                // Calculate position relative to CardView for ripple hotspot
                val location = IntArray(2)
                tv.getLocationInWindow(location)
                val cardLocation = IntArray(2)
                cardView.getLocationInWindow(cardLocation)
                
                val relativeX = event.x + location[0] - cardLocation[0]
                val relativeY = event.y + location[1] - cardLocation[1]
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isInSelectionMode = false
                        // Set ripple hotspot and pressed state
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cardView.foreground?.setHotspot(relativeX, relativeY)
                        }
                        cardView.isPressed = true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        // Check if text selection has started
                        if (tv.hasSelection()) {
                            isInSelectionMode = true
                            // Cancel ripple when selecting text
                            cardView.isPressed = false
                        }
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        cardView.isPressed = false
                    }
                    
                    MotionEvent.ACTION_CANCEL -> {
                        cardView.isPressed = false
                    }
                }
                
                // Don't consume - let TextView handle for text selection
                false
            }
            
            // Click on TextView - copy entire content
            textView.setOnClickListener {
                copyToClipboard(currentNote?.content ?: "")
            }
            
            // Long press on TextView
            textView.setOnLongClickListener { v ->
                val tv = v as TextView
                val layout = tv.layout
                
                if (layout == null || tv.text.isNullOrEmpty()) {
                    copyToClipboard(currentNote?.content ?: "")
                    return@setOnLongClickListener true
                }
                
                // Get last touch position (we need to check if it's on text)
                // Since textIsSelectable=true, system will handle text selection
                // We only need to handle long press on non-text areas
                
                // Let system handle - it will start text selection if on text
                // If not on text, the selection won't start and we can copy
                
                // Return false to let system handle text selection
                false
            }
        }
        
        private fun copyToClipboard(content: String) {
            val context = binding.root.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", content)
            clipboard.setPrimaryClip(clip)
            
            // Show custom capsule toast
            val inflater = LayoutInflater.from(context)
            val layout = inflater.inflate(R.layout.toast_copied, null)
            
            Toast(context).apply {
                duration = Toast.LENGTH_SHORT
                view = layout
                setGravity(Gravity.CENTER, 0, 0)
                show()
            }
        }
    }
    
    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
