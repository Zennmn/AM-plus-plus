package dev.amenhancer.module.hook

import android.text.Layout
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Compensate for edge spacing after span centering, before the row reads the child positions. */
internal fun compensateLyricPronunciationEdges(group: LinearLayout) {
    if (group.childCount != 2) return
    val top = group.getChildAt(0)
    val bottom = group.getChildAt(1)
    if (top.visibility != View.VISIBLE || bottom.visibility != View.VISIBLE ||
        top.width <= 0 || bottom.width <= 0 || top.width == bottom.width
    ) return

    val anchor = if (top.width > bottom.width) top else bottom
    val moving = if (anchor === top) bottom else top
    val minLeft = group.paddingLeft
    val maxLeft = group.width - group.paddingRight - moving.width
    if (maxLeft <= minLeft) return

    val anchorBias = anchor.edgeBias() ?: return
    val movingBias = moving.edgeBias() ?: return
    val correction = anchorBias - movingBias
    if (correction == 0f) return

    // Use the original LinearLayout center, so a second layout never adds to the previous offset.
    val centeredLeft = minLeft + (maxLeft - minLeft) / 2
    val left = (centeredLeft + correction).roundToInt().coerceIn(minLeft, maxLeft)
    moving.offsetLeftAndRight(left - moving.left)
}

private fun View.edgeBias(): Float? {
    val pieces = when {
        this is TextView -> listOf(this)
        // Native split glyphs share a horizontal child and move together.
        this is LinearLayout && orientation == LinearLayout.HORIZONTAL ->
            List(childCount) { getChildAt(it) as? TextView ?: return null }
        else -> return null
    }
    val leading = pieces.edgeAdvance(leading = true) ?: return null
    val trailing = pieces.edgeAdvance(leading = false) ?: return null
    val rtl = if (this is TextView) layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        else layoutDirection == View.LAYOUT_DIRECTION_RTL
    // Leading occupies the physical left in LTR; a larger left edge shifts the body right.
    return (if (rtl) trailing - leading else leading - trailing) / 2f
}

private fun List<TextView>.edgeAdvance(leading: Boolean): Float? {
    var width = 0f
    for (index in if (leading) indices else indices.reversed()) {
        val piece = this[index]
        val layout = piece.layout ?: return null
        if (piece.visibility != View.VISIBLE || layout.lineCount != 1 || layout.getEllipsisCount(0) != 0) return null
        val text = layout.text
        val start = if (leading) 0 else text.bodyEnd()
        val end = if (leading) text.bodyStart() else text.length
        if (start < end) {
            val from = layout.getPrimaryHorizontal(start)
            val to = layout.getPrimaryHorizontal(end)
            // Two different caret positions indicate a bidi boundary, not a simple edge.
            if (from != layout.getSecondaryHorizontal(start) || to != layout.getSecondaryHorizontal(end)) return null
            val advance = (to - from) * layout.getParagraphDirection(0)
            if (!advance.isFinite() || advance < 0f) return null
            width += advance
        }
        if (end - start < text.length) return width
    }
    return null // A symbol-only track has no body to align.
}

private fun CharSequence.bodyStart(): Int {
    var start = 0
    while (start < length) {
        val codePoint = Character.codePointAt(this, start)
        if (isLyricBodyCharacter(codePoint)) break
        start += Character.charCount(codePoint)
    }
    return start
}

private fun CharSequence.bodyEnd(): Int {
    var end = length
    while (end > 0) {
        val codePoint = Character.codePointBefore(this, end)
        if (isLyricBodyCharacter(codePoint)) {
            // Keep combining marks attached to the last letter, including decomposed romanization.
            while (end < length) {
                val mark = Character.codePointAt(this, end)
                if (Character.getType(mark) !in
                    Character.NON_SPACING_MARK.toInt()..Character.COMBINING_SPACING_MARK.toInt()
                ) break
                end += Character.charCount(mark)
            }
            return end
        }
        end -= Character.charCount(codePoint)
    }
    return 0
}

private fun isLyricBodyCharacter(codePoint: Int): Boolean =
    Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint)) {
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }
