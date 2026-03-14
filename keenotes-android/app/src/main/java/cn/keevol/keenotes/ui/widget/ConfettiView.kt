package cn.keevol.keenotes.ui.widget

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.random.Random

/**
 * Confetti helper that dynamically adds colored particle views to a container
 * and animates them from bottom to top. No custom onDraw — uses standard View animations.
 */
object ConfettiHelper {

    private val colors = intArrayOf(
        Color.RED, Color.parseColor("#FF9800"), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.parseColor("#9C27B0"),
        Color.parseColor("#E91E63"), Color.CYAN
    )

    /**
     * Fire confetti by adding animated particle views to the given [container].
     * Particles auto-remove after animation completes.
     */
    fun fire(container: ViewGroup) {
        val context = container.context
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return

        val density = context.resources.displayMetrics.density

        repeat(50) {
            val size = ((Random.nextFloat() * 6f + 6f) * density).toInt()
            val color = colors[Random.nextInt(colors.size)]

            val particle = View(context).apply {
                background = createShape(color, size, density)
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            }

            // Starting position: bottom center area
            val startX = w / 2f + (Random.nextFloat() - 0.5f) * w * 0.3f
            val startY = h * 0.85f
            particle.x = startX
            particle.y = startY

            container.addView(particle)

            // Target position: upward and spread
            val endX = startX + (Random.nextFloat() - 0.5f) * w * 0.8f
            val endY = startY - (Random.nextFloat() * h * 0.6f + h * 0.1f)

            val animX = ObjectAnimator.ofFloat(particle, "x", startX, endX)
            val animY = ObjectAnimator.ofFloat(particle, "y", startY, endY)
            val animAlpha = ObjectAnimator.ofFloat(particle, "alpha", 1f, 0f)
            val animRotation = ObjectAnimator.ofFloat(particle, "rotation", 0f, (Random.nextFloat() - 0.5f) * 720f)
            val animScaleX = ObjectAnimator.ofFloat(particle, "scaleX", 0.2f, Random.nextFloat() * 0.9f + 0.3f)
            val animScaleY = ObjectAnimator.ofFloat(particle, "scaleY", 0.2f, Random.nextFloat() * 0.9f + 0.3f)

            AnimatorSet().apply {
                playTogether(animX, animY, animAlpha, animRotation, animScaleX, animScaleY)
                duration = 2000L
                interpolator = LinearInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        container.removeView(particle)
                    }
                })
                start()
            }
        }
    }

    private fun createShape(color: Int, size: Int, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = when (Random.nextInt(3)) {
                0 -> GradientDrawable.OVAL
                else -> GradientDrawable.RECTANGLE
            }
            setColor(color)
            cornerRadius = if (shape == GradientDrawable.RECTANGLE) 2f * density else 0f
        }
    }
}
