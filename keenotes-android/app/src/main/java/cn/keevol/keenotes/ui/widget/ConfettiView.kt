package cn.keevol.keenotes.ui.widget

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.random.Random

/**
 * Confetti helper using Choreographer for frame-by-frame animation.
 * Does NOT rely on ValueAnimator/ObjectAnimator, so it works even when
 * system animator duration scale is set to 0x.
 */
object ConfettiHelper {

    private const val TAG = "Confetti"

    private val colors = intArrayOf(
        Color.RED, Color.parseColor("#FF9800"), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.parseColor("#9C27B0"),
        Color.parseColor("#E91E63"), Color.CYAN
    )

    private class Particle(
        val view: View,
        val startX: Float,
        val startY: Float,
        val dx: Float,
        val dy: Float,
        val maxRotation: Float,
        val duration: Long,   // ms
        val delay: Long       // ms
    )

    fun fire(activity: Activity) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val w = rootView.width
        val h = rootView.height
        val density = activity.resources.displayMetrics.density

        Log.d(TAG, "fire(): ${w}x${h}, density=$density")
        if (w == 0 || h == 0) return

        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }
        rootView.addView(overlay)

        val particles = mutableListOf<Particle>()
        val total = 50

        repeat(total) {
            val size = ((Random.nextFloat() * 6f + 6f) * density).toInt()
            val color = colors[Random.nextInt(colors.size)]
            val startX = w / 2f + (Random.nextFloat() - 0.5f) * w * 0.3f
            val startY = h * 0.75f
            val dx = (Random.nextFloat() - 0.5f) * w * 0.8f
            val dy = -(Random.nextFloat() * h * 0.5f + h * 0.1f)
            val maxRotation = (Random.nextFloat() - 0.5f) * 720f
            val duration = 1500L + Random.nextLong(1000L)
            val delay = Random.nextLong(100L)

            val pView = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = if (Random.nextBoolean()) GradientDrawable.OVAL
                            else GradientDrawable.RECTANGLE
                    setColor(color)
                    cornerRadius = 2f * density
                }
                x = startX
                y = startY
            }
            overlay.addView(pView, FrameLayout.LayoutParams(size, size))
            particles.add(Particle(pView, startX, startY, dx, dy, maxRotation, duration, delay))
        }

        // Drive animation manually via Choreographer (bypasses animator duration scale)
        val choreographer = Choreographer.getInstance()
        val startTime = System.nanoTime()

        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val elapsedMs = (frameTimeNanos - startTime) / 1_000_000L
                var allDone = true

                for (p in particles) {
                    val localElapsed = elapsedMs - p.delay
                    if (localElapsed < 0) {
                        allDone = false
                        continue
                    }
                    val fraction = (localElapsed.toFloat() / p.duration).coerceIn(0f, 1f)
                    // Decelerate interpolation: 1 - (1-t)^2
                    val f = 1f - (1f - fraction) * (1f - fraction)

                    p.view.x = p.startX + p.dx * f
                    p.view.y = p.startY + p.dy * f
                    p.view.alpha = 1f - f
                    p.view.rotation = p.maxRotation * f

                    if (fraction < 1f) allDone = false
                }

                if (allDone) {
                    Log.d(TAG, "animation complete, removing overlay")
                    rootView.removeView(overlay)
                } else {
                    choreographer.postFrameCallback(this)
                }
            }
        }
        choreographer.postFrameCallback(frameCallback)
        Log.d(TAG, "$total particles created, Choreographer driving animation")
    }
}
