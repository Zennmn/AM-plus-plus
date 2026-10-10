package dev.amenhancer.module.hook.tabletmedia

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabletComponentGeometryTest {
    @Test fun volumeAndOutputHaveSeparate44dpRowsAtTabletDensities() {
        listOf(1f, 2.25f, 2.625f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = (dp * density).roundToInt()
            val volume = checkNotNull(TabletComponentGeometry.volumeAboveOutput(px(48), px(552), px(744), px(656), density))
            assertEquals(px(44), volume.height)
            assertEquals(px(744), volume.top + volume.height + px(8))
            assertTrue(volume.top >= px(656) + px(4))
            assertEquals(px(48), volume.left)
            assertEquals(px(552), volume.left + volume.width)
        }
    }
    @Test fun insufficientExtraSpaceStillKeepsVolumeInTheOriginalNativeFooter() {
        assertNotNull(TabletComponentGeometry.volumeAboveOutput(48, 552, 186, 130, 1f))
        assertNull(TabletComponentGeometry.volumeAboveOutput(48, 552, 186, 131, 1f))
        val base = checkNotNull(TabletComponentGeometry.volumeAtNativeFooter(48, 552, 156, 1f))
        assertEquals(134, base.top); assertEquals(44, base.height); assertEquals(504, base.width)
    }
    @Test fun differentNativeTransportBoundsNeverOverlapTheFooterSlider() {
        for (outputTop in 60..240) for (contentBottom in listOf(40, 80, 120, 160)) {
            val volume = TabletComponentGeometry.volumeAboveOutput(48, 552, outputTop, contentBottom, 1f)
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
    @Test fun nativeFooterVolumeFollowsTheCenteredColumnWithoutReservingCornerOutput() {
        val volume = checkNotNull(TabletComponentGeometry.volumeAtNativeFooter(48, 552, 156, 1f))
        assertEquals(48, volume.left); assertEquals(552, volume.left + volume.width)
        val centered = checkNotNull(TabletComponentGeometry.volumeAtNativeFooter(348, 852, 156, 1f))
        assertEquals(348, centered.left)
    }
}
