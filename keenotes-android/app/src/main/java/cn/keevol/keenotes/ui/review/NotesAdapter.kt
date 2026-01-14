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
            setupInteractions()
        }
        
        /**
         * Unified action: ripple + copy + notify
         */
        private fun performCopyAction() {
            val content = currentNote?.content ?: return
            val context = binding.root.context
            val cardView = binding.root
            
            // 1. Trigger ripple (card click will handle this naturally)
            cardView.isPressed = true
            cardView.postDelayed({ cardView.isPressed = false }, 100)
            
            // 2. Copy to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("note", content)
            clipboard.setPrimaryClip(clip)
            
            // 3. Show toast notification
            val inflater = LayoutInflater.from(context)
            val layout = inflater.inflate(R.layout.toast_copied, null)
            Toast(context).apply {
                duration = Toast.LENGTH_SHORT
                view = layout
                setGravity(Gravity.CENTER, 0, 0)
                show()
            }
        }
        
        /**
         * Setup all interactions:
         * - Any click/tap: performCopyAction()
         * - Long press on text: system text selection
         * - Long press on non-text: performCopyAction()
         */
        private fun setupInteractions() {
            val textView = binding.contentText
            val cardView = binding.root
            
            // Track touch state
            var lastTouchX = 0f
            var lastTouchY = 0f
            var touchDownTime = 0L
            var isInSelectionMode = false
            
            // CardView click - copy action
            cardView.setOnClickListener {
                performCopyAction()
            }
            
            // TextView touch - handle all touch events here
            textView.setOnTouchListener { v, event ->
                val tv = v as TextView
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        touchDownTime = System.currentTimeMillis()
                        isInSelectionMode = false
                        
                        // Set ripple hotspot
                        val location = IntArray(2)
                        tv.getLocationInWindow(location)
                        val cardLocation = IntArray(2)
                        cardView.getLocationInWindow(cardLocation)
                        val relativeX = event.x + location[0] - cardLocation[0]
                        val relativeY = event.y + location[1] - cardLocation[1]
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cardView.foreground?.setHotspot(relativeX, relativeY)
                        }
                        cardView.isPressed = true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        // Check if text selection has started
                        if (tv.hasSelection()) {
                            isInSelectionMode = true
                            cardView.isPressed = false
                        }
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        cardView.isPressed = false
                        
                        val touchDuration = System.currentTimeMillis() - touchDownTime
                        val isQuickTap = touchDuration < 500 // Less than 500ms is a tap
                        
                        // If it's a quick tap and not in selection mode, perform copy
                        if (isQuickTap && !isInSelectionMode && !tv.hasSelection()) {
                            performCopyAction()
                            // Return true to consume the event and prevent onClick from firing twice
                            return@setOnTouchListener true
                        }
                    }
                    
                    MotionEvent.ACTION_CANCEL -> {
                        cardView.isPressed = false
                    }
                }
                
                false // Let TextView handle for text selection
            }
            
            // TextView click - backup for cases where onTouchListener doesn't catch it
            textView.setOnClickListener {
                // Only perform if not already handled by onTouchListener
                if (!textView.hasSelection()) {
                    performCopyAction()
                }
            }
            
            // TextView long press - only allow text selection if on text
            textView.setOnLongClickListener { v ->
                val tv = v as TextView
                val layout = tv.layout ?: return@setOnLongClickListener run {
                    performCopyAction()
                    true
                }
                
                if (isPointOnText(tv, layout, lastTouchX, lastTouchY)) {
                    // On text - let system handle text selection
                    false
                } else {
                    // Not on text - copy action
                    performCopyAction()
                    true
                }
            }
        }
        
        /**
         * Check if touch point is on actual text content
         */
        private fun isPointOnText(textView: TextView, layout: Layout, x: Float, y: Float): Boolean {
            val textX = x - textView.paddingLeft
            val textY = y - textView.paddingTop
            
            if (textX < 0 || textY < 0) return false
            
            val line = layout.getLineForVertical(textY.toInt())
            if (line < 0 || line >= layout.lineCount) return false
            
            val lineLeft = layout.getLineLeft(line)
            val lineRight = layout.getLineRight(line)
            
            return textX >= lineLeft && textX <= lineRight
        }
    }
    
    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
