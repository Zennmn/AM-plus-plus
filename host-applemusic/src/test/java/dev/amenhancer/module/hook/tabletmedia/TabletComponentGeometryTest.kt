package dev.amenhancer.module.hook.tabletmedia

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabletComponentGeometryTest {
    @Test fun referenceControlsContainAllTouchRowsAtTabletDensities() {
        listOf(1f, 2.25f, 2.625f, 3.3125f, 4f).forEach { density ->
            fun px(dp: Int) = (dp * density).roundToInt()
            val rows = checkNotNull(TabletComponentGeometry.rows(px(220), px(41), density))
            assertEquals(px(52), rows.transportHeight)
            val volume = checkNotNull(rows.volumeTop)
            assertTrue(rows.transportTop >= px(41) + px(4))
            assertTrue(rows.transportTop + rows.transportHeight + px(4) <= volume)
            assertTrue(volume + rows.volumeHeight + px(4) <= rows.footerTop)
            assertTrue(rows.footerTop + rows.footerHeight + px(12) <= px(220))
        }
    }
    @Test fun smallerWindowsKeepFullTouchTargetsAndNeverGrowTheirContainer() {
        val withVolume = checkNotNull(TabletComponentGeometry.rows(199, 41, 1f))
        assertEquals(44, withVolume.transportHeight)
        assertNotNull(withVolume.volumeTop)
        assertNull(checkNotNull(TabletComponentGeometry.rows(195, 41, 1f)).volumeTop)
        val narrow = checkNotNull(TabletComponentGeometry.rows(173, 41, 1f))
        assertNull(narrow.volumeTop)
        assertEquals(44, narrow.transportHeight)
        assertTrue(narrow.transportTop + narrow.transportHeight < narrow.footerTop)
        assertTrue(narrow.footerTop + narrow.footerHeight < 173)
        assertNull(TabletComponentGeometry.rows(110, 41, 1f))
    }
    @Test fun variableProgressRowsCannotOverlapTheVolumeOrFooter() {
        for (height in 120..300) for (progress in listOf(30, 41, 60, 85)) {
            val rows = TabletComponentGeometry.rows(height, progress, 1f) ?: continue
            assertTrue(rows.transportTop >= progress + 4)
            assertTrue(rows.transportTop + rows.transportHeight + 4 <= rows.footerTop)
            rows.volumeTop?.let {
                assertTrue(it >= rows.transportTop + rows.transportHeight + 4)
                assertTrue(it + rows.volumeHeight + 4 <= rows.footerTop)
            }
            assertEquals(height - 12, rows.footerTop + rows.footerHeight)
        }
    }
    @Test fun centeredSongKeepsItsWidthAndCanRestoreItsOriginalLeft() {
        val offset = checkNotNull(TabletComponentGeometry.centeredOffset(1200, 48, 504))
        assertEquals(300f, offset, 0f)
        assertEquals((1200 - 504) / 2f, 48 + offset, 0f)
        assertNull(TabletComponentGeometry.centeredOffset(600, 0, 600))
    }
    @Test fun longDeviceNamesStopBeforeFooterButtonsOrTheColumnEdge() {
        assertEquals(152, TabletComponentGeometry.deviceLabelWidth(100, 500, listOf(260, 330), 8, 240))
        assertEquals(240, TabletComponentGeometry.deviceLabelWidth(100, 500, emptyList(), 8, 240))
        assertEquals(0, TabletComponentGeometry.deviceLabelWidth(100, 140, listOf(90), 8, 240))
    }
}
