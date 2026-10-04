package computer.handy.android.core

/**
 * Result of inserting dictated text into a field.
 * @property text the full new content
 * @property cursor where the cursor goes (right after the insertion)
 * @property inserted the inserted segment including added spaces, used by the paste fallback
 */
data class MergeResult(val text: String, val cursor: Int, val inserted: String)

/**
 * Merges a transcription into a field's existing content at the current selection, the way a
 * keyboard would: the selection is replaced, and spaces are added so words don't get glued.
 */
object TextMerger {

    /**
     * @param existing the node's current text (may be the placeholder, see [isShowingHint])
     * @param isShowingHint `AccessibilityNodeInfo.isShowingHintText`: the text is a placeholder
     * @param hint the node's hint, used to detect placeholders apps don't flag
     * @param selectionStart `textSelectionStart`, -1 when unknown
     * @param selectionEnd `textSelectionEnd`, -1 when unknown
     * @param insertion the dictated text
     * @param trailingSpace desktop's `append_trailing_space`: always end with a space
     */
    fun merge(
        existing: String?,
        isShowingHint: Boolean,
        hint: String?,
        selectionStart: Int,
        selectionEnd: Int,
        insertion: String,
        trailingSpace: Boolean = false,
    ): MergeResult {
        val current = when {
            existing == null -> ""
            isShowingHint -> ""
            // Some WebView/Compose fields expose the placeholder as text without the flag.
            !hint.isNullOrEmpty() && existing == hint && selectionStart <= 0 && selectionEnd <= 0 -> ""
            else -> existing
        }
        val toInsert = insertion.trim()
        if (toInsert.isEmpty()) {
            val cursor = if (selectionEnd in 0..current.length) selectionEnd else current.length
            return MergeResult(current, cursor, "")
        }

        var start = selectionStart
        var end = selectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            // Unknown or stale selection: append, like typing at the end.
            start = current.length
            end = current.length
        }
        if (start > end) {
            val tmp = start
            start = end
            end = tmp
        }
        // Never split a surrogate pair (emoji): move the boundary after it.
        start = avoidSurrogateSplit(current, start)
        end = maxOf(start, avoidSurrogateSplit(current, end))

        val before = current.substring(0, start)
        val after = current.substring(end)

        val leading = if (needsSpaceBefore(before, toInsert)) " " else ""
        val trailing = if (needsSpaceAfter(toInsert, after) || (trailingSpace && after.firstOrNull()?.isWhitespace() != true)) " " else ""
        val inserted = leading + toInsert + trailing

        return MergeResult(before + inserted + after, before.length + inserted.length, inserted)
    }

    private fun avoidSurrogateSplit(text: String, index: Int): Int =
        if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) {
            index + 1
        } else {
            index
        }

    private fun needsSpaceBefore(before: String, insertion: String): Boolean {
        if (before.isEmpty()) return false
        val prev = before.last()
        if (prev.isWhitespace()) return false
        val first = insertion.first()
        // "word" + ", next" -> no space before punctuation.
        if (first in CLOSING_PUNCTUATION) return false
        if (prev in OPENING_PUNCTUATION) return false
        return true
    }

    private fun needsSpaceAfter(insertion: String, after: String): Boolean {
        if (after.isEmpty()) return false
        val next = after.first()
        if (next.isWhitespace()) return false
        if (next in CLOSING_PUNCTUATION) return false
        return insertion.last().isLetterOrDigit() || insertion.last() in CLOSING_PUNCTUATION
    }

    private val CLOSING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '…', '%', '»')
    private val OPENING_PUNCTUATION = setOf('(', '[', '{', '«', '"', '\'', '/', '@', '#')
}

/** Spacing for text committed through an InputConnection (voice keyboard). */
object ImeText {
    /**
     * @param before text just before the cursor (`getTextBeforeCursor`), selection excluded
     * @param after text just after the cursor/selection (`getTextAfterCursor`)
     * @return what to commit so words don't get glued, as [TextMerger] would insert it
     */
    fun compose(before: CharSequence?, after: CharSequence?, insertion: String, trailingSpace: Boolean): String {
        val b = before?.toString() ?: ""
        val a = after?.toString() ?: ""
        return TextMerger.merge(b + a, false, null, b.length, b.length, insertion, trailingSpace).inserted
    }
}
