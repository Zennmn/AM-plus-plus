package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordHighlightStateTest {
    @Test
    fun `word evidence arriving after a line subset keeps the departed row`() {
        val state = LyricWordHighlightState()

        state.onLineHighlightsChanged(setOf(1, 2))
        state.onLineHighlightsChanged(setOf(1))
        state.update("word", setOf(2))
        state.update("word", emptySet())

        assertEquals(setOf(2), state.snapshot())
    }

    @Test
    fun `subset grace only does not suppress the instrumental anchor during a line gap`() {
        val session = LyricHighlightSession()
        val state = LyricWordHighlightState()

        session.update(setOf(1, 2))
        state.onLineHighlightsChanged(setOf(1, 2))
        state.update("word", setOf(2))

        state.onLineHighlightsChanged(setOf(1))
        session.update(setOf(1))
        state.update("word", emptySet())
        state.onLineHighlightsChanged(emptySet())
        session.update(emptySet())

        val wordActiveIds = state.snapshot()
        assertEquals(setOf(2), wordActiveIds)
        assertEquals(emptySet<Int>(), state.liveSnapshot())

        val gap = session.isGap() && state.liveSnapshot().isEmpty()
        assertEquals(
            3,
            BidirectionalBlurPolicy.selectInstrumentalGapAnchor(
                active = session.snapshot() + wordActiveIds,
                isGap = gap,
                isOpeningHighlight = session.isOpeningHighlight(),
                instrumentalPositions = listOf(3),
                visiblePositions = listOf(1, 2),
            ),
        )
    }

    @Test
    fun `empty line callback does not retire subset grace`() {
        val state = LyricWordHighlightState()

        state.onLineHighlightsChanged(setOf(1, 2))
        state.onLineHighlightsChanged(setOf(1))
        state.onLineHighlightsChanged(emptySet())
        state.update("word", setOf(2))
        state.update("word", emptySet())

        assertEquals(setOf(2), state.snapshot())

        state.onLineHighlightsChanged(setOf(1))
        assertEquals(setOf(2), state.snapshot())

        state.onLineHighlightsChanged(setOf(1, 3))
        assertEquals(emptySet<Int>(), state.snapshot())
    }

    @Test
    fun `word evidence bridges a line subset after the word stream has already retired`() {
        val session = LyricHighlightSession()
        val state = LyricWordHighlightState()

        session.update(setOf(5, 6))
        state.onLineHighlightsChanged(setOf(5, 6))
        state.update("word", setOf(6))
        state.update("word", emptySet())

        state.onLineHighlightsChanged(setOf(5))
        session.update(setOf(5))

        assertEquals(setOf(5, 6), session.snapshot() + state.snapshot())
    }

    @Test
    fun `line subset grace survives a later word retirement but expires on the next distinct line`() {
        val session = LyricHighlightSession()
        val state = LyricWordHighlightState()

        session.update(setOf(5, 6))
        state.onLineHighlightsChanged(setOf(5, 6))
        state.update("word", setOf(6))

        state.onLineHighlightsChanged(setOf(5))
        session.update(setOf(5))
        state.update("word", emptySet())

        assertEquals(setOf(5, 6), session.snapshot() + state.snapshot())

        state.onLineHighlightsChanged(setOf(5, 7))
        session.update(setOf(5, 7))

        assertEquals(setOf(5, 7), session.snapshot() + state.snapshot())
    }

    @Test
    fun `seeking backward without word evidence does not retain the departed future row`() {
        val session = LyricHighlightSession()
        val state = LyricWordHighlightState()

        session.update(setOf(5, 6))
        state.onLineHighlightsChanged(setOf(5, 6))
        session.update(setOf(5))

        assertEquals(setOf(5), session.snapshot() + state.snapshot())
    }

    @Test
    fun `word evidence for a future row does not grace a different departed line`() {
        val state = LyricWordHighlightState()

        state.onLineHighlightsChanged(setOf(5, 6))
        state.update("word", setOf(7))
        state.update("word", emptySet())
        state.onLineHighlightsChanged(setOf(5))

        assertEquals(emptySet<Int>(), state.snapshot())
    }

    @Test
    fun `native seek reset removes line grace but keeps the live word source`() {
        val state = LyricWordHighlightState()

        state.onLineHighlightsChanged(setOf(5, 6))
        state.update("word", setOf(6))
        state.onLineHighlightsChanged(setOf(5))
        state.update("word", emptySet())
        assertEquals(setOf(6), state.snapshot())
        assertEquals(emptySet<Int>(), state.liveSnapshot())

        state.resetLineHistory()

        assertEquals(emptySet<Int>(), state.snapshot())
        assertEquals(emptySet<Int>(), state.liveSnapshot())
        state.update("word", setOf(6))
        state.resetLineHistory()
        assertEquals(setOf(6), state.snapshot())
        assertEquals(setOf(6), state.liveSnapshot())
        state.update("word", emptySet())
        state.onLineHighlightsChanged(setOf(5))
        assertEquals(emptySet<Int>(), state.snapshot())
        assertEquals(emptySet<Int>(), state.liveSnapshot())
    }

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
        state.onLineHighlightsChanged(setOf(17, 18))
        state.update("word", setOf(18))
        state.onLineHighlightsChanged(setOf(17))

        state.clear()

        assertEquals(emptySet<Int>(), state.snapshot())

        state.onLineHighlightsChanged(setOf(17, 19))
        assertEquals(emptySet<Int>(), state.snapshot())
    }
}
