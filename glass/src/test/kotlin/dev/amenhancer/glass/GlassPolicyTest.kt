package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test

class GlassPolicyTest {
    @Test fun onlyVerifiedHostAndHardwareAreEligible() {
        assertTrue(GlassPolicy.supports(33, 1586, "6.5.2", false))
        assertFalse(GlassPolicy.supports(32, 1586, "6.5.2", false))
        assertFalse(GlassPolicy.supports(36, 1583, "6.5.1", false))
        assertFalse(GlassPolicy.supports(36, 1586, "6.5.2", true))
        assertFalse(GlassPolicy.supports(36, 1587, "6.5.2", false))
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
}
