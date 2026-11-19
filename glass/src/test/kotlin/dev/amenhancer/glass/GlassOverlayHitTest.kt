package dev.amenhancer.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassOverlayHitTest {
    @Test
    fun `custom hitTest overrides bounds while honoring interactive and alpha gates`() {
        val fullContainer = OverlayBounds(0, 0, 1080, 2400)
        val capsuleOnly = GlassOverlayItem(
            key = "mini",
            bounds = fullContainer,
            interactive = true,
            alpha = 1f,
            hitTest = { x, y -> x in 100f..500f && y in 2000f..2100f },
            content = {},
        )
        assertTrue(glassOverlayHitTest(listOf(capsuleOnly), 300f, 2050f))
        assertFalse(glassOverlayHitTest(listOf(capsuleOnly), 300f, 500f))

        assertFalse(glassOverlayHitTest(listOf(capsuleOnly.copy(alpha = 0f)), 300f, 2050f))
        assertFalse(glassOverlayHitTest(listOf(capsuleOnly.copy(interactive = false)), 300f, 2050f))
        assertFalse(glassOverlayHitTest(listOf(capsuleOnly.copy(alpha = Float.NaN)), 300f, 2050f))
    }
}
