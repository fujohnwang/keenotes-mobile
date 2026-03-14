package cn.keevol.keenotes.ui.widget

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Confetti helper inspired by Swiftetti's Default Preset physics.
 * Uses Choreographer for frame-by-frame animation (bypasses animator duration scale).
 *
 * Physics: cone burst upward → gravity pull down → wobble side-to-side → fade out.
 */
object ConfettiHelper {

    private const val TAG = "Confetti"

    // Default Preset parameters (matching Swiftetti)
    private const val PARTICLE_COUNT = 100
    private const val BURST_SPEED_MIN = 2000.0
    private const val BURST_SPEED_MAX = 10000.0
    private const val BURST_DIRECTION = 270.0  // degrees, 270 = upward
    private const val CONE_SPREAD = 120.0       // upwardBias in degrees
    private const val GRAVITY = 1000.0
    private const val MASS_MIN = 0.5
    private const val MASS_MAX = 1.5
    private const val DRAG_MIN = 0.8
    private const val DRAG_MAX = 1.2
    private const val FALL_DURATION_BASE = 0.3
    private const val WOBBLE_AMP_MIN = 5.0
    private const val WOBBLE_AMP_MAX = 15.0
    private const val WOBBLE_FREQ_MIN = 2.0
    private const val WOBBLE_FREQ_MAX = 5.0
    private const val WOBBLE_DECAY = 1.0
    private const val SIZE_MIN = 2f
    private const val SIZE_MAX = 20f
    private const val FADE_START = 0.8
    private const val FADE_DURATION = 0.2

    // White/silver/gray/blue palette (Swiftetti default)
    private val colors = intArrayOf(
        Color.WHITE,
        Color.parseColor("#C0C0C0"),
        Color.parseColor("#B2B0B0"),
        Color.parseColor("#007AFF")
    )

    private class Particle(
        val view: View,
        val startX: Float,
        val startY: Float,
        val vx: Double,
        val vy: Double,
        val mass: Double,
        val wobbleAmp: Double,
        val wobbleFreq: Double,
        val rotationSpeed: Float,   // degrees per second
        val totalDuration: Double,  // seconds
        val delay: Long             // ms
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

        val dirRad = Math.toRadians(BURST_DIRECTION)
        val coneRad = Math.toRadians(CONE_SPREAD)
        val burstX = w * 0.5f
        val burstY = 400f * density  // 400dp from top, matching iOS

        val particles = mutableListOf<Particle>()

        repeat(PARTICLE_COUNT) {
            val angle = dirRad + Random.nextDouble(-coneRad / 2, coneRad / 2)
            val speed = Random.nextDouble(BURST_SPEED_MIN, BURST_SPEED_MAX)
            val mass = Random.nextDouble(MASS_MIN, MASS_MAX)
            val drag = Random.nextDouble(DRAG_MIN, DRAG_MAX)
            val totalDuration = FALL_DURATION_BASE + (1.0 / mass) + (drag * 0.5)

            val size = (Random.nextFloat() * (SIZE_MAX - SIZE_MIN) + SIZE_MIN) * density
            val color = colors[Random.nextInt(colors.size)]
            val isCircle = Random.nextFloat() < 0.2f  // 20% circles, 80% rectangles
            val aspectRatio = if (isCircle) 1f else Random.nextFloat() * 0.4f + 0.4f  // 0.4~0.8 for paper-like

            val pWidth = size
            val pHeight = (size * aspectRatio).toInt().coerceAtLeast(2)

            val pView = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                    setColor(color)
                    if (!isCircle) cornerRadius = 1f * density
                }
                x = burstX + Random.nextFloat() * 40f - 20f
                y = burstY
            }
            overlay.addView(pView, FrameLayout.LayoutParams(pWidth.toInt().coerceAtLeast(2), pHeight))

            particles.add(Particle(
                view = pView,
                startX = pView.x,
                startY = burstY,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                mass = mass,
                wobbleAmp = Random.nextDouble(WOBBLE_AMP_MIN, WOBBLE_AMP_MAX),
                wobbleFreq = Random.nextDouble(WOBBLE_FREQ_MIN, WOBBLE_FREQ_MAX),
                rotationSpeed = Random.nextFloat() * 720f - 360f,  // -360~360 deg/s
                totalDuration = totalDuration,
                delay = Random.nextLong(50L)
            ))
        }

        val choreographer = Choreographer.getInstance()
        val startTime = System.nanoTime()

        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val elapsedMs = (frameTimeNanos - startTime) / 1_000_000L
                var allDone = true

                for (p in particles) {
                    val localMs = elapsedMs - p.delay
                    if (localMs < 0) { allDone = false; continue }

                    val t = localMs / 1000.0
                    val progress = (t / p.totalDuration).coerceIn(0.0, 1.0)

                    // X: velocity with deceleration + wobble
                    val xDecel = 1.0 - progress * 0.9
                    val wobbleDecayF = 1.0 - progress * WOBBLE_DECAY
                    val wobble = sin(t * p.wobbleFreq) * p.wobbleAmp * wobbleDecayF
                    val x = p.startX + (p.vx * t * xDecel * 0.15 + wobble).toFloat()

                    // Y: initial velocity + gravity (parabolic, mass-weighted)
                    val y = p.startY + (p.vy * t * 0.3 + 0.5 * GRAVITY * t * t * p.mass * 0.8).toFloat()

                    // Fade out in last portion
                    val alpha = when {
                        progress < FADE_START -> 1f
                        FADE_DURATION > 0 -> max(0.0, 1.0 - (progress - FADE_START) / FADE_DURATION).toFloat()
                        else -> 0f
                    }

                    p.view.x = x
                    p.view.y = y
                    p.view.alpha = alpha
                    p.view.rotation = p.rotationSpeed * t.toFloat()

                    if (progress < 1.0) allDone = false
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
        Log.d(TAG, "$PARTICLE_COUNT particles created, Choreographer driving")
    }
}
