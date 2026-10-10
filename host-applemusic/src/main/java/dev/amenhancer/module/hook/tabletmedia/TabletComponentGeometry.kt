package dev.amenhancer.module.hook.tabletmedia

import kotlin.math.roundToInt

/** Volume owns the native footer; output is added only when a second row fits. */
internal object TabletComponentGeometry {
    const val CONTROLS_HEIGHT_PERCENT = .25f
    data class Slot(val left: Int, val top: Int, val width: Int, val height: Int)
    fun volumeAtNativeFooter(left: Int, right: Int, center: Int, density: Float): Slot? {
        if (!density.isFinite() || density <= 0f) return null
        return volumeSlot(left, right, center - (44 * density).roundToInt() / 2, density)
    }
    fun volumeAboveOutput(left: Int, right: Int, outputTop: Int, contentBottom: Int,
        density: Float): Slot? {
        if (!density.isFinite() || density <= 0f) return null
        fun dp(value: Int) = (value * density).roundToInt()
        val height = dp(44); val top = outputTop - dp(8) - height
        if (top < 0 || top < contentBottom + dp(4)) return null
        return volumeSlot(left, right, top, density)
    }
    private fun volumeSlot(left: Int, right: Int, top: Int, density: Float): Slot? {
        // Icons consume 80dp; leave at least a 48dp track for a usable volume gesture.
        val width = right - left
        return if (top >= 0 && width >= (128 * density).roundToInt())
            Slot(left, top, width, (44 * density).roundToInt()) else null
    }

    fun centeredOffset(parentWidth: Int, left: Int, width: Int): Float? {
        if (parentWidth <= 0 || width <= 0 || left < 0 || width > parentWidth / 2 || left + width > parentWidth) return null
        return (parentWidth - width) / 2f - left
    }

}
