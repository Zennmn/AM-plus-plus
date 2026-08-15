package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior coverage for the bounded hand-off between an action-sheet request
 * and its asynchronous artist response map.
 */
class ActionSheetArtistIdTrackerTest {
    @Test
    fun `recorded id is consumed exactly once`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 8,
            ttlMillis = 100,
            clock = clock,
        )

        tracker.record("artist-1")

        assertTrue(tracker.consume("artist-1"))
        assertFalse(tracker.consume("artist-1"))
        assertEquals(0, tracker.size)
    }

    @Test
    fun `same id can represent two concurrent action sheets`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 4,
            ttlMillis = 100,
            clock = clock,
        )

        tracker.record("artist-reentrant")
        clock.nowMillis = 1
        tracker.record("artist-reentrant")

        assertEquals(2, tracker.size)
        assertTrue(tracker.consume("artist-reentrant"))
        assertTrue(tracker.consume("artist-reentrant"))
        assertFalse(tracker.consume("artist-reentrant"))
    }

    @Test
    fun `expired id is not consumable and is removed from size`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 8,
            ttlMillis = 100,
            clock = clock,
        )

        tracker.record("artist-expired")
        clock.nowMillis = 100

        assertFalse(tracker.consume("artist-expired"))
        assertEquals(0, tracker.size)
    }

    @Test
    fun `capacity evicts oldest ids while retaining newer concurrent sheets`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 2,
            ttlMillis = 10_000,
            clock = clock,
        )

        tracker.record("artist-a")
        clock.nowMillis += 1
        tracker.record("artist-b")
        clock.nowMillis += 1
        tracker.record("artist-c")

        assertEquals(2, tracker.size)
        assertFalse(tracker.consume("artist-a"))
        assertTrue(tracker.consume("artist-b"))
        assertTrue(tracker.consume("artist-c"))
    }

    @Test
    fun `duplicate id retains the newer token after the older one expires`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 2,
            ttlMillis = 100,
            clock = clock,
        )

        tracker.record("artist-refresh")
        clock.nowMillis = 90
        tracker.record("artist-refresh")
        clock.nowMillis = 150

        assertEquals(1, tracker.size)
        assertTrue(tracker.consume("artist-refresh"))
    }

    @Test
    fun `unconsumed ids remain bounded`() {
        val clock = MutableTrackerClock()
        val tracker = ActionSheetArtistIdTracker(
            maxEntries = 3,
            ttlMillis = 10_000,
            clock = clock,
        )

        repeat(100) { index ->
            tracker.record("artist-$index")
        }

        assertEquals(3, tracker.size)
        assertFalse(tracker.consume("artist-0"))
        assertTrue(tracker.consume("artist-99"))
    }

    private class MutableTrackerClock(
        var nowMillis: Long = 0,
    ) : () -> Long {
        override fun invoke(): Long = nowMillis
    }
}
