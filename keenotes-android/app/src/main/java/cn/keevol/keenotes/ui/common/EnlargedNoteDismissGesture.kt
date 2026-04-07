package cn.keevol.keenotes.ui.common

import android.view.MotionEvent
import android.view.View
import cn.keevol.keenotes.databinding.ViewEnlargedNoteBinding
import kotlin.math.abs

object EnlargedNoteDismissGesture {
    fun attach(
        binding: ViewEnlargedNoteBinding,
        onDismiss: () -> Unit
    ) {
        var swipeStartX = 0f
        var swipeStartY = 0f
        val thresholdPx = 72f * binding.root.resources.displayMetrics.density

        val swipeListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeStartY = event.rawY
                }

                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - swipeStartX
                    val deltaY = event.rawY - swipeStartY
                    val canDismissFromScroll = binding.enlargedContentScroll.scrollY == 0

                    if (canDismissFromScroll &&
                        deltaY > thresholdPx &&
                        deltaY > abs(deltaX) * 1.3f
                    ) {
                        onDismiss()
                        return@OnTouchListener true
                    }
                }
            }
            false
        }

        listOf(
            binding.root,
            binding.enlargedHeaderRow,
            binding.enlargedDateText,
            binding.enlargedChannelText,
            binding.enlargedContentScroll,
            binding.enlargedContentText
        ).forEach { view ->
            view.setOnTouchListener(swipeListener)
        }
    }
}
