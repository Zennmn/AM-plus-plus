package dev.amenhancer.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassMaterialPolicyTest {
    @Test
    fun tierPolicySeparatesHeroRefractionFromChromeSurfaces() {
        assertTrue(LiquidGlassMaterialPolicy.usesLens(GlassTier.Hero))
        assertFalse(LiquidGlassMaterialPolicy.usesLens(GlassTier.Chrome))

        assertTrue(LiquidGlassMaterialPolicy.highlightWidthDp(GlassTier.Hero) >
            LiquidGlassMaterialPolicy.highlightWidthDp(GlassTier.Chrome))
        assertEquals(10f, LiquidGlassMaterialPolicy.effectiveBlurDp(10f, GlassTier.Hero), 0.001f)
        assertEquals(8.5f, LiquidGlassMaterialPolicy.effectiveBlurDp(10f, GlassTier.Chrome), 0.001f)
        assertEquals(0f, LiquidGlassMaterialPolicy.effectiveBlurDp(-4f, GlassTier.Chrome), 0.001f)
    }

    @Test
    fun refractionClampsToSmallControlMinDimension() {
        assertEquals(15f, LiquidGlassMaterialPolicy.maxRefractionPx(minDimensionPx = 40f, maxDpPx = 24f), 0.001f)
        assertEquals(24f, LiquidGlassMaterialPolicy.maxRefractionPx(minDimensionPx = 120f, maxDpPx = 24f), 0.001f)
        assertEquals(0f, LiquidGlassMaterialPolicy.maxRefractionPx(minDimensionPx = -5f, maxDpPx = 24f), 0.001f)
    }

    @Test
    fun overlayHitTestMatchesOnlyInteractiveVisibleBounds() {
        val visibleButton = GlassOverlayItem(
            key = "play",
            bounds = OverlayBounds(10, 20, 110, 60),
            interactive = true,
            alpha = 1f,
        ) {}
        val hiddenButton = GlassOverlayItem(
            key = "hidden",
            bounds = OverlayBounds(200, 20, 300, 60),
            interactive = true,
            alpha = 0f,
        ) {}
        val staticCard = GlassOverlayItem(
            key = "card",
            bounds = OverlayBounds(10, 100, 210, 220),
            interactive = false,
            alpha = 1f,
        ) {}

        val items = listOf(visibleButton, hiddenButton, staticCard)
        assertTrue(glassOverlayHitTest(items, 10f, 20f))
        assertTrue(glassOverlayHitTest(items, 109.5f, 59.5f))
        assertFalse(glassOverlayHitTest(items, 110f, 59.5f))
        assertFalse(glassOverlayHitTest(items, 250f, 40f))
        assertFalse(glassOverlayHitTest(items, 50f, 150f))
        assertFalse(glassOverlayHitTest(emptyList(), 20f, 30f))
    }
}
