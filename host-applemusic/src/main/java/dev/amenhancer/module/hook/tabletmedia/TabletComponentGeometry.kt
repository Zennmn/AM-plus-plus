package dev.amenhancer.module.hook.tabletmedia

import kotlin.math.roundToInt

/** Fit additions into the native footer, leaving the 25% controls' own layout untouched. */
internal object TabletComponentGeometry {
    data class Slot(val left: Int, val top: Int, val width: Int, val height: Int)
    fun cornerOutput(leftInset: Int, progressLeft: Int, top: Int, density: Float): Slot? {
        if (!density.isFinite() || density <= 0f || top < 0) return null
        fun dp(value: Int) = (value * density).roundToInt()
        val size = dp(44)
        val left = minOf(leftInset + dp(12), progressLeft - dp(8) - size)
        return if (left >= leftInset) Slot(left, top, size, size) else null
    }
    fun bottomVolume(left: Int, right: Int, center: Int, contentBottom: Int,
        blockers: List<IntRange>, density: Float): Slot? {
        if (right <= left || !density.isFinite() || density <= 0f) return null
        fun dp(value: Int) = (value * density).roundToInt()
        val height = dp(44); val top = center - height / 2
        if (top < 0 || top < contentBottom + dp(4)) return null
        var spaces = listOf(left until right)
        blockers.filterNot { it.isEmpty() }.forEach { block ->
            val start = block.first - dp(8); val end = block.last + 1 + dp(8)
            spaces = spaces.flatMap { span ->
                if (end <= span.first || start > span.last) listOf(span)
                else listOf(span.first until minOf(start, span.last + 1),
                    maxOf(end, span.first) until span.last + 1).filterNot { it.isEmpty() }
            }
        }
        // Icons consume 80dp; leave at least a 48dp track for a usable volume gesture.
        val span = spaces.maxByOrNull { it.last + 1 - it.first } ?: return null
        val width = span.last + 1 - span.first
        return if (width >= dp(128)) Slot(span.first, top, width, height) else null
    }

    fun centeredOffset(parentWidth: Int, left: Int, width: Int): Float? {
        if (parentWidth <= 0 || width <= 0 || left < 0 || width > parentWidth / 2 || left + width > parentWidth) return null
        return (parentWidth - width) / 2f - left
    }

}
