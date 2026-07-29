package dev.amenhancer.module.hook

import kotlin.math.abs
import kotlin.math.roundToInt

internal object BidirectionalBlurPolicy {
    private const val BLUR_MAX = 22f
    private val PAST_RADII_BY_DISTANCE = floatArrayOf(0f, 13f, 17f, BLUR_MAX)
    private val FUTURE_RADII_BY_DISTANCE = floatArrayOf(0f, 8f, 13f, 17f, BLUR_MAX)
    const val TRANSITION_DURATION_MS = 300L

    fun resolveHighlights(current: Set<Int>, incoming: Set<Int>): Set<Int> =
        (incoming.takeIf { it.isNotEmpty() } ?: current).toSet()

    fun resolveDisplayHighlights(active: Set<Int>, visiblePositions: List<Int>): Set<Int> {
        if (active.isNotEmpty()) return active.toSet()
        return visiblePositions
            .asSequence()
            .filter { position -> position >= 0 }
            .minOrNull()
            ?.let(::setOf)
            .orEmpty()
    }

    fun targetRadius(position: Int, highlighted: Set<Int>): Float {
        if (highlighted.isEmpty()) return BLUR_MAX
        if (position in highlighted) return 0f
        return highlighted.minOf { highlightedPosition ->
            val offset = position - highlightedPosition
            val radii = if (offset < 0) {
                PAST_RADII_BY_DISTANCE
            } else {
                FUTURE_RADII_BY_DISTANCE
            }
            radii.getOrElse(abs(offset)) { BLUR_MAX }
        }
    }

    fun quantize(radius: Float): Int = radius
        .coerceIn(0f, BLUR_MAX)
        .roundToInt()

    fun interpolate(
        start: Float,
        target: Float,
        elapsedMs: Long,
        durationMs: Long = TRANSITION_DURATION_MS,
    ): Float {
        if (durationMs <= 0L) return target
        val progress = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        return start + (target - start) * progress
    }
}
