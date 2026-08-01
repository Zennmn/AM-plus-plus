package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(126, decision.bottomMargin)
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
        assertEquals(126, decision.bottomMargin)
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
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet remains inert before an overlap has been observed`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet clears margin and hides tabs after reservation`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertFalse(decision.tabsVisible)
    }
}
