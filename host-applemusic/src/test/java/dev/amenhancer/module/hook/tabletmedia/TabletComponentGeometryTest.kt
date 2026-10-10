package dev.amenhancer.module.hook.tabletmedia

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabletComponentGeometryTest {
    @Test fun volumeAndOutputHaveSeparate44dpRowsAtTabletDensities() {
        listOf(1f, 2.25f, 2.625f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = (dp * density).roundToInt()
            val volume = checkNotNull(TabletComponentGeometry.volumeAboveOutput(px(48), px(552), px(744), px(656),
                emptyList(), density))
            assertEquals(px(44), volume.height)
            assertEquals(px(744), volume.top + volume.height + px(8))
            assertTrue(volume.top >= px(656) + px(4))
            assertEquals(px(48), volume.left)
            assertEquals(px(552), volume.left + volume.width)
        }
    }
    @Test fun insufficientFooterSpaceHidesVolumeWithoutShrinkingTheNativeTransport() {
        assertNotNull(TabletComponentGeometry.volumeAboveOutput(48, 552, 186, 130, emptyList(), 1f))
        assertNull(TabletComponentGeometry.volumeAboveOutput(48, 552, 186, 131, emptyList(), 1f))
        assertNull(TabletComponentGeometry.volumeAboveOutput(48, 200, 186, 100, listOf(58 until 102), 1f))
    }
    @Test fun differentNativeTransportBoundsNeverOverlapTheFooterSlider() {
        for (outputTop in 60..240) for (contentBottom in listOf(40, 80, 120, 160)) {
            val volume = TabletComponentGeometry.volumeAboveOutput(48, 552, outputTop, contentBottom, emptyList(), 1f)
                ?: continue
            assertTrue(volume.top >= contentBottom + 4)
            assertEquals(outputTop, volume.top + volume.height + 8)
            assertEquals(48, volume.left); assertTrue(volume.left + volume.width <= 552)
        }
    }
    @Test fun centeredSongKeepsItsWidthAndCanRestoreItsOriginalLeft() {
        val offset = checkNotNull(TabletComponentGeometry.centeredOffset(1200, 48, 504))
        assertEquals(300f, offset, 0f)
        assertEquals((1200 - 504) / 2f, 48 + offset, 0f)
        assertNull(TabletComponentGeometry.centeredOffset(600, 0, 600))
    }
    @Test fun sharePlayKeepsItsNativeSlotAndOutputOutsideTheVolumeGesture() {
        val volume = checkNotNull(TabletComponentGeometry.volumeAboveOutput(48, 552, 186, 100,
            listOf(220 until 340), 1f))
        assertEquals(348, volume.left); assertEquals(552, volume.left + volume.width)
        val centered = checkNotNull(TabletComponentGeometry.volumeAboveOutput(348, 852, 186, 100, emptyList(), 1f))
        assertEquals(348, centered.left)
    }
}
