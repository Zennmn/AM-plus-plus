package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialGlassSlideTest {
    @Test fun `restored expanded state does not start collapsed`() {
        assertEquals(1f, InitialGlassSlide.resolve(3, 0, 0, 0), 0f)
    }
    @Test fun `dragging settling and half expanded use current position`() {
        for (state in listOf(1, 2, 6)) {
            assertEquals(0.5f, InitialGlassSlide.resolve(state, 550, 1000, 100), 0f)
        }
    }
    @Test fun `collapsed hidden and out of range positions stay bounded`() {
        for (state in listOf(4, 5)) assertEquals(0f, InitialGlassSlide.resolve(state, 100, 1000, 100), 0f)
        assertEquals(0f, InitialGlassSlide.resolve(1, 1200, 1000, 100), 0f)
        assertEquals(1f, InitialGlassSlide.resolve(2, 0, 1000, 100), 0f)
        assertEquals(0f, InitialGlassSlide.resolve(1, 0, 0, 0), 0f)
    }
}
