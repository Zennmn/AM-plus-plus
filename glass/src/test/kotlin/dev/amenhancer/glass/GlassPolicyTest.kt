package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test

class GlassPolicyTest {
    @Test fun onlyVerifiedHostAndHardwareAreEligible() {
        assertTrue(GlassPolicy.supports(33, 1586, "6.5.2", false))
        assertTrue(GlassPolicy.supports(36, 1599, "6.5.3", false))
        assertFalse(GlassPolicy.supports(32, 1586, "6.5.2", false))
        assertFalse(GlassPolicy.supports(32, 1599, "6.5.3", false))
        assertFalse(GlassPolicy.supports(36, 1583, "6.5.1", false))
        assertFalse(GlassPolicy.supports(36, 1586, "6.5.2", true))
        assertFalse(GlassPolicy.supports(36, 1599, "6.5.3", true))
        assertFalse(GlassPolicy.supports(36, 1587, "6.5.2", false))
        assertFalse(GlassPolicy.supports(36, 1600, "6.5.3", false))
        assertFalse(GlassPolicy.supports(36, 1599, "6.5.4", false))
    }
    @Test fun menuReorderUsesIdentityAndMissingSelectionIsNotGuessed() {
        assertEquals(0, GlassPolicy.selectedIndex(listOf(30, 10, 20), 30))
        assertEquals(2, GlassPolicy.selectedIndex(listOf(10, 20, 30), 30))
        assertNull(GlassPolicy.selectedIndex(listOf(10, 20), 30))
        assertNull(GlassPolicy.selectedIndex(emptyList(), 30))
    }
    @Test fun absentMiniPlayerDoesNotLeavePhantomSpaceAndInsetsArePixels() {
        assertEquals(168, GlassPolicy.occupiedHeight(2f, 24, false))
        assertEquals(270, GlassPolicy.occupiedHeight(2f, 24, true))
        assertEquals(72, GlassPolicy.occupiedHeight(1f, 0, false))
    }
    @Test fun bottomGapOverrideKeepsTheDefaultContract() {
        // The parameter defaults to BOTTOM_DP, so existing callers are unchanged.
        assertEquals(
            GlassPolicy.occupiedHeight(2f, 24, false),
            GlassPolicy.occupiedHeight(2f, 24, false, GlassPolicy.BOTTOM_DP),
        )
        // A larger lift grows the occupied area: the capsule sits higher above the edge.
        assertEquals(184, GlassPolicy.occupiedHeight(2f, 24, false, bottomGapDp = 24))
        assertEquals(286, GlassPolicy.occupiedHeight(2f, 24, true, bottomGapDp = 24))
        assertEquals(56, GlassPolicy.occupiedHeight(1f, 0, false, bottomGapDp = 0))
    }
}
