package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tablet row band geometry: inside the collapsed band only the two capsule handles may
 * start a sheet drag. Everything else is empty side space and must not expand the player.
 */
class TabletRowGestureGateTest {

    private val frameWidth = 3392
    private val band = RowBandRect(0, 2056, 3392, 2400)

    // The slots the tablet session publishes: nav [W/6, 2W/5] -> capsule [565, 2036],
    // mini [19W/30, W/6] -> capsule [2148, 2827].
    private fun row(slop: Int = 24) = TabletRowBand(
        frameWidth = frameWidth,
        navSlot = intArrayOf(frameWidth / 6, frameWidth * 2 / 5),
        miniSlot = intArrayOf(frameWidth * 19 / 30, frameWidth / 6),
        slop = slop,
    )

    @Test
    fun `blocks a drag from either empty side area`() {
        assertTrue(row().blocksDrag(200f, 2270f, band))
        assertTrue(row().blocksDrag(3200f, 2270f, band))
    }

    @Test
    fun `blocks a drag from the gap between the capsules`() {
        assertTrue(row().blocksDrag(2100f, 2270f, band))
    }

    @Test
    fun `keeps both capsules as drag handles`() {
        assertFalse(row().blocksDrag(1200f, 2270f, band))
        assertFalse(row().blocksDrag(2500f, 2270f, band))
    }

    @Test
    fun `keeps a small tolerance around each capsule edge`() {
        // 2056 sits inside the nav capsule's slop band, 2130 inside the mini's.
        assertFalse(row(slop = 24).blocksDrag(2056f, 2270f, band))
        assertFalse(row(slop = 24).blocksDrag(2130f, 2270f, band))
        // Without the tolerance those points are empty row and must not drag.
        assertTrue(row(slop = 0).blocksDrag(2050f, 2270f, band))
        assertTrue(row(slop = 0).blocksDrag(2120f, 2270f, band))
    }

    @Test
    fun `ignores points outside the collapsed band`() {
        assertFalse(row().blocksDrag(200f, 1800f, band))
        assertFalse(row().blocksDrag(200f, 2450f, band))
    }

    @Test
    fun `fails open without a measurable band`() {
        assertFalse(row().blocksDrag(200f, 2270f, RowBandRect(0, 0, 0, 0)))
    }
}
