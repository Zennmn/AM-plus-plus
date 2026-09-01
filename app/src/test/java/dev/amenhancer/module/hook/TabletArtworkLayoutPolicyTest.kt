package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabletArtworkLayoutPolicyTest {
    @Test
    fun centersNativeCoverWithEqualTopAndTitleGaps() {
        val layout = TabletArtworkLayoutPolicy.resolve(
            availableHeightPx = 1_200f,
            nativeSizePx = 600f,
        )

        requireNotNull(layout)
        assertEquals(600f, layout.sizePx, 0.001f)
        assertEquals(300f, layout.edgeGapPx, 0.001f)
    }

    @Test
    fun keepsNativeSizeWhenAvailableIntervalIsTight() {
        val layout = TabletArtworkLayoutPolicy.resolve(
            availableHeightPx = 800f,
            nativeSizePx = 800f,
        )

        requireNotNull(layout)
        assertEquals(800f, layout.sizePx, 0.001f)
        assertEquals(0f, layout.edgeGapPx, 0.001f)
    }

    @Test
    fun doesNotShrinkNativeCoverWhenItExceedsAvailableInterval() {
        val layout = TabletArtworkLayoutPolicy.resolve(
            availableHeightPx = 600f,
            nativeSizePx = 800f,
        )

        requireNotNull(layout)
        assertEquals(800f, layout.sizePx, 0.001f)
        assertEquals(0f, layout.edgeGapPx, 0.001f)
    }

    @Test
    fun rejectsNonPositiveViewport() {
        assertNull(
            TabletArtworkLayoutPolicy.resolve(
                availableHeightPx = 0f,
                nativeSizePx = 400f,
            ),
        )
    }
}
