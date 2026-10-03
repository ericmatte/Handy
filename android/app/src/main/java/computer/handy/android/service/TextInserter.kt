package computer.handy.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import computer.handy.android.core.TextMerger

/** Puts a transcript into the focused field. */
class TextInserter(private val context: Context) {

    enum class Outcome {
        /** Written with ACTION_SET_TEXT, cursor moved after the insertion. */
        SET_TEXT,
        /** SET_TEXT unsupported: pasted through the clipboard. */
        PASTED,
        /** Nothing worked: the text is left on the clipboard for a manual paste. */
        CLIPBOARD_ONLY,
    }

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun insert(node: AccessibilityNodeInfo?, transcript: String): Outcome {
        if (node == null || !node.refresh() || !node.isEditable) {
            copyToClipboard(transcript)
            return Outcome.CLIPBOARD_ONLY
        }

        val merge = TextMerger.merge(
            existing = node.text?.toString(),
            isShowingHint = node.isShowingHintText,
            hint = node.hintText?.toString(),
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            insertion = transcript,
        )

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merge.text)
        }
        // Not verified by re-reading: apps such as Compose update the text on the next frame,
        // and a "failed" check would then paste a second copy.
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merge.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merge.cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            return Outcome.SET_TEXT
        }

        Log.i(TAG, "ACTION_SET_TEXT refused, falling back to paste")
        return if (paste(node, merge.inserted.ifEmpty { transcript })) Outcome.PASTED else Outcome.CLIPBOARD_ONLY
    }

    /**
     * Clipboard + ACTION_PASTE, then restores the previous clip. Android 10+ only lets the
     * focused app or the IME read the clipboard, so the previous clip is often unreadable for
     * an accessibility service; in that case the transcript stays on the clipboard.
     */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val previous: ClipData? = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            null
        }
        copyToClipboard(text, transient = true)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted && previous != null) {
            // Give the target app time to read the clip before swapping it back.
            main.postDelayed({ clipboard.setPrimaryClip(previous) }, RESTORE_DELAY_MS)
        }
        // Paste refused: leave a normal (visible) clip so the user can paste by hand.
        if (!pasted) copyToClipboard(text)
        return pasted
    }

    /** @param transient the clip is only a vehicle for ACTION_PASTE: hide it from previews. */
    private fun copyToClipboard(text: String, transient: Boolean = false) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (transient) {
            // Hides the Android 13+ clipboard preview and keyboard suggestion; ignored before 13.
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
    }

    companion object {
        private const val TAG = "HandyInsert"
        private const val CLIP_LABEL = "Handy"
        private const val RESTORE_DELAY_MS = 500L
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33), inlined for minSdk 29.
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
