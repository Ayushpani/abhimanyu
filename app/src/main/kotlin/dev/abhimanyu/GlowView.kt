package dev.abhimanyu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import kotlin.math.min

/**
 * The edge glow. A soft saffron-magenta-indigo light hugging the screen edges that:
 *  - follows your voice while listening,
 *  - circles the edge like a turning chakra while thinking,
 *  - flashes once when done and fades in a dim warm tone on failure.
 *
 * It never gets in the way: it ignores touches, can't take focus, is hidden from
 * accessibility, and draws nothing at all when idle (no frames, no battery).
 */
class GlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, DONE, FAILED }

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = this@GlowView.shader }
    private val density = resources.displayMetrics.density

    private var mode = Mode.IDLE
    private var modeStartedAt = 0L
    private var intensity = 0f
    private var level = 0f
    private var targetLevel = 0f
    private val startTime = SystemClock.uptimeMillis()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** Touches always pass straight through to whatever is underneath. */
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        modeStartedAt = SystemClock.uptimeMillis()
        if (newMode != Mode.LISTENING) targetLevel = 0f
        postInvalidateOnAnimation()
    }

    /** Microphone loudness, 0..1. Called from the audio thread. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    private fun cornerRadius(): Float {
        val corner = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        return corner?.radius?.toFloat() ?: (40f * density)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val sinceMode = now - modeStartedAt
        val animate = ValueAnimator.areAnimatorsEnabled()

        // Where the brightness is heading in the current mode.
        val target = when (mode) {
            Mode.IDLE -> 0f
            Mode.LISTENING, Mode.THINKING -> 1f
            Mode.DONE -> if (sinceMode < 160) 1.35f else 0f
            Mode.FAILED -> if (sinceMode < 120) 0.8f else 0f
        }
        val fade = when (mode) {
            Mode.DONE -> 0.12f       // about 300 ms to fade out
            Mode.FAILED -> 0.09f
            else -> 0.18f
        }
        intensity += (target - intensity) * fade
        level += (targetLevel - level) * 0.25f

        if ((mode == Mode.DONE || mode == Mode.FAILED) && sinceMode > 600 && intensity < 0.01f) {
            mode = Mode.IDLE
        }
        if (mode == Mode.IDLE && intensity < 0.005f) {
            intensity = 0f
            return // draw nothing; don't schedule another frame
        }

        val w = width.toFloat()
        val h = height.toFloat()
        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", if (animate) (now - startTime) / 1000f else 0f)
        shader.setFloatUniform("iLevel", if (animate) level else 0f)
        shader.setFloatUniform("iIntensity", min(intensity, 1.35f))
        shader.setFloatUniform("iCorner", min(cornerRadius(), min(w, h) / 2f))
        shader.setFloatUniform("iThickness", 14f * density)
        shader.setFloatUniform(
            "iMode",
            when (mode) {
                Mode.THINKING -> 1f
                Mode.DONE -> 2f
                Mode.FAILED -> 3f
                else -> 0f
            },
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        postInvalidateOnAnimation()
    }

    companion object {
        // AGSL: distance from the rounded screen edge drives an exponential glow; colour sweeps
        // around the perimeter; THINKING adds a bright head travelling around the edge.
        private val AGSL = """
            uniform float2 iResolution;
            uniform float iTime;
            uniform float iLevel;
            uniform float iIntensity;
            uniform float iCorner;
            uniform float iThickness;
            uniform float iMode;

            float sdRoundRect(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + float2(r, r);
                return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 fragCoord) {
                float2 c = iResolution * 0.5;
                float2 p = fragCoord - c;
                float d = max(-sdRoundRect(p, c, iCorner), 0.0);
                float thick = iThickness * (0.7 + 1.1 * iLevel);
                float glow = exp(-d / thick);
                if (glow < 0.004) { return half4(0.0); }

                float ang = atan(p.y, p.x);
                float a = ang / 6.2831853 + 0.5;
                float3 saffron = float3(1.0, 0.58, 0.19);
                float3 magenta = float3(0.92, 0.22, 0.58);
                float3 indigo  = float3(0.33, 0.28, 0.95);
                float w = fract(a + iTime * 0.07);
                float3 col;
                if (w < 0.3333) {
                    col = mix(saffron, magenta, w * 3.0);
                } else if (w < 0.6666) {
                    col = mix(magenta, indigo, (w - 0.3333) * 3.0);
                } else {
                    col = mix(indigo, saffron, (w - 0.6666) * 3.0);
                }

                float k = glow * (0.85 + 0.15 * sin(ang * 6.0 + iTime * 5.0) * iLevel);
                if (iMode > 0.5 && iMode < 1.5) {
                    float head = fract(a - iTime * 0.45);
                    k *= 0.3 + 1.0 * pow(1.0 - head, 5.0);
                } else if (iMode > 2.5) {
                    col = mix(col, float3(0.85, 0.36, 0.2), 0.7) * 0.7;
                }
                float alpha = clamp(k * iIntensity, 0.0, 1.0);
                return half4(half3(col * alpha), half(alpha));
            }
        """.trimIndent()
    }
}
