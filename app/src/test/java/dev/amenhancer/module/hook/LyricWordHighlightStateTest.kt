package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordHighlightStateTest {
    @Test
    fun `word streams are unioned and each stream can retire independently`() {
        val state = LyricWordHighlightState()

        assertEquals(setOf(18), state.update("word", setOf(18)))
        assertEquals(setOf(17, 18), state.update("bg-word", setOf(17)))
        assertEquals(setOf(17, 19), state.update("word", setOf(19)))
        assertEquals(setOf(19), state.update("bg-word", emptySet()))
    }

    @Test
    fun `clear removes every word stream`() {
        val state = LyricWordHighlightState()
        state.update("word", setOf(18))

        state.clear()

        assertEquals(emptySet<Int>(), state.snapshot())
    }
}
