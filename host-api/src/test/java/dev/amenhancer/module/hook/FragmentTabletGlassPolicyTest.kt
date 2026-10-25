package dev.amenhancer.module.hook

import org.junit.Assert.*
import org.junit.Test

class FragmentTabletGlassPolicyTest {
    @Test fun `drawer-only startup material cannot take ownership even with a complete glass layout`() {
        // OPD2515, density 420: native top_nav_blur and glass both measured 126 x 126
        // while the model already contained all five destinations.
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(126, 126, 126, 126))
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(125, 126, 125, 126))
        // Native composition is allowed to finish, then glass follows the expanded material.
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(1068, 126, 126, 126))
        assertTrue(FragmentTabletGlassPolicy.navigationLayoutReady(1068, 126, 1068, 126))
        // A later recreation must wait for the native menu again.
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(126, 126, 126, 126))
        assertTrue(FragmentTabletGlassPolicy.navigationLayoutReady(700, 56, 700, 56))
    }

    @Test fun `screen coordinates project to the root without resizing native wide controls`() {
        val bounds = FragmentTabletGlassPolicy.bounds(100, 80, 350, 900, 690, 64)!!
        assertEquals(FragmentTabletGlassPolicy.Bounds(250, 820, 690, 64), bounds)
        assertTrue(bounds.contains(250f, 820f))
        assertTrue(bounds.contains(939.9f, 883.9f))
        assertFalse(bounds.contains(940f, 840f))
        assertFalse(bounds.contains(500f, 884f))
        assertEquals(FragmentTabletGlassPolicy.Bounds(-20, 5, 420, 48),
            FragmentTabletGlassPolicy.bounds(100, 80, 80, 85, 420, 48))
        assertNull(FragmentTabletGlassPolicy.bounds(0, 0, 0, 0, 0, 64))
        assertNull(FragmentTabletGlassPolicy.bounds(0, 0, 0, 0, 690, -1))
    }

    @Test fun `native navigation remains during placeholder and incomplete composition layouts`() {
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(1, 1, 1, 1))
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(700, 56, 0, 0))
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(700, 56, 700, 0))
        assertTrue(FragmentTabletGlassPolicy.navigationLayoutReady(700, 56, 700, 56))
    }

    @Test fun `window resize waits for new navigation content before taking ownership`() {
        assertTrue(FragmentTabletGlassPolicy.navigationLayoutReady(700, 56, 700, 56))
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(900, 64, 700, 56))
        assertTrue(FragmentTabletGlassPolicy.navigationLayoutReady(900, 64, 900, 64))
        assertFalse(FragmentTabletGlassPolicy.navigationLayoutReady(900, 56, 900, 64))
    }

    @Test fun `native sheet alpha and hidden ancestors fade glass rather than leaving a capsule`() {
        assertEquals(0.125f, FragmentTabletGlassPolicy.opacity(true, listOf(0.5f, 0.25f)), 0f)
        assertEquals(0f, FragmentTabletGlassPolicy.opacity(false, listOf(1f)), 0f)
        assertEquals(0f, FragmentTabletGlassPolicy.opacity(true, listOf(1f, 0f)), 0f)
        assertEquals(0f, FragmentTabletGlassPolicy.opacity(true, listOf(Float.NaN)), 0f)
        assertEquals(1f, FragmentTabletGlassPolicy.opacity(true, listOf(1.2f)), 0f)
        val nativeFade = kotlin.math.exp(-300f * 0.03f)
        assertEquals(nativeFade, FragmentTabletGlassPolicy.opacity(true, listOf(nativeFade, 1f)), 0f)
    }

    @Test fun `hidden native blur does not hide the replacement navigation at rest`() {
        // Native paint updates may keep the replaced BlurView transparent.
        val contentAlpha = 1f
        repeat(3) { // before press, while pressing, after release: no input state is consulted
            assertEquals(1f, FragmentTabletGlassPolicy.navigationOpacity(true, contentAlpha, listOf(1f), 0f), 0f)
        }
    }

    @Test fun `navigation still respects hidden content and ancestor fades`() {
        assertEquals(.2f, FragmentTabletGlassPolicy.navigationOpacity(true, .5f, listOf(.4f), 0f), .00001f)
        assertEquals(0f, FragmentTabletGlassPolicy.navigationOpacity(false, 1f, listOf(1f), 0f), 0f)
        assertEquals(0f, FragmentTabletGlassPolicy.navigationOpacity(true, 0f, listOf(1f), 0f), 0f)
        assertEquals(0f, FragmentTabletGlassPolicy.navigationOpacity(true, 1f, listOf(0f), 0f), 0f)
    }

    @Test fun `expanded player hides navigation and collapse restores it without a press`() {
        for (slide in listOf(0f, .1f, .35f, .475f, .6f, 1f, .6f, .475f, .35f, 0f)) {
            assertEquals(FragmentPlayerSurfaceMotion.tabletFrame(slide).alpha,
                FragmentTabletGlassPolicy.navigationOpacity(true, 1f, emptyList(), slide), .00001f)
        }
        assertEquals(1f, FragmentTabletGlassPolicy.navigationOpacity(true, 1f, emptyList(), 0f), 0f)
    }

    @Test fun `mini keeps the established glass morph instead of adopting the rapid native fade`() {
        val start = FragmentPlayerSurfaceMotion.tabletFrame(0f)
        assertEquals(0f, start.expansion, 0f)
        assertEquals(1f, start.alpha, 0f)
        val early = FragmentPlayerSurfaceMotion.tabletFrame(0.03f)
        assertEquals(1f, early.alpha, 0f)
        assertTrue(early.expansion > 0f)
        assertEquals(1f, FragmentPlayerSurfaceMotion.tabletFrame(0.35f).expansion, 0f)
        assertEquals(1f, FragmentPlayerSurfaceMotion.tabletFrame(0.35f).alpha, 0f)
        assertEquals(0.5f, FragmentPlayerSurfaceMotion.tabletFrame(0.475f).alpha, 0.00001f)
        assertEquals(0f, FragmentPlayerSurfaceMotion.tabletFrame(0.6f).alpha, 0f)
        assertEquals(0f, FragmentPlayerSurfaceMotion.tabletFrame(0.6f).motion, 0f)
        assertEquals(1f, FragmentPlayerSurfaceMotion.tabletFrame(0.85f).motion, 0f)
        assertEquals(start, FragmentPlayerSurfaceMotion.tabletFrame(Float.NaN))
        assertEquals(start, FragmentPlayerSurfaceMotion.tabletFrame(-1f))
        assertEquals(FragmentPlayerSurfaceMotion.tabletFrame(1f), FragmentPlayerSurfaceMotion.tabletFrame(2f))
    }
}
