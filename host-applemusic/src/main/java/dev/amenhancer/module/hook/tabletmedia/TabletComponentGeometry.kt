package dev.amenhancer.module.hook.tabletmedia

import kotlin.math.roundToInt

/** Fit additions into the native footer, leaving the 25% controls' own layout untouched. */
internal object TabletComponentGeometry {
    data class Slot(val left: Int, val top: Int, val width: Int, val height: Int)
    fun cornerOutput(leftInset: Int, progressLeft: Int, top: Int, density: Float, separateRow: Boolean = false): Slot? {
        if (!density.isFinite() || density <= 0f || top < 0) return null
        fun dp(value: Int) = (value * density).roundToInt()
        val size = dp(44)
        val left = if (separateRow) leftInset + dp(12) else minOf(leftInset + dp(12), progressLeft - dp(8) - size)
        return if (left >= leftInset) Slot(left, top, size, size) else null
    }
    fun bottomVolume(left: Int, right: Int, center: Int, contentBottom: Int,
        bottom: Int, density: Float): Slot? {
        if (right <= left || !density.isFinite() || density <= 0f) return null
        fun dp(value: Int) = (value * density).roundToInt()
        // Keep the whole progress width; both speaker icons occupy 80dp with their spacing.
        val width = right - left
        if (width <= dp(80)) return null
        val minTop = contentBottom.coerceAtLeast(0)
        val height = minOf(dp(44), bottom - minTop)
        // Shrink only empty touch padding, keeping the 24dp glyphs at their normal size.
        if (height < dp(24)) return null
        val top = (center - height / 2).coerceIn(minTop, bottom - height)
        return Slot(left, top, width, height)
    }

    fun centeredOffset(parentWidth: Int, left: Int, width: Int): Float? {
        if (parentWidth <= 0 || width <= 0 || left < 0 || width > parentWidth / 2 || left + width > parentWidth) return null
        return (parentWidth - width) / 2f - left
    }

}
