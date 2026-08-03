package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightSessionTest {
    @Test
    fun `empty callbacks retain the previous highlight within one song`() {
        val session = LyricHighlightSession()
        val song = Any()

        assertTrue(session.enter(song))
        session.update(setOf(46))

        assertFalse(session.enter(song))
        assertEquals(setOf(46), session.update(emptySet()))
        assertTrue(session.isGap())
    }

    @Test
    fun `non-empty highlights song changes and fallback events leave gap state`() {
        val session = LyricHighlightSession()

        session.update(emptySet())
        session.update(setOf(46))
        assertFalse(session.isGap())

        session.update(emptySet())
        session.replace(47)
        assertFalse(session.isGap())

        session.update(emptySet())
        session.enter(Any())
        assertFalse(session.isGap())
    }

    @Test
    fun `one callback snapshot keeps every highlighted row clear`() {
        val session = LyricHighlightSession()

        assertEquals(setOf(46, 47), session.update(setOf(46, 47)))
        assertEquals(0f, BidirectionalBlurPolicy.targetRadius(46, session.snapshot()))
        assertEquals(0f, BidirectionalBlurPolicy.targetRadius(47, session.snapshot()))
    }

    @Test
    fun `each non-empty callback replaces the complete highlight snapshot`() {
        val session = LyricHighlightSession()

        session.update(setOf(46))

        assertEquals(setOf(47), session.update(setOf(47)))
        assertEquals(13f, BidirectionalBlurPolicy.targetRadius(46, session.snapshot()))
    }

    @Test
    fun `a line leaving a real multi-highlight snapshot stays clear until the next event`() {
        val session = LyricHighlightSession()

        session.update(setOf(5, 6))

        assertEquals(setOf(5, 6), session.update(setOf(6)))
        assertEquals(0f, BidirectionalBlurPolicy.targetRadius(5, session.snapshot()))
        assertEquals(0f, BidirectionalBlurPolicy.targetRadius(6, session.snapshot()))
        assertEquals(setOf(5, 6), session.update(setOf(6)))
    }

    @Test
    fun `the next distinct highlight snapshot retires a completed overlap`() {
        val session = LyricHighlightSession()

        session.update(setOf(5, 6))
        session.update(setOf(6))

        assertEquals(setOf(6, 7), session.update(setOf(6, 7)))
        assertEquals(13f, BidirectionalBlurPolicy.targetRadius(5, session.snapshot()))
    }

    @Test
    fun `seeking backward never retains a departed future line`() {
        val session = LyricHighlightSession()

        session.update(setOf(5, 6))

        assertEquals(setOf(5), session.update(setOf(5)))
        assertEquals(8f, BidirectionalBlurPolicy.targetRadius(6, session.snapshot()))
    }

    @Test
    fun `entering another song clears a retained highlight before an empty callback`() {
        val session = LyricHighlightSession()
        val firstSong = Any()
        val nextSong = Any()

        session.enter(firstSong)
        session.update(setOf(46))

        assertTrue(session.enter(nextSong))
        assertEquals(emptySet<Int>(), session.snapshot())
        assertEquals(emptySet<Int>(), session.update(emptySet()))
    }

    @Test
    fun `fallback replacement keeps only the latest lyric line clear`() {
        val session = LyricHighlightSession()

        session.update(setOf(45))
        session.replace(47)

        assertEquals(setOf(47), session.snapshot())
    }

    @Test
    fun `song boundaries use pointer identity rather than value equality`() {
        val session = LyricHighlightSession()
        val firstWrapper = EqualToken(7)
        val replacementWrapper = EqualToken(7)

        session.enter(firstWrapper)
        session.update(setOf(12))

        assertTrue(session.enter(replacementWrapper))
        assertEquals(emptySet<Int>(), session.snapshot())
    }

    private data class EqualToken(val value: Int)
}
