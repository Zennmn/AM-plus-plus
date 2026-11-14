package dev.amenhancer.module.hook

import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Adjust the real child position after native layout, before the row uses it for sweeps. */
internal fun alignLyricPronunciationBody(group: LinearLayout) {
    if (group.childCount != 2) return
    val top = group.getChildAt(0)
    val bottom = group.getChildAt(1)
    if (top.visibility != View.VISIBLE || bottom.visibility != View.VISIBLE ||
        top.width <= 0 || bottom.width <= 0 || top.width == bottom.width
    ) return

    val wide = if (top.width > bottom.width) top else bottom
    val narrow = if (wide === top) bottom else top
    val widePieces = wide.textPieces() ?: return
    val narrowPieces = narrow.textPieces() ?: return
    if (!widePieces.hasDecoratedEdge() && !narrowPieces.hasDecoratedEdge()) return

    val path = Path()
    val bounds = RectF()
    val wideCenter = widePieces.bodyCenter(wide, path, bounds) ?: return
    val narrowCenter = narrowPieces.bodyCenter(narrow, path, bounds) ?: return
    val left = (wide.left + wideCenter - narrowCenter).roundToInt()
        .coerceIn(wide.left, wide.right - narrow.width)
    narrow.offsetLeftAndRight(left - narrow.left)
}

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
private fun List<TextView>.bodyCenter(layer: View, path: Path, bounds: RectF): Float? {
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
    return if (left < right) (left + right) / 2f else null
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
