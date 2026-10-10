package dev.amenhancer.module.hook.tabletmedia

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabletComponentGeometryTest {
    @Test fun volumeIncludesItsIconsWithinTheProgressBoundsAndOutputStaysOutside() {
        listOf(1f, 2.25f, 2.625f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = (dp * density).roundToInt()
            val output = checkNotNull(TabletComponentGeometry.cornerOutput(0, px(64), px(734), density))
            val volume = checkNotNull(TabletComponentGeometry.bottomVolume(px(64), px(568), px(756), px(698),
                listOf(output.left until output.left + output.width), density))
            assertEquals(px(44), volume.height)
            assertTrue(kotlin.math.abs(volume.top + volume.height / 2 - px(756)) <= 1)
            assertTrue(volume.top >= px(698) + px(4))
            assertTrue(output.left + output.width + px(8) <= px(64))
            assertEquals(px(64), volume.left)
            assertEquals(px(568), volume.left + volume.width)
        }
    }
    @Test fun cornerOutputUsesAvailableGutterWithoutCrossingTheProgressEdge() {
        val wide = checkNotNull(TabletComponentGeometry.cornerOutput(0, 64, 734, 1f))
        assertEquals(12, wide.left); assertEquals(44, wide.width)
        val narrow = checkNotNull(TabletComponentGeometry.cornerOutput(20, 80, 734, 1f))
        assertEquals(28, narrow.left); assertEquals(72, narrow.left + narrow.width)
        assertNull(TabletComponentGeometry.cornerOutput(20, 71, 734, 1f))
    }
    @Test fun insufficientFooterSpaceHidesVolumeWithoutShrinkingTheNativeTransport() {
        assertNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 130, emptyList(), 1f))
        assertNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 131, emptyList(), 1f))
        assertNull(TabletComponentGeometry.bottomVolume(48, 200, 156, 100, listOf(58 until 102), 1f))
    }
    @Test fun differentNativeTransportBoundsNeverOverlapTheFooterSlider() {
        for (center in 60..240) for (contentBottom in listOf(40, 80, 120, 160)) {
            val volume = TabletComponentGeometry.bottomVolume(48, 552, center, contentBottom, listOf(58 until 102), 1f)
                ?: continue
            assertTrue(volume.top >= contentBottom + 4)
            assertEquals(center, volume.top + volume.height / 2)
            assertTrue(volume.left >= 110); assertTrue(volume.left + volume.width <= 552)
        }
    }
    @Test fun centeredSongKeepsItsWidthAndCanRestoreItsOriginalLeft() {
        val offset = checkNotNull(TabletComponentGeometry.centeredOffset(1200, 48, 504))
        assertEquals(300f, offset, 0f)
        assertEquals((1200 - 504) / 2f, 48 + offset, 0f)
        assertNull(TabletComponentGeometry.centeredOffset(600, 0, 600))
    }
    @Test fun sharePlayKeepsItsNativeSlotAndOutputOutsideTheVolumeGesture() {
        val volume = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 100,
            listOf(58 until 102, 260 until 340), 1f))
        assertEquals(348, volume.left); assertEquals(552, volume.left + volume.width)
        val centered = checkNotNull(TabletComponentGeometry.bottomVolume(348, 852, 156, 100, listOf(58 until 102), 1f))
        assertEquals(348, centered.left)
    }
}
