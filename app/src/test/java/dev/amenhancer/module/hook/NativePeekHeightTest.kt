package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePeekHeightTest {
    @Test fun `latest host request replaces activation fallback`() {
        val state = NativePeekHeight()
        state.initialize(100)
        state.observe(240)
        state.initialize(120)
        assertEquals(240, state.latest)
    }

    @Test fun `module writes cannot replace host height including nested calls`() {
        val state = NativePeekHeight()
        state.observe(240)
        state.writeByModule {
            state.observe(300)
            state.writeByModule { state.observe(320) }
            state.observe(340)
        }
        assertEquals(240, state.latest)
        state.observe(260)
        assertEquals(260, state.latest)
    }

    @Test fun `failed module setter does not suppress future host requests`() {
        val state = NativePeekHeight()
        state.observe(240)
        runCatching { state.writeByModule { error("setter failed") } }
        state.observe(280)
        assertEquals(280, state.latest)
    }
}
