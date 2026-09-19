package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLayerAlphaTest {
    @Test fun `ordinary track hides previous motion artwork across expand cycles`() {
        val alpha = NativeLayerAlpha(1f)
        alpha.factor = 1f
        assertEquals(0f, alpha.hostWrite(0f), 0f)
        for (factor in listOf(0f, .3f, 1f, 0f, 1f)) {
            alpha.factor = factor
            assertEquals(0f, alpha.effective, 0f)
        }
    }
    @Test fun `motion track arriving while collapsed keeps native alpha for later reveal`() {
        val alpha = NativeLayerAlpha(0f)
        alpha.factor = 0f
        assertEquals(0f, alpha.hostWrite(1f), 0f)
        alpha.factor = .5f
        assertEquals(.5f, alpha.effective, 0f)
        alpha.factor = 1f
        assertEquals(1f, alpha.effective, 0f)
    }
    @Test fun `native fade is multiplied without overwriting its target`() {
        val alpha = NativeLayerAlpha(1f)
        alpha.factor = .5f
        assertEquals(.2f, alpha.hostWrite(.4f), .0001f)
        assertEquals(.4f, alpha.native, 0f)
    }
}
