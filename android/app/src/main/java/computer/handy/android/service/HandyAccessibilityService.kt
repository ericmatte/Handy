package computer.handy.android.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.core.TapAction
import computer.handy.android.core.Box
import computer.handy.android.core.ButtonPlacer
import computer.handy.android.core.ExclusionMatcher
import computer.handy.android.core.FieldInfo
import computer.handy.android.core.SensitiveFieldDetector
import computer.handy.android.overlay.OverlayController
import computer.handy.android.settings.HandyPrefs
import computer.handy.android.settings.InstalledApps
import computer.handy.android.ui.MainActivity
import computer.handy.android.ui.TestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches input focus and shows the Handy button next to eligible text fields.
 *
 * Battery: no polling, no wake locks. Only three cheap event types are subscribed while no
 * field is focused. Scroll and window-list events are subscribed only while an eligible field
 * has focus (to hide on scroll and to track the keyboard), and every event is debounced into a
 * single [evaluate] pass on the main thread.
 */
class HandyAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: HandyPrefs
    private lateinit var app: HandyApp
    private lateinit var overlay: OverlayController
    private lateinit var dictation: DictationController

    private var excluded: Set<String> = emptySet()
    private var launchers: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private var extendedEvents = false

    /** The field the button is attached to. */
    private var target: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null
    /** Button position without the user's drag offset, to compute that offset after a drag. */
    private var basePlacement: Box? = null
    private var lastKeyboard: Box? = null

    private val evaluateRunnable = Runnable { evaluate() }
    private val rect = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = HandyPrefs(this)
        overlay = OverlayController(this, overlayCallbacks)
        overlay.configure(prefs.buttonSizeDp, prefs.buttonOpacity)
        app = application as HandyApp
        dictation = DictationController(
            context = this,
            app = app,
            overlay = overlay,
            scope = scope,
        )
        excluded = prefs.excludedPackages
        prefs.registerListener(this)
        refreshPackageSets()
        if (!prefs.defaultsSeeded) {
            scope.launch(Dispatchers.IO) { InstalledApps.seedDefaultExclusions(this@HandyAccessibilityService, prefs) }
        }
        setExtendedEvents(false)
    }

    private fun refreshPackageSets() {
        scope.launch(Dispatchers.IO) {
            val homes = InstalledApps.launchers(this@HandyAccessibilityService)
            val imes = getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapTo(mutableSetOf()) { it.packageName }
                .orEmpty()
            main.post {
                launchers = homes
                imePackages = imes
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Typing in a multi-line field scrolls the field itself: not a reason to hide.
                val source = event.source
                if (source != null && source == target) return
                if (!dictation.isBusy) overlay.hide()
                schedule(SCROLL_SETTLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                // A new screen or dialog in another app: hide now, re-check once it settles.
                // The keyboard's own window shows up here too and must not hide the button.
                if (pkg != null && pkg != targetPackage && pkg !in imePackages && !dictation.isBusy) {
                    overlay.hide()
                }
                schedule(DEBOUNCE_MS)
            }
            else -> schedule(DEBOUNCE_MS)
        }
    }

    /** Spoken/haptic feedback interruption: Handy gives none, nothing to stop. */
    override fun onInterrupt() = Unit

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(evaluateRunnable)
        main.postDelayed(evaluateRunnable, delayMs)
    }

    /** Single source of truth: look at the current input focus and show/move/hide the button. */
    private fun evaluate() {
        if (dictation.isBusy) {
            // Keep the button where it is during a dictation; follow the field if it moved.
            target?.let { node -> if (node.refresh()) placeFor(node, node.packageName?.toString()) }
            return
        }

        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val pkg = node?.packageName?.toString()
        if (node == null || !node.isEditable || !isEligible(node, pkg)) {
            detach()
            return
        }

        // An eligible field has focus: listen to keyboard/scroll changes until it loses it.
        setExtendedEvents(true)
        target = node
        targetPackage = pkg

        if (!node.isVisibleToUser) {
            overlay.hide()
            return
        }
        placeFor(node, pkg)
    }

    private fun isEligible(node: AccessibilityNodeInfo, pkg: String?): Boolean {
        if (node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        val ownTestScreen = pkg == packageName && TestActivity.isVisible
        if (!ownTestScreen &&
            ExclusionMatcher.isExcluded(pkg, packageName, launchers, excluded)
        ) {
            return false
        }
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        val field = FieldInfo(
            isEditable = node.isEditable,
            isPassword = node.isPassword,
            inputType = node.inputType,
            hint = node.hintText?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
        )
        return SensitiveFieldDetector.isEligible(field)
    }

    private fun placeFor(node: AccessibilityNodeInfo, pkg: String?) {
        val keyboard = keyboardBounds()
        lastKeyboard = keyboard
        if (keyboard == null && !dictation.isBusy) {
            // Keyboard closed while the field kept focus: step aside until it comes back.
            overlay.hide()
            return
        }
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            if (!dictation.isBusy) overlay.hide()
            return
        }
        val field = Box(rect.left, rect.top, rect.right, rect.bottom)
        val screen = screenBox()
        val size = overlay.buttonSizePx
        val gap = dp(6)
        basePlacement = ButtonPlacer.place(field, screen, keyboard, size, gap)
        val offsetPx = pkg?.let { dp(prefs.offsetDp(it)) } ?: 0
        val placed = if (offsetPx == 0) basePlacement!! else ButtonPlacer.place(field, screen, keyboard, size, gap, offsetPx)
        overlay.showAt(placed)
    }

    private fun keyboardBounds(): Box? {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        ime.getBoundsInScreen(rect)
        return if (rect.isEmpty) null else Box(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun screenBox(): Box {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Box(b.left, b.top, b.right, b.bottom)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Box(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun detach() {
        target = null
        targetPackage = null
        basePlacement = null
        overlay.hide()
        setExtendedEvents(false)
    }

    /** Subscribes to scroll/window-list events only while they are useful. */
    private fun setExtendedEvents(enabled: Boolean) {
        val info = serviceInfo ?: return
        val wanted = if (enabled) BASE_EVENTS or EXTENDED_EVENTS else BASE_EVENTS
        if (extendedEvents == enabled && info.eventTypes == wanted) return
        extendedEvents = enabled
        info.eventTypes = wanted
        serviceInfo = info
    }

    private val overlayCallbacks = object : OverlayController.Callbacks {
        override fun onButtonTap() {
            dictation.toggle(target, withPostProcess = prefs.tapAction == TapAction.TRANSCRIBE_WITH_POST_PROCESS)
        }

        override fun alternateActionLabel(): Int? {
            if (!prefs.postProcessEnabled || dictation.isBusy) return null
            return if (prefs.tapAction == TapAction.TRANSCRIBE) R.string.menu_dictate_with_claude else R.string.menu_dictate_without_claude
        }

        override fun onAlternateAction() {
            dictation.toggle(target, withPostProcess = prefs.tapAction == TapAction.TRANSCRIBE)
        }

        override fun onHideInThisApp() {
            targetPackage?.let { prefs.addExcluded(it) }
            detach()
        }

        override fun onOpenSettings() {
            startActivity(Intent(this@HandyAccessibilityService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun onButtonDragged(buttonTop: Int) {
            val pkg = targetPackage ?: return
            val base = basePlacement ?: return
            prefs.setOffsetDp(pkg, pxToDp(buttonTop - base.top))
        }

        override fun dragBounds(): IntRange {
            val screen = screenBox()
            val bottomLimit = (lastKeyboard?.top ?: screen.bottom) - overlay.buttonSizePx - dp(4)
            return screen.top..maxOf(screen.top, bottomLimit)
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            HandyPrefs.KEY_EXCLUDED -> {
                excluded = prefs.excludedPackages
                schedule(DEBOUNCE_MS)
            }
            HandyPrefs.KEY_UNLOAD -> dictation.scheduleUnload()
            HandyPrefs.KEY_OPACITY, HandyPrefs.KEY_SIZE -> {
                overlay.configure(prefs.buttonSizeDp, prefs.buttonOpacity)
                schedule(DEBOUNCE_MS)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION") // still delivered for RUNNING_* levels on recent Android
        if (level >= TRIM_MEMORY_RUNNING_LOW && ::dictation.isInitialized) dictation.releaseModelIfIdle()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private var tornDown = false

    private fun teardown() {
        if (tornDown || !::prefs.isInitialized) return
        tornDown = true
        main.removeCallbacksAndMessages(null)
        prefs.unregisterListener(this)
        dictation.release()
        overlay.destroy()
        scope.cancel()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pxToDp(px: Int): Int = (px / resources.displayMetrics.density).toInt()

    companion object {
        private const val DEBOUNCE_MS = 80L
        private const val SCROLL_SETTLE_MS = 400L

        /** Always on: what is needed to notice that a field got or lost focus. */
        const val BASE_EVENTS = AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        /** Only while an eligible field is focused: keyboard show/hide and scrolling. */
        const val EXTENDED_EVENTS = AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }
}
