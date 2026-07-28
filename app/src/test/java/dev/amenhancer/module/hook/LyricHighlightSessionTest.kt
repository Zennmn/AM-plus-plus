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
