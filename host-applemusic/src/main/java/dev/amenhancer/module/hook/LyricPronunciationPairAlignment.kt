package dev.amenhancer.module.hook

import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Adjust the real child position after native layout, before the row uses it for sweeps. */
internal fun alignLyricPronunciationBody(group: LinearLayout) {
    // Native split glyphs share a horizontal child; extra vertical children are not one track.
    if (group.childCount != 2) return
    val top = group.getChildAt(0)
    val bottom = group.getChildAt(1)
    if (top.visibility != View.VISIBLE || bottom.visibility != View.VISIBLE ||
        top.width <= 0 || bottom.width <= 0
    ) return

    val topPieces = top.textPieces() ?: return
    val bottomPieces = bottom.textPieces() ?: return
    if (!topPieces.hasDecoratedEdge() && !bottomPieces.hasDecoratedEdge()) return

    val path = Path()
    val bounds = RectF()
    val topBody = topPieces.bodyRange(top, path, bounds) ?: return
    val bottomBody = bottomPieces.bodyRange(bottom, path, bounds) ?: return
    val topBodyWidth = topBody.endInclusive - topBody.start
    val bottomBodyWidth = bottomBody.endInclusive - bottomBody.start
    if (topBodyWidth == bottomBodyWidth) return

    val topIsWider = topBodyWidth > bottomBodyWidth
    val anchor = if (topIsWider) top else bottom
    val moving = if (topIsWider) bottom else top
    val anchorBody = if (topIsWider) topBody else bottomBody
    val movingBody = if (topIsWider) bottomBody else topBody
    // Compare the virtual text spans above, but keep the entire moving view in the native slot.
    val minLeft = group.paddingLeft
    val maxLeft = group.width - group.paddingRight - moving.width
    if (maxLeft < minLeft) return
    val left = (anchor.left + anchorBody.center() - movingBody.center()).roundToInt()
        .coerceIn(minLeft, maxLeft)
    moving.offsetLeftAndRight(left - moving.left)
}

private fun ClosedFloatingPointRange<Float>.center(): Float = (start + endInclusive) / 2f

private fun View.textPieces(): List<TextView>? {
    val pieces = when {
        this is TextView -> listOf(this)
        this is LinearLayout && orientation == LinearLayout.HORIZONTAL ->
            List(childCount) { getChildAt(it) as? TextView ?: return null }
        else -> return null
    }
    return pieces.takeIf { it.isNotEmpty() && it.all { piece ->
        piece.visibility == View.VISIBLE && piece.layout?.let {
            it.lineCount == 1 && it.getEllipsisCount(0) == 0
        } == true
    } }
}

private fun List<TextView>.hasDecoratedEdge(): Boolean {
    val first = first().layout.text
    val last = last().layout.text
    return first.isEmpty() || last.isEmpty() || first.bodyStart() != 0 || last.bodyEnd() != last.length
}

@Suppress("DEPRECATION") // Path.computeBounds(RectF, boolean) is available on older Android versions.
private fun List<TextView>.bodyRange(
    layer: View,
    path: Path,
    bounds: RectF,
): ClosedFloatingPointRange<Float>? {
    val first = indexOfFirst { it.layout.text.let { text -> text.bodyStart() < text.length } }
    if (first < 0) return null
    val last = indexOfLast { it.layout.text.bodyEnd() != 0 }
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    // A native layer may be split into several views for glyph animation; trim only the layer's edges.
    for (index in first..last) {
        val piece = this[index]
        val layout = piece.layout
        val start = if (index == first) layout.text.bodyStart() else 0
        val end = if (index == last) layout.text.bodyEnd() else layout.text.length
        if (start >= end) continue
        layout.getSelectionPath(start, end, path)
        path.computeBounds(bounds, true)

        val contentLeft = piece.compoundPaddingLeft.toFloat()
        val contentRight = (piece.width - piece.compoundPaddingRight).toFloat()
        if (contentRight <= contentLeft) continue
        val pieceX = if (piece === layer) 0 else piece.left - layer.scrollX
        val from = (bounds.left + contentLeft - piece.scrollX).coerceIn(contentLeft, contentRight)
        val to = (bounds.right + contentLeft - piece.scrollX).coerceIn(contentLeft, contentRight)
        if (from < to) {
            left = minOf(left, pieceX + from)
            right = maxOf(right, pieceX + to)
        }
    }
    return if (left < right) left..right else null
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
