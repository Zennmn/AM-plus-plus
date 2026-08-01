package dev.amenhancer.module.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable pure-logic coverage for the bounded retry decision. */
class FontLoadRetryPolicyTest {
    @Test
    fun `transient failures retry only up to the bounded attempt count`() {
        assertTrue(FontLoadRetryPolicy.shouldRetry(0, transient = true))
        assertTrue(FontLoadRetryPolicy.shouldRetry(1, transient = true))
        assertFalse(FontLoadRetryPolicy.shouldRetry(2, transient = true))
        assertFalse(FontLoadRetryPolicy.shouldRetry(3, transient = true))
    }

    @Test
    fun `permanent validation failures never retry`() {
        assertFalse(FontLoadRetryPolicy.shouldRetry(0, transient = false))
        assertFalse(FontLoadRetryPolicy.shouldRetry(1, transient = false))
        assertFalse(FontLoadRetryPolicy.shouldRetry(2, transient = false))
    }

    @Test
    fun `backoff grows exponentially and stays capped`() {
        assertEquals(100L, FontLoadRetryPolicy.backoffMillis(0))
        assertEquals(200L, FontLoadRetryPolicy.backoffMillis(1))
        assertEquals(400L, FontLoadRetryPolicy.backoffMillis(2))
        assertEquals(800L, FontLoadRetryPolicy.backoffMillis(3))
        assertEquals(1000L, FontLoadRetryPolicy.backoffMillis(4))
        assertEquals(1000L, FontLoadRetryPolicy.backoffMillis(8))
    }

    @Test
    fun `max attempts matches the retry schedule`() {
        // With three attempts (0, 1, 2) there are exactly two retry sleeps.
        assertEquals(3, FontLoadRetryPolicy.MAX_ATTEMPTS)
        val retries = (0 until FontLoadRetryPolicy.MAX_ATTEMPTS)
            .count { FontLoadRetryPolicy.shouldRetry(it, transient = true) }
        assertEquals(2, retries)
    }
}
