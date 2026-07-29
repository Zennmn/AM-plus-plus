package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class BidirectionalBlurPolicyTest {
    @Test
    fun `ordinary lyric gap retains the last non-empty highlight until replacement`() {
        val active = BidirectionalBlurPolicy.resolveHighlights(emptySet(), setOf(46))
        val gap = BidirectionalBlurPolicy.resolveHighlights(active, emptySet())
        val next = BidirectionalBlurPolicy.resolveHighlights(gap, setOf(47))

        assertEquals(setOf(46), gap)
        assertEquals(setOf(47), next)
    }

    @Test
    fun `intro without an active highlight focuses the earliest visible lyric row`() {
        assertEquals(
            setOf(0),
            BidirectionalBlurPolicy.resolveDisplayHighlights(
                active = emptySet(),
                visiblePositions = listOf(-1, 2, 0, 1),
            ),
        )
        assertEquals(
            setOf(8),
            BidirectionalBlurPolicy.resolveDisplayHighlights(
                active = setOf(8),
                visiblePositions = listOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun `highlighted row is clear and surrounding rows follow open source directional blur`() {
        val highlighted = setOf(10)

        assertRadius(22f, position = 5, highlighted)
        assertRadius(22f, position = 6, highlighted)
        assertRadius(22f, position = 7, highlighted)
        assertRadius(17f, position = 8, highlighted)
        assertRadius(13f, position = 9, highlighted)
        assertRadius(0f, position = 10, highlighted)
        assertRadius(8f, position = 11, highlighted)
        assertRadius(13f, position = 12, highlighted)
        assertRadius(17f, position = 13, highlighted)
        assertRadius(22f, position = 14, highlighted)
        assertRadius(22f, position = 15, highlighted)
    }

    @Test
    fun `nearest highlighted row determines radius when several rows are highlighted`() {
        val highlighted = setOf(3, 9)

        assertRadius(13f, position = 2, highlighted)
        assertRadius(0f, position = 3, highlighted)
        assertRadius(13f, position = 5, highlighted)
        assertRadius(17f, position = 7, highlighted)
        assertRadius(0f, position = 9, highlighted)
        assertRadius(8f, position = 10, highlighted)
    }

    @Test
    fun `rows use maximum blur when there is no highlight`() {
        assertRadius(22f, position = -1, highlighted = emptySet())
        assertRadius(22f, position = 0, highlighted = emptySet())
        assertRadius(22f, position = 42, highlighted = emptySet())
    }

    @Test
    fun `radius is quantized to nearest whole pixel`() {
        assertEquals(0, BidirectionalBlurPolicy.quantize(0f))
        assertEquals(0, BidirectionalBlurPolicy.quantize(0.49f))
        assertEquals(1, BidirectionalBlurPolicy.quantize(0.5f))
        assertEquals(12, BidirectionalBlurPolicy.quantize(12.49f))
        assertEquals(13, BidirectionalBlurPolicy.quantize(12.5f))
        assertEquals(20, BidirectionalBlurPolicy.quantize(20f))
        assertEquals(22, BidirectionalBlurPolicy.quantize(25f))
    }

    @Test
    fun `interpolation is linear over the default 300 milliseconds`() {
        assertInterpolated(4f, start = 4f, target = 16f, elapsedMs = 0L)
        assertInterpolated(7f, start = 4f, target = 16f, elapsedMs = 75L)
        assertInterpolated(10f, start = 4f, target = 16f, elapsedMs = 150L)
        assertInterpolated(16f, start = 4f, target = 16f, elapsedMs = 300L)
    }

    @Test
    fun `interpolation clamps elapsed time at both ends`() {
        assertInterpolated(4f, start = 4f, target = 16f, elapsedMs = -50L)
        assertInterpolated(16f, start = 4f, target = 16f, elapsedMs = 450L)
        assertInterpolated(14f, start = 20f, target = 8f, elapsedMs = 150L)
        assertInterpolated(
            expected = 8f,
            start = 20f,
            target = 8f,
            elapsedMs = 150L,
            durationMs = 150L,
        )
    }

    private fun assertRadius(expected: Float, position: Int, highlighted: Set<Int>) {
        assertEquals(expected, BidirectionalBlurPolicy.targetRadius(position, highlighted), FLOAT_TOLERANCE)
    }

    private fun assertInterpolated(
        expected: Float,
        start: Float,
        target: Float,
        elapsedMs: Long,
        durationMs: Long = 300L,
    ) {
        assertEquals(
            expected,
            BidirectionalBlurPolicy.interpolate(start, target, elapsedMs, durationMs),
            FLOAT_TOLERANCE,
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
