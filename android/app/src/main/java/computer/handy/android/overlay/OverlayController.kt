package computer.handy.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import computer.handy.android.R
import computer.handy.android.core.Box

/**
 * Owns the overlay windows (button + long-press menu). Must be created from the
 * AccessibilityService context: TYPE_ACCESSIBILITY_OVERLAY needs its window token,
 * and in exchange needs no SYSTEM_ALERT_WINDOW permission.
 *
 * Every window is FLAG_NOT_FOCUSABLE so the text field keeps input focus (and Gboard stays up).
 */
class OverlayController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onButtonTap()
        fun onHideInThisApp()
        fun onOpenSettings()
        /** Drag finished; [buttonTop] is the new top edge of the visible circle, in screen px. */
        fun onButtonDragged(buttonTop: Int)
        /** Allowed vertical range for the circle's top edge while dragging. */
        fun dragBounds(): IntRange
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var restingAlpha = 0.7f
    private val dimAlpha = 0.4f

    val button = HandyButtonView(context, dp(40))
    private val params = baseParams().apply {
        width = button.totalSizePx
        height = button.totalSizePx
    }

    private var attached = false
    private var hiding = false
    private var dragStartY = 0

    /** Where the visible circle currently is, in screen px. Null while hidden. */
    var currentBox: Box? = null
        private set

    val isShowing: Boolean get() = attached && !hiding

    private var menu: View? = null
    private val dismissMenuRunnable = Runnable { dismissMenu() }

    private val dimRunnable = Runnable {
        if (button.state == ButtonState.IDLE) {
            button.animate().alpha(dimAlpha).setDuration(400).start()
        }
    }

    init {
        button.listener = object : HandyButtonView.Listener {
            override fun onTap() {
                dismissMenu()
                callbacks.onButtonTap()
            }

            override fun onLongPress() = showMenu()

            override fun onDragStart() {
                dismissMenu()
                dragStartY = params.y
            }

            override fun onDrag(totalDy: Int) {
                val range = callbacks.dragBounds()
                val top = (dragStartY + button.padding + totalDy).coerceIn(range.first, range.last)
                params.y = top - button.padding
                currentBox = currentBox?.let { Box.square(it.left, top, button.buttonSizePx) }
                if (attached) windowManager.updateViewLayout(button, params)
            }

            override fun onDragEnd(totalDy: Int) {
                callbacks.onButtonDragged(params.y + button.padding)
            }

            override fun onInteraction() = wake()
        }
    }

    fun configure(sizeDp: Int, opacity: Float) {
        restingAlpha = opacity.coerceIn(0.2f, 1f)
        val sizePx = dp(sizeDp)
        if (button.buttonSizePx != sizePx) {
            button.buttonSizePx = sizePx
            params.width = button.totalSizePx
            params.height = button.totalSizePx
            if (attached) windowManager.updateViewLayout(button, params)
        }
        if (isShowing && button.state == ButtonState.IDLE) button.alpha = restingAlpha
    }

    val buttonSizePx: Int get() = button.buttonSizePx

    /** Shows the button with its circle at [box], or moves it there if already visible. */
    fun showAt(box: Box) {
        if (isShowing && box == currentBox) return
        currentBox = box
        params.x = box.left - button.padding
        params.y = box.top - button.padding
        if (!attached) {
            button.alpha = 0f
            button.scaleX = 0.6f
            button.scaleY = 0.6f
            windowManager.addView(button, params)
            attached = true
        } else {
            windowManager.updateViewLayout(button, params)
        }
        if (hiding || button.alpha < restingAlpha * 0.5f) {
            hiding = false
            button.animate().cancel()
            button.animate()
                .alpha(targetAlpha())
                .scaleX(1f).scaleY(1f)
                .setDuration(ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        scheduleDim()
    }

    fun hide() {
        dismissMenu()
        main.removeCallbacks(dimRunnable)
        currentBox = null
        if (!attached || hiding) return
        hiding = true
        button.animate().cancel()
        button.animate()
            .alpha(0f)
            .scaleX(0.6f).scaleY(0.6f)
            .setDuration(ANIM_MS)
            .withEndAction {
                if (hiding) removeButton()
            }
            .start()
    }

    fun setState(state: ButtonState) {
        button.setState(state)
        wake()
    }

    fun setLevel(level: Float) = button.setLevel(level)

    /** Restores full resting opacity and restarts the 3 s dim timer. */
    fun wake() {
        if (!isShowing) return
        button.animate().alpha(targetAlpha()).setDuration(120).start()
        scheduleDim()
    }

    private fun targetAlpha(): Float =
        if (button.state == ButtonState.IDLE) restingAlpha else 1f

    private fun scheduleDim() {
        main.removeCallbacks(dimRunnable)
        main.postDelayed(dimRunnable, DIM_DELAY_MS)
    }

    private fun removeButton() {
        hiding = false
        if (attached) {
            windowManager.removeViewImmediate(button)
            attached = false
        }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        dismissMenu()
        button.animate().cancel()
        removeButton()
    }

    // --- Long-press menu -----------------------------------------------------------------

    private fun showMenu() {
        dismissMenu()
        val box = currentBox ?: return
        val pad = dp(14)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(context.getColor(R.color.handy_menu_bg))
            }
            elevation = dp(6).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        fun item(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
            setText(textRes)
            setTextColor(context.getColor(R.color.handy_menu_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, dp(10), pad, dp(10))
            isClickable = true
            setOnClickListener {
                dismissMenu()
                onClick()
            }
        }
        layout.addView(item(R.string.menu_hide_in_app) { callbacks.onHideInThisApp() })
        layout.addView(item(R.string.menu_settings) { callbacks.onOpenSettings() })
        layout.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                false
            }
        }
        layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val menuParams = baseParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            // Open to the left of the button, vertically centered on it.
            x = (box.left - layout.measuredWidth - dp(8)).coerceAtLeast(dp(8))
            y = box.top + box.height / 2 - layout.measuredHeight / 2
        }
        windowManager.addView(layout, menuParams)
        menu = layout
        main.postDelayed(dismissMenuRunnable, MENU_TIMEOUT_MS)
    }

    private fun dismissMenu() {
        val m = menu ?: return
        menu = null
        main.removeCallbacks(dismissMenuRunnable)
        windowManager.removeViewImmediate(m)
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Handy"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics)
            .toInt()

    companion object {
        private const val ANIM_MS = 150L
        private const val DIM_DELAY_MS = 3_000L
        private const val MENU_TIMEOUT_MS = 4_000L
    }
}
