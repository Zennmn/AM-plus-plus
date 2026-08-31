package dev.amenhancer.module.hook

internal data class TabletArtworkLayout(
    val sizePx: Float,
    val edgeGapPx: Float,
)

/**
 * Center the unchanged native cover inside the vertical interval between the
 * player top and Apple's metadata barrier, leaving equal edge gaps whenever
 * the native cover fits in that interval.
 */
internal object TabletArtworkLayoutPolicy {
    fun resolve(
        availableHeightPx: Float,
        nativeSizePx: Float,
    ): TabletArtworkLayout? {
        if (availableHeightPx <= 0f || nativeSizePx <= 0f) return null
        val edgeGapPx = ((availableHeightPx - nativeSizePx) / 2f).coerceAtLeast(0f)
        return TabletArtworkLayout(
            sizePx = nativeSizePx,
            edgeGapPx = edgeGapPx,
        )
    }
}
