package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tablet row geometry: inside the collapsed band only the two capsule handles own a down
 * event. Everything else — the empty side areas, the gap between the capsules and the corners
 * the stadium shape does not cover — is empty row that must neither drag the sheet nor
 * swallow the touch that belongs to the page below.
 */
class TabletRowGestureGateTest {

    private val band = RowBandRect(0, 2161, 3392, 2400)
    private val pillHeight = 218

    // The session publishes the capsules as the very rectangles its glass surfaces occupy:
    // nav [565, 2036], mini [2148, 2827] on a 3392 wide host, both 56dp tall from the row top.
    private fun handles(miniVisible: Boolean = true) = buildList {
        add(RowCapsuleRect(565, 2161, 2036, 2161 + pillHeight))
        if (miniVisible) add(RowCapsuleRect(2148, 2161, 2827, 2161 + pillHeight))
    }

    private fun row(slop: Int = 0, miniVisible: Boolean = true) = TabletRowBand(handles(miniVisible), slop)

    @Test
    fun `empty side areas belong to the page below`() {
        assertTrue(row().blocksDrag(200f, 2270f, band))
        assertTrue(row().blocksDrag(3200f, 2270f, band))
    }

    @Test
    fun `the gap between the capsules belongs to the page below`() {
        assertTrue(row().blocksDrag(2100f, 2270f, band))
    }

    @Test
    fun `both capsules keep their own gestures`() {
        assertFalse(row().blocksDrag(1200f, 2270f, band))
        assertFalse(row().blocksDrag(2500f, 2270f, band))
    }

    @Test
    fun `capsule corners follow the stadium shape`() {
        // Inside the bounding box but outside the rounded end: empty row, not a handle.
        assertTrue(row().blocksDrag(566f, 2162f, band))
        assertTrue(row().blocksDrag(2035f, (2161 + pillHeight - 1).toFloat(), band))
        // The vertical centre of that same end is inside the shape.
        assertFalse(row().blocksDrag(566f, (2161 + pillHeight / 2).toFloat(), band))
    }

    @Test
    fun `the strip below the capsules belongs to the page below`() {
        assertTrue(row().blocksDrag(1200f, 2399f, band))
    }

    @Test
    fun `a hidden mini player gives up its slot`() {
        assertTrue(row(miniVisible = false).blocksDrag(2500f, 2270f, band))
    }

    @Test
    fun `keeps a small tolerance around each capsule end`() {
        assertFalse(row(slop = 30).blocksDrag(545f, 2270f, band))
        assertFalse(row(slop = 30).blocksDrag(2845f, 2270f, band))
        assertTrue(row(slop = 0).blocksDrag(545f, 2270f, band))
        assertTrue(row(slop = 0).blocksDrag(2845f, 2270f, band))
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
