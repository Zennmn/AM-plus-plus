package dev.amenhancer.module.hook

import kotlin.math.max
import kotlin.math.min

/** Native equivalent of the reference player's viewport-relative lyric presentation. */
internal object TabletLyricVisualPolicy {
    const val ITEM_SPACING_EXTRA_DP = 4
    const val EDGE_TRANSITION_FRACTION = 0.10f
    private const val FONT_HEIGHT_FRACTION = 0.05f
    private const val FONT_WIDTH_FRACTION = 0.025f
    private const val MIN_FONT_PX = 12f

    fun edgeBlurRadius(
        rowCenterPx: Float,
        viewportHeightPx: Float,
    ): Float {
        if (viewportHeightPx <= 0f) return 0f
        val distanceToEdge = min(rowCenterPx, viewportHeightPx - rowCenterPx).coerceAtLeast(0f)
        val transitionPx = viewportHeightPx * EDGE_TRANSITION_FRACTION
        val visibility = (distanceToEdge / transitionPx).coerceIn(0f, 1f)
        return EDGE_MAX_BLUR_PX * (1f - visibility)
    }

    fun textSizeSp(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        scaledDensity: Float,
    ): Float {
        if (scaledDensity <= 0f) return MIN_FONT_PX
        val targetPx = max(
            max(viewportHeightPx * FONT_HEIGHT_FRACTION, viewportWidthPx * FONT_WIDTH_FRACTION),
            MIN_FONT_PX,
        )
        return targetPx / scaledDensity
    }

    fun mergeBlurRadius(
        focusBlurRadius: Float,
        edgeBlurRadius: Float,
        isHighlighted: Boolean = false,
    ): Float = if (isHighlighted) 0f else max(focusBlurRadius, edgeBlurRadius)
    private const val EDGE_MAX_BLUR_PX = 5f
}
