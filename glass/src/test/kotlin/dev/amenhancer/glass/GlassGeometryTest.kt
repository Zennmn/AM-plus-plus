package dev.amenhancer.glass

import org.junit.Assert.*
import org.junit.Test

class GlassGeometryTest {
    @Test fun phoneAndTabletPresetsStartIdentical() {
        // sw640dp native miniplayer_height=59dp / mini_player_thumbnail_height=41dp are the
        // documented fork reference; both presets deliberately share the phone capsule today.
        assertEquals(GlassGeometry.Phone, GlassGeometry.Tablet)
        assertEquals(
            GlassGeometry(navHeightDp = 56, miniHeightDp = 43, horizontalDp = 16, gapDp = 8),
            GlassGeometry.Phone,
        )
        assertEquals(GlassPolicy.NAV_HEIGHT_DP, GlassGeometry.Phone.navHeightDp)
        assertEquals(GlassPolicy.MINI_HEIGHT_DP, GlassGeometry.Phone.miniHeightDp)
        assertEquals(GlassPolicy.HORIZONTAL_DP, GlassGeometry.Phone.horizontalDp)
        assertEquals(GlassPolicy.GAP_DP, GlassGeometry.Phone.gapDp)
    }

    @Test fun occupiedHeightDefaultsToPhoneGeometry() {
        assertEquals(
            GlassPolicy.occupiedHeight(2f, 24, true),
            GlassPolicy.occupiedHeight(2f, 24, true, GlassPolicy.BOTTOM_DP, GlassGeometry.Phone),
        )
        assertEquals(
            GlassPolicy.occupiedHeight(1f, 0, false),
            GlassPolicy.occupiedHeight(1f, 0, false, geometry = GlassGeometry.Phone),
        )
    }

    @Test fun occupiedHeightFollowsAForkedGeometry() {
        val forked = GlassGeometry(navHeightDp = 64, miniHeightDp = 51, horizontalDp = 20, gapDp = 10)
        // (64 + 16 + 51 + 10) * 2 + 24 = 306
        assertEquals(306, GlassPolicy.occupiedHeight(2f, 24, true, geometry = forked))
        // (64 + 16) * 2 + 24 = 184
        assertEquals(184, GlassPolicy.occupiedHeight(2f, 24, false, geometry = forked))
        // geometry composes with a custom bottom lift: (64 + 24 + 51 + 10) * 1 + 8 = 157
        assertEquals(157, GlassPolicy.occupiedHeight(1f, 8, true, bottomGapDp = 24, geometry = forked))
    }

    @Test fun hostFormsShareOneSeamWhitelist() {
        assertTrue(GlassPolicy.supports(33, 1586, "6.5.2", GlassHostForm.PhoneStacked))
        assertTrue(GlassPolicy.supports(33, 1586, "6.5.2", GlassHostForm.TabletDualPane))
        assertTrue(GlassPolicy.supports(36, 1599, "6.5.3", GlassHostForm.TabletDualPane))
        assertFalse(GlassPolicy.supports(32, 1586, "6.5.2", GlassHostForm.TabletDualPane))
        assertFalse(GlassPolicy.supports(32, 1599, "6.5.3", GlassHostForm.PhoneStacked))
        assertFalse(GlassPolicy.supports(36, 1583, "6.5.1", GlassHostForm.PhoneStacked))
        assertFalse(GlassPolicy.supports(36, 1587, "6.5.2", GlassHostForm.TabletDualPane))
        assertFalse(GlassPolicy.supports(36, 1599, "6.5.4", GlassHostForm.TabletDualPane))
    }

    @Test fun legacyTabletFlagKeepsPhoneOnlySemantics() {
        // phone = !tablet: the legacy overload keeps rejecting tablets while delegating
        // the build check to the form overload.
        assertTrue(GlassPolicy.supports(33, 1586, "6.5.2", false))
        assertTrue(GlassPolicy.supports(36, 1599, "6.5.3", false))
        assertFalse(GlassPolicy.supports(33, 1586, "6.5.2", true))
        assertFalse(GlassPolicy.supports(36, 1599, "6.5.3", true))
        assertFalse(GlassPolicy.supports(32, 1586, "6.5.2", false))
        assertFalse(GlassPolicy.supports(36, 1587, "6.5.2", false))
    }
}
