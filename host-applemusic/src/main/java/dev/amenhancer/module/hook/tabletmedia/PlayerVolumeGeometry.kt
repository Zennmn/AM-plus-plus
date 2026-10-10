package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).

import kotlin.math.roundToInt

/** Reserve a full touch target between the native transport and bottom action row. */
internal object PlayerVolumeGeometry {
    fun transportShift(transportTop: Int, transportBottom: Int, progressBottom: Int,
        actionsTop: Int, height: Int, margin: Int, preferredShift: Int = 0): Int? {
        val required = (height + margin * 2 - (actionsTop - transportBottom)).coerceAtLeast(0)
        val available = transportTop - progressBottom - margin
        if (available < required) return null
        return -maxOf(required, preferredShift.coerceIn(0, available))
    }
    // Reserve native controls plus the new touch target, rather than depending on the
    // screen's DPI or the native percentage height. One extra gap covers pixel rounding.
    fun controlsHeight(progress: Int, transport: Int, actions: Int, bottom: Int, height: Int, margin: Int) =
        progress.coerceAtLeast(0) + transport.coerceAtLeast(0) + actions.coerceAtLeast(0) +
            bottom.coerceAtLeast(0) + height.coerceAtLeast(0) + margin.coerceAtLeast(0) * 4

    fun column(left: Int, width: Int, rootWidth: Int, padding: Int): Pair<Int, Int>? {
        val start = maxOf(0, left) + padding
        val end = minOf(rootWidth, left + width) - padding
        return if (end > start) start to end - start else null
    }
    fun top(transportBottom: Int, actionsTop: Int, height: Int, margin: Int): Int? {
        val start = transportBottom + margin
        val end = actionsTop - margin
        if (height <= 0 || end - start < height) return null
        return start + (end - start - height) / 2
    }
    fun level(fraction: Float, minimum: Int, maximum: Int): Int {
        if (maximum <= minimum) return minimum
        val value = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        return (minimum + value * (maximum - minimum)).roundToInt().coerceIn(minimum, maximum)
    }
    fun fraction(level: Int, minimum: Int, maximum: Int): Float =
        if (maximum <= minimum) 0f else ((level - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f)
}
