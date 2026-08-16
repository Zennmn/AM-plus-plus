package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 109 settled visual compensation.
 *
 * The policy never mutates layout geometry: it produces a visual
 * translationY for the outer player container. Expanded is always settled at
 * 0; a settled collapsed sheet settles at exactly -tabsHeight. The
 * decision stays binary on `expanded` (sheetTop <= rootHeight / 2) on
 * purpose: the collapsed peek geometry is owned by Apple's holder and is not
 * measurable here, so no continuous sheetTop -> collapsed mapping can be
 * verified. The reservation latch makes a detected collapsed state sticky,
 * so the flip cannot oscillate.
 */
class FlatPlayerBoundaryPolicyTest {
    @Test
    fun `detects navigation overlap below the eager aspect ratio gate`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 982,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(-126, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `keeps detected reservation after the overlap has moved above tabs`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 856,
            sheetBottom = 954,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(-126, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `leaves ordinary non-overlapping tablet geometry unchanged`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 800,
            sheetTop = 650,
            sheetBottom = 744,
            tabsTop = 744,
            tabsHeight = 56,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet remains native before a reservation is observed`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet stays visible when it does not cover the navigation area`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 900,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet clears compensation and hides tabs after reservation`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(0, decision.translationY)
        assertFalse(decision.tabsVisible)
    }

    @Test
    fun `collapsed wide sheet reserves only the navigation inset`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1440,
            sheetTop = 1066,
            sheetBottom = 1296,
            tabsTop = 1296,
            tabsHeight = 144,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(-144, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `fresh collapsed overlap uses the dynamic inset`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1440,
            sheetTop = 1178,
            sheetBottom = 1408,
            tabsTop = 1296,
            tabsHeight = 144,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(-144, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `reserved collapsed sheet clears the full tabs frame`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 982,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(-126, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `collapsed sheet without an observed reservation stays at zero translation`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 700,
            sheetBottom = 1080,
            tabsTop = 200,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.translationY)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `sheet measurement excludes the module's own container translation`() {
        val corrected = FlatPlayerBoundaryPolicy.sheetTopRelativeToRoot(
            sheetWindowTop = 525,
            rootWindowTop = 0,
            containerTranslationY = -16f,
        )

        // The raw window top 525 has already been pulled up by the -16
        // compensation; the corrected value restores the layout geometry.
        assertEquals(541, corrected)
    }

    @Test
    fun `feedback-corrected collapsed geometry does not flip back to expanded`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = FlatPlayerBoundaryPolicy.sheetTopRelativeToRoot(
                sheetWindowTop = 525,
                rootWindowTop = 0,
                containerTranslationY = -16f,
            ),
            sheetBottom = 900,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = true,
        )

        assertEquals(-126, decision.translationY)

        // Without the correction the same raw window top reads as expanded
        // and would drop the compensation back to 0.
        val raw = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 525,
            sheetBottom = 900,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = true,
        )
        assertEquals(0, raw.translationY)
    }
}
