package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletLyricVisualPolicyTest {
    @Test
    fun `center rows keep their existing focus blur and full opacity`() {
        val edge = TabletLyricVisualPolicy.edgeBlurRadius(
            rowCenterPx = 500f,
            viewportHeightPx = 1_000f,
        )

        assertEquals(0f, edge, TOLERANCE)
        assertEquals(
            12f,
            TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = 12f,
                edgeBlurRadius = edge,
            ),
            TOLERANCE,
        )
    }

    @Test
    fun `top ten percent fades and blurs continuously into the viewport edge`() {
        assertEquals(5f, edgeAt(rowCenterPx = 0f), TOLERANCE)
        assertEquals(2.5f, edgeAt(rowCenterPx = 50f), TOLERANCE)
        assertEquals(0f, edgeAt(rowCenterPx = 100f), TOLERANCE)
    }

    @Test
    fun `bottom edge mirrors the top edge`() {
        assertEquals(5f, edgeAt(rowCenterPx = 1_000f), TOLERANCE)
        assertEquals(2.5f, edgeAt(rowCenterPx = 950f), TOLERANCE)
        assertEquals(0f, edgeAt(rowCenterPx = 900f), TOLERANCE)
    }

    @Test
    fun `edge blur never weakens the bidirectional focus blur`() {
        assertEquals(
            16f,
            TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = 16f,
                edgeBlurRadius = edgeAt(rowCenterPx = 50f),
            ),
            TOLERANCE,
        )
    }

    @Test
    fun `highlighted rows stay clear even inside the tablet edge field`() {
        assertEquals(
            0f,
            TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = 0f,
                edgeBlurRadius = edgeAt(rowCenterPx = 0f),
                isHighlighted = true,
            ),
            TOLERANCE,
        )
    }

    @Test
    fun `tablet lyric size follows five percent of viewport height like the reference player`() {
        assertEquals(
            45.714287f,
            TabletLyricVisualPolicy.textSizeSp(
                viewportWidthPx = 3_392f,
                viewportHeightPx = 2_400f,
                scaledDensity = 2.625f,
            ),
            TOLERANCE,
        )
        assertEquals(4, TabletLyricVisualPolicy.ITEM_SPACING_EXTRA_DP)
        assertEquals(0.10f, TabletLyricVisualPolicy.EDGE_TRANSITION_FRACTION, TOLERANCE)
    }

    private fun edgeAt(rowCenterPx: Float): Float =
        TabletLyricVisualPolicy.edgeBlurRadius(
            rowCenterPx = rowCenterPx,
            viewportHeightPx = 1_000f,
        )

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
