package dev.amenhancer.module.hook.tabletmedia

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabletComponentGeometryTest {
    @Test fun volumeAndOutputRowAlignWithinTheProgressBounds() {
        listOf(1f, 2.25f, 2.625f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = (dp * density).roundToInt()
            val volume = checkNotNull(TabletComponentGeometry.bottomVolume(px(64), px(568), px(700), px(642), px(800), density))
            val output = checkNotNull(TabletComponentGeometry.outputRow(px(64), px(568), px(734),
                volume.top + volume.height, px(800), px(220), density))
            assertEquals(px(44), volume.height)
            assertTrue(kotlin.math.abs(volume.top + volume.height / 2 - px(700)) <= 1)
            assertTrue(volume.top >= px(642))
            assertEquals(volume.left, output.left)
            assertTrue(output.top >= volume.top + volume.height + px(8))
            assertTrue(output.left + output.width <= px(568))
            assertEquals(px(64), volume.left)
            assertEquals(px(568), volume.left + volume.width)
        }
    }
    @Test fun outputRowKeepsPreferredHeightAndBoundsLongNamesWithoutCrossingVolume() {
        val normal = checkNotNull(TabletComponentGeometry.outputRow(64, 568, 734, 710, 800, 220, 1f))
        assertEquals(64, normal.left); assertEquals(734, normal.top); assertEquals(220, normal.width)
        val moved = checkNotNull(TabletComponentGeometry.outputRow(64, 568, 734, 742, 800, 220, 1f))
        assertEquals(750, moved.top); assertEquals(44, moved.height)
        val longName = checkNotNull(TabletComponentGeometry.outputRow(64, 160, 734, 710, 800, 220, 1f))
        assertEquals(96, longName.width)
        assertNull(TabletComponentGeometry.outputRow(64, 568, 734, 762, 800, 220, 1f))
    }
    @Test fun insufficientPreferredSpaceMovesVolumeDownAndShrinksOnlyEmptyTouchPadding() {
        val normal = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 130, 200, 1f))
        assertEquals(134, normal.top); assertEquals(44, normal.height)
        val moved = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 135, 200, 1f))
        assertEquals(135, moved.top); assertEquals(44, moved.height)
        val tight = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 166, 200, 1f))
        assertEquals(166, tight.top); assertEquals(34, tight.height)
        val glyphHeight = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 156, 176, 200, 1f))
        assertEquals(176, glyphHeight.top); assertEquals(24, glyphHeight.height)
    }
    @Test fun differentNativeTransportBoundsNeverOverlapTheFooterSlider() {
        for (center in 60..240) for (contentBottom in listOf(40, 80, 120, 160)) {
            val volume = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, center, contentBottom, 260, 1f))
            assertTrue(volume.top >= contentBottom)
            assertTrue(volume.top + volume.height <= 260)
            assertEquals(48, volume.left); assertEquals(552, volume.left + volume.width)
        }
    }
    @Test fun centeredSongKeepsItsWidthAndCanRestoreItsOriginalLeft() {
        val offset = checkNotNull(TabletComponentGeometry.centeredOffset(1200, 48, 504))
        assertEquals(300f, offset, 0f)
        assertEquals((1200 - 504) / 2f, 48 + offset, 0f)
        assertNull(TabletComponentGeometry.centeredOffset(600, 0, 600))
    }
    @Test fun volumeKeepsItsProgressWidthWhenOutputDoesNotFit() {
        assertNull(TabletComponentGeometry.outputRow(48, 200, 134, 180, 200, 160, 1f))
        val volume = checkNotNull(TabletComponentGeometry.bottomVolume(48, 200, 156, 100, 200, 1f))
        assertEquals(48, volume.left); assertEquals(200, volume.left + volume.width)
        val centered = checkNotNull(TabletComponentGeometry.bottomVolume(348, 852, 156, 100, 200, 1f))
        assertEquals(348, centered.left)
    }
    @Test fun volumeCanTouchThePlaybackTargetWithoutAnExtraSafetyGap() {
        val volume = checkNotNull(TabletComponentGeometry.bottomVolume(48, 552, 178, 156, 200, 1f))
        assertEquals(156, volume.top); assertEquals(44, volume.height)
    }
}
