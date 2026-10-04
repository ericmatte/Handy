package computer.handy.android.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scrolling voice-level bars: one bar per two 30 ms microphone frames, newest on the right, so you
 * can see at a glance that Handy hears you (and how loud). Draws nothing expensive: a fixed
 * number of rounded rectangles, invalidated only when a new level arrives.
 */
class WaveformView(context: Context) : View(context) {

    private val barWidth = dp(3f)
    private val gap = dp(2.5f)
    private val minBar = dp(3f)
    private var levels = FloatArray(0)
    private var head = 0
    private val rect = RectF()
    private var pending = 0f
    private var pendingFrames = 0

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Bars are dimmed (not listening) or in the accent color (listening). */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun setColors(activeColor: Int, idleColor: Int) {
        activePaint.color = activeColor
        idlePaint.color = idleColor
        invalidate()
    }

    /** Adds the loudness (0..1) of the latest frame. */
    fun push(level: Float) {
        if (levels.isEmpty()) return
        // One bar per FRAMES_PER_BAR frames (the loudest), so the bars scroll at a calm pace.
        pending = max(pending, level)
        if (++pendingFrames < FRAMES_PER_BAR) return
        val loudest = pending
        pending = 0f
        pendingFrames = 0
        // sqrt: speech energy is mostly low; this keeps quiet speech visible without clipping loud.
        levels[head] = sqrt(loudest.coerceIn(0f, 1f))
        head = (head + 1) % levels.size
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        head = 0
        pending = 0f
        pendingFrames = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val count = max(1, ((w + gap) / (barWidth + gap)).toInt())
        if (count != levels.size) {
            levels = FloatArray(count)
            head = 0
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (levels.isEmpty()) return
        val paint = if (active) activePaint else idlePaint
        val centerY = height / 2f
        val maxHalf = height / 2f
        val radius = barWidth / 2f
        // Oldest bar first, from the left.
        for (i in levels.indices) {
            val level = levels[(head + i) % levels.size]
            val half = max(minBar / 2f, level * maxHalf)
            val left = i * (barWidth + gap)
            rect.set(left, centerY - half, left + barWidth, centerY + half)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        const val FRAMES_PER_BAR = 2
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
