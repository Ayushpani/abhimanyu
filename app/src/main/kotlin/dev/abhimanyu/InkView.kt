package dev.abhimanyu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Syahi motion. While you speak, your voice spreads from the mic as ink rings in water, with
 * slightly uneven, inky edges. While Abhimanyu thinks, a brush draws a single circle (ensō)
 * around the mic; when the task is done the circle completes and the vermilion seal is pressed.
 *
 * It never gets in the way: no touches, no focus, hidden from accessibility, and nothing is drawn
 * (and no frames are scheduled) when idle.
 */
class InkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, DONE, FAILED }

    private val density = resources.displayMetrics.density
    private val inkColor = context.getColor(R.color.ink)
    private val softColor = context.getColor(R.color.ink_soft)
    private val sealColor = context.getColor(R.color.seal)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val arcRect = RectF()

    private var mode = Mode.IDLE
    private var modeStartedAt = 0L
    @Volatile private var level = 0f
    private var smoothLevel = 0f
    private var lastSpawn = 0L
    private var anchor: View? = null

    private class Ring(val born: Long, val strength: Float, val seed: Float)
    private val rings = ArrayList<Ring>()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    /** The rings and the brush circle centre on this view (the mic button). */
    fun anchorTo(view: View) { anchor = view }

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        modeStartedAt = SystemClock.uptimeMillis()
        if (newMode != Mode.LISTENING) level = 0f
        postInvalidateOnAnimation()
    }

    /** Microphone loudness, 0..1. Called from the audio thread. */
    fun setLevel(value: Float) { level = value.coerceIn(0f, 1f) }

    private fun center(): Pair<Float, Float> {
        val a = anchor ?: return width / 2f to height * 0.8f
        val me = IntArray(2).also { getLocationInWindow(it) }
        val pos = IntArray(2).also { a.getLocationInWindow(it) }
        return (pos[0] - me[0] + a.width / 2f) to (pos[1] - me[1] + a.height / 2f)
    }

    private fun ease(x: Float): Float = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val el = now - modeStartedAt
        val motion = ValueAnimator.areAnimatorsEnabled()
        val (cx, cy) = center()
        val micR = (anchor?.width ?: (84 * density).toInt()) / 2f
        smoothLevel += (level - smoothLevel) * 0.3f

        // ---- ink rings while listening ----
        if (mode == Mode.LISTENING && motion && now - lastSpawn > 380) {
            lastSpawn = now
            rings += Ring(now, 0.35f + smoothLevel * 0.65f, (now % 1000) / 100f)
        }
        rings.removeAll { now - it.born > RING_LIFE }
        for (r in rings) {
            val age = (now - r.born).toFloat() / RING_LIFE
            val rad = micR + 6 * density + age * 150 * density
            ringPaint.color = withAlpha(inkColor, (1 - age) * 0.55f * r.strength * (if (mode == Mode.FAILED) 0.6f else 1f))
            ringPaint.strokeWidth = (1 + (1 - age) * 3 * r.strength) * density
            path.reset()
            for (i in 0..72) {
                val th = (i / 72f) * 2 * PI.toFloat()
                // uneven edge, like ink spreading on wet paper
                val j = 1 + 0.035f * sin(th * 5 + r.seed) + 0.02f * sin(th * 11 + r.seed * 2)
                val x = cx + cos(th) * rad * j
                val y = cy + sin(th) * rad * j * 0.94f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, ringPaint)
        }

        // ---- the brush circle (ensō) while thinking, completed when done ----
        val R = micR + 26 * density
        when (mode) {
            Mode.THINKING -> drawEnso(canvas, cx, cy, R, if (motion) min(0.82f, el / 1800f * 0.82f) else 0.82f,
                if (motion) el / 2600f else 0f, 1f, inkColor)
            Mode.DONE -> {
                val fade = 1 - ease((el - 1300) / 1100f)
                drawEnso(canvas, cx, cy, R, 1f, 0f, fade, inkColor)
                sealPaint.color = withAlpha(sealColor, fade)
                val s = 9 * density
                val sx = cx + R * 0.72f
                val sy = cy - R * 0.95f
                canvas.drawRect(sx, sy, sx + s, sy + s, sealPaint)
            }
            Mode.FAILED -> {
                val fade = 1 - ease((el - 600) / 900f)
                drawEnso(canvas, cx, cy, R, 0.35f, 0f, fade * 0.8f, softColor)
            }
            else -> Unit
        }

        val busy = mode == Mode.LISTENING || mode == Mode.THINKING ||
            (mode == Mode.DONE && el < 2500) || (mode == Mode.FAILED && el < 1600) || rings.isNotEmpty()
        if ((mode == Mode.DONE || mode == Mode.FAILED) && !busy) mode = Mode.IDLE
        if (busy) postInvalidateOnAnimation()
    }

    /** A brush stroke around a circle whose thickness swells and tapers like a real ensō. */
    private fun drawEnso(canvas: Canvas, cx: Float, cy: Float, r: Float, portion: Float, turn: Float, alpha: Float, color: Int) {
        if (alpha <= 0f) return
        val segs = 90
        val start = (-0.6f * PI.toFloat() + turn) * 180f / PI.toFloat()
        brushPaint.color = withAlpha(color, 0.85f * alpha)
        val drawn = (segs * portion).toInt()
        for (k in 0 until drawn) {
            val p = k.toFloat() / segs
            val thick = 2f + 7f * sin(PI.toFloat() * min(p * 1.15f, 1f)) * (0.8f + 0.2f * sin(k.toFloat()))
            brushPaint.strokeWidth = thick * density
            val wobble = sin(k * 0.7f) * 1.2f * density
            arcRect.set(cx - r - wobble, cy - r - wobble, cx + r + wobble, cy + r + wobble)
            canvas.drawArc(arcRect, start + k * 4f, 4.4f, false, brushPaint)
        }
    }

    companion object {
        private const val RING_LIFE = 2600L
    }
}
