package computer.handy.android.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import computer.handy.android.R
import computer.handy.android.overlay.ButtonState
import computer.handy.android.overlay.HandyButtonView

/**
 * The voice keyboard's view: status line, live voice-level bars and three controls
 * (back to the keyboard, the Handy record button, cancel). Plain Views, no Compose: an
 * InputMethodService has no lifecycle owner for Compose.
 */
class VoicePanel(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onMainButton()
        fun onSwitchKeyboard()
        fun onCancel()
        fun onToggleClaude()
        fun onAction()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    val status = TextView(context).apply {
        setTextColor(context.getColor(R.color.ime_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 2
    }

    private val claudeChip = TextView(context).apply {
        setText(R.string.ime_claude_chip)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        setOnClickListener { callbacks.onToggleClaude() }
        visibility = View.GONE
    }

    private val action = TextView(context).apply {
        setTextColor(context.getColor(R.color.handy_recording))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        setOnClickListener { callbacks.onAction() }
        visibility = View.GONE
    }

    val waveform = WaveformView(context).apply {
        setColors(context.getColor(R.color.handy_recording), context.getColor(R.color.ime_wave_idle))
    }

    val button = HandyButtonView(context, dp(60f)).apply {
        listener = object : HandyButtonView.Listener {
            override fun onTap() = callbacks.onMainButton()
            override fun onLongPress() = Unit
            override fun onDragStart() = Unit
            override fun onDrag(totalDy: Int) = Unit
            override fun onDragEnd(totalDy: Int) = Unit
            override fun onInteraction() = Unit
        }
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(context.getColor(R.color.ime_text))
        background = null
        contentDescription = context.getString(description)
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        setOnClickListener { onClick() }
    }

    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(R.color.ime_background))
        val base = dp(12f)
        setPadding(dp(16f), base, dp(16f), base)

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(action)
                addView(claudeChip)
            },
        )
        addView(
            waveform,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64f)).apply {
                topMargin = dp(10f)
                bottomMargin = dp(6f)
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(R.drawable.ic_keyboard, R.string.ime_switch_keyboard) { callbacks.onSwitchKeyboard() })
                addView(
                    FrameLayout(context).apply {
                        addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(iconButton(R.drawable.ic_close, R.string.ime_cancel) { callbacks.onCancel() })
            },
        )

        // Stay above the navigation bar on edge-to-edge devices.
        setOnApplyWindowInsetsListener { v, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + nav)
            insets
        }
    }

    fun setClaude(visible: Boolean, enabled: Boolean) {
        claudeChip.visibility = if (visible) View.VISIBLE else View.GONE
        claudeChip.setTextColor(context.getColor(if (enabled) R.color.handy_ink else R.color.ime_text_secondary))
        claudeChip.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            if (enabled) {
                setColor(context.getColor(R.color.handy_pink))
            } else {
                setColor(0)
                setStroke(dp(1f), context.getColor(R.color.ime_text_secondary))
            }
        }
    }

    /** Shows an extra action link (e.g. "Open Handy") next to the status, or hides it. */
    fun setAction(textRes: Int?) {
        if (textRes == null) {
            action.visibility = View.GONE
        } else {
            action.setText(textRes)
            action.visibility = View.VISIBLE
        }
    }

    fun show(state: ButtonState, statusText: CharSequence, listening: Boolean) {
        button.setState(state)
        status.text = statusText
        waveform.active = listening
    }
}
