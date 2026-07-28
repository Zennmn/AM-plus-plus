package dev.amenhancer.module.hook

import kotlin.math.roundToInt

internal object TabletLyricAnchorPolicy {
    fun highlightOffset(currentOffset: Int, containerHeight: Int): Int {
        val stockAnchor = (containerHeight * STOCK_ANCHOR_FRACTION).roundToInt()
        val targetAnchor = (containerHeight * TARGET_ANCHOR_FRACTION).roundToInt()
        return currentOffset + targetAnchor - stockAnchor
    }

    private const val STOCK_ANCHOR_FRACTION = 0.08f
    private const val TARGET_ANCHOR_FRACTION = 0.33f
}
