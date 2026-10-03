package computer.handy.android.core

/** Screen rectangle in pixels (mirror of android.graphics.Rect, kept pure for tests). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(other: Box): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    fun offsetY(dy: Int) = copy(top = top + dy, bottom = bottom + dy)

    companion object {
        fun square(left: Int, top: Int, size: Int) = Box(left, top, left + size, top + size)
    }
}

/**
 * Computes where the floating button goes. Rules, in order:
 *  1. never over the keyboard (TYPE_INPUT_METHOD window bounds);
 *  2. never over the field itself, so typed text is never hidden;
 *  3. stay on screen;
 *  4. prefer the field's bottom-right corner, outside its bounds.
 *
 * The user's vertical drag offset is applied to each candidate before checking the rules.
 */
object ButtonPlacer {

    fun place(
        field: Box,
        screen: Box,
        keyboard: Box?,
        buttonSize: Int,
        gap: Int,
        userOffsetY: Int = 0,
    ): Box {
        val kb = keyboard?.takeUnless { it.isEmpty }
        val candidates = listOf(
            // Right of the field, bottom-aligned.
            Box.square(field.right + gap, field.bottom - buttonSize, buttonSize),
            // Below the field, right-aligned.
            Box.square(field.right - buttonSize, field.bottom + gap, buttonSize),
            // Above the field, right-aligned (typical for chat inputs docked on the keyboard).
            Box.square(field.right - buttonSize, field.top - gap - buttonSize, buttonSize),
            // Left of the field, bottom-aligned.
            Box.square(field.left - gap - buttonSize, field.bottom - buttonSize, buttonSize),
        ).map { it.offsetY(userOffsetY) }

        candidates.firstOrNull { isValid(it, field, screen, kb) }?.let { return it }

        // Nothing fits as-is (e.g. a full-screen editor): clamp the preferred spot to the
        // screen area above the keyboard. Overlapping the field's edge is the lesser evil.
        val usableBottom = minOf(screen.bottom, kb?.top ?: screen.bottom) - gap
        val x = (field.right - buttonSize - gap).coerceIn(screen.left, screen.right - buttonSize)
        val y = (field.bottom - buttonSize + userOffsetY)
            .coerceAtMost(usableBottom - buttonSize)
            .coerceAtLeast(screen.top)
        return Box.square(x, y, buttonSize)
    }

    private fun isValid(candidate: Box, field: Box, screen: Box, keyboard: Box?): Boolean {
        if (!screen.contains(candidate)) return false
        if (keyboard != null && candidate.intersects(keyboard)) return false
        return !candidate.intersects(field)
    }
}
