package dev.amenhancer.module.hook.tabletmedia

import kotlin.math.roundToInt

/** Arrange actual touch rectangles inside the existing 25% controls, never grow that container. */
internal object TabletComponentGeometry {
    data class Rows(val transportTop: Int, val transportHeight: Int, val volumeTop: Int?,
        val volumeHeight: Int, val footerTop: Int, val footerHeight: Int)

    fun rows(height: Int, progressHeight: Int, density: Float): Rows? {
        if (height <= 0 || progressHeight < 0 || !density.isFinite() || density <= 0f) return null
        fun dp(value: Int) = (value * density).roundToInt()
        val footer = dp(44); val bottom = dp(8); val volume = dp(44)
        val footerTop = height - bottom - footer
        // Prefer the reference's compact row. Retain a full touch target on shorter windows.
        for (transport in listOf(dp(52), dp(44))) {
            val gap = dp(4)
            val spare = footerTop - progressHeight - transport - volume - gap * 3
            if (spare >= 0) {
                val top = progressHeight + gap + spare / 3
                return Rows(top, transport, top + transport + gap + spare / 3, volume, footerTop, footer)
            }
        }
        val gap = dp(4); val transport = dp(44)
        if (footerTop - progressHeight < transport + gap * 2) return null
        val top = progressHeight + (footerTop - progressHeight - transport) / 2
        return Rows(top, transport, null, volume, footerTop, footer)
    }

    data class Modes(val shuffleLeft: Int, val repeatLeft: Int)
    fun modes(left: Int, width: Int, previousLeft: Int, nextRight: Int, size: Int, gap: Int): Modes? {
        if (size <= 0 || width <= 0 || gap < 0 || previousLeft - left < size + gap ||
            left + width - nextRight < size + gap) return null
        return Modes(left, left + width - size)
    }

    fun centeredOffset(parentWidth: Int, left: Int, width: Int): Float? {
        if (parentWidth <= 0 || width <= 0 || left < 0 || width > parentWidth / 2 || left + width > parentWidth) return null
        return (parentWidth - width) / 2f - left
    }

    fun deviceLabelWidth(left: Int, columnRight: Int, blockers: List<Int>, gap: Int, maximum: Int): Int =
        (minOf(columnRight, blockers.minOrNull()?.minus(gap) ?: columnRight) - left).coerceIn(0, maximum.coerceAtLeast(0))
}
