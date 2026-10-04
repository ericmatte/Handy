package computer.handy.android.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import computer.handy.android.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class ButtonState { IDLE, RECORDING, PROCESSING, SUCCESS, ERROR }

/**
 * The floating Handy pill. Pure drawing + gesture detection; window management lives in
 * [OverlayController]. Animators only run while recording/processing, never at rest.
 *
 * The view is larger than the visible circle: [padding] leaves room for the shadow and the
 * pulsing ring, so the circle is centered in the view.
 */
@SuppressLint("ViewConstructor")
class HandyButtonView(
    context: Context,
    buttonSizePx: Int,
) : View(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragStart()
        /** @param totalDy vertical movement since the drag started, in px */
        fun onDrag(totalDy: Int)
        fun onDragEnd(totalDy: Int)
        fun onInteraction()
    }

    var listener: Listener? = null

    var buttonSizePx: Int = buttonSizePx
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    val padding: Int = dp(14f).toInt()
    val totalSizePx: Int get() = buttonSizePx + padding * 2

    var state: ButtonState = ButtonState.IDLE
        private set

    private val icon: Drawable? = context.getDrawable(R.drawable.ic_handy_button)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.handy_pink)
        setShadowLayer(dp(3f), 0f, dp(1f), 0x40000000)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x1F000000
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.handy_recording)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.handy_recording)
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.handy_ink)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val arcRect = RectF()
    private val checkPath = Path()

    private var targetLevel = 0f
    private var shownLevel = 0f
    private var spinnerAngle = 0f
    private var ticker: ValueAnimator? = null
    private var shake: ObjectAnimator? = null

    init {
        // setShadowLayer needs the shape drawn by the hardware renderer; it is supported there
        // for non-text since API 28, so keep the default layer type.
        contentDescription = context.getString(R.string.overlay_button_description)
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalSizePx, totalSizePx)
    }

    fun setState(newState: ButtonState) {
        if (state == newState) return
        state = newState
        bgPaint.color = context.getColor(
            when (newState) {
                ButtonState.SUCCESS -> R.color.handy_success
                ButtonState.ERROR -> R.color.handy_error
                else -> R.color.handy_pink
            },
        )
        if (newState != ButtonState.RECORDING) {
            targetLevel = 0f
            shownLevel = 0f
        }
        when (newState) {
            ButtonState.RECORDING, ButtonState.PROCESSING -> startTicker()
            else -> stopTicker()
        }
        if (newState == ButtonState.ERROR) playShake()
        updateContentDescription()
        invalidate()
    }

    /** Microphone loudness, 0..1. Smoothed per frame while recording. */
    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(
            when (state) {
                ButtonState.RECORDING -> R.string.overlay_button_recording
                ButtonState.PROCESSING -> R.string.overlay_button_processing
                else -> R.string.overlay_button_description
            },
        )
    }

    private fun startTicker() {
        if (ticker?.isRunning == true) return
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerAngle = (it.animatedValue as Float) * 360f
                // Fast attack, slower release, so the ring "breathes" with the voice.
                val k = if (targetLevel > shownLevel) 0.5f else 0.15f
                shownLevel += (targetLevel - shownLevel) * k
                invalidate()
            }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun playShake() {
        shake?.cancel()
        val d = dp(5f)
        shake = ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, d, -d, d * 0.7f, -d * 0.7f, d * 0.3f, 0f)
            .apply {
                duration = 380
                start()
            }
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        shake?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = buttonSizePx / 2f

        if (state == ButtonState.RECORDING) {
            val breathe = (sin(SystemClock.uptimeMillis() / 220.0) * 0.5 + 0.5).toFloat()
            val extra = dp(2f) + shownLevel * dp(9f) + breathe * dp(1.5f)
            haloPaint.alpha = (50 + shownLevel * 70).toInt()
            canvas.drawCircle(cx, cy, r + extra, haloPaint)
            ringPaint.alpha = 230
            canvas.drawCircle(cx, cy, r + dp(1.5f) + shownLevel * dp(5f), ringPaint)
        }

        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r - borderPaint.strokeWidth / 2f, borderPaint)

        when (state) {
            ButtonState.SUCCESS -> drawCheck(canvas, cx, cy, r)
            ButtonState.ERROR -> drawCross(canvas, cx, cy, r)
            else -> drawIcon(canvas, cx, cy, r)
        }

        if (state == ButtonState.PROCESSING) {
            val inset = dp(3f)
            arcRect.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
            canvas.drawArc(arcRect, spinnerAngle - 90f, 100f, false, spinnerPaint)
        }
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val d = icon ?: return
        val maxSide = r * 2f * 0.6f
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 1
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 1
        val scale = min(maxSide / iw, maxSide / ih)
        val w = iw * scale
        val h = ih * scale
        d.setBounds((cx - w / 2).toInt(), (cy - h / 2).toInt(), (cx + w / 2).toInt(), (cy + h / 2).toInt())
        d.alpha = if (state == ButtonState.PROCESSING) 110 else 255
        d.draw(canvas)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r * 0.42f
        checkPath.reset()
        checkPath.moveTo(cx - s, cy + s * 0.05f)
        checkPath.lineTo(cx - s * 0.3f, cy + s * 0.7f)
        checkPath.lineTo(cx + s, cy - s * 0.6f)
        canvas.drawPath(checkPath, checkPaint)
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r * 0.32f
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, checkPaint)
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, checkPaint)
    }

    // --- Gestures: tap, long press, vertical drag -------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downRawY = 0f
    private var downRawX = 0f
    private var dragging = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        listener?.onLongPress()
    }

    @SuppressLint("ClickableViewAccessibility") // tap is exposed via performClick below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longPressed = false
                listener?.onInteraction()
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                val dx = event.rawX - downRawX
                if (!dragging && !longPressed && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    listener?.onDragStart()
                }
                if (dragging) listener?.onDrag(dy.toInt())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                when {
                    dragging -> listener?.onDragEnd((event.rawY - downRawY).toInt())
                    !longPressed -> performClick()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                if (dragging) listener?.onDragEnd((event.rawY - downRawY).toInt())
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
