package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkLyricValueAnimatorTest {
    @Test
    fun `duration is clamped to safe animator bounds`() {
        assertEquals(CjkLyricAnimationPolicy.MIN_DURATION_MS, CjkLyricAnimationPolicy.clampDurationMs(-1L))
        assertEquals(CjkLyricAnimationPolicy.MIN_DURATION_MS, CjkLyricAnimationPolicy.clampDuration(0L))
        assertEquals(420L, CjkLyricAnimationPolicy.clampDurationMs(420L))
        assertEquals(CjkLyricAnimationPolicy.MAX_DURATION_MS, CjkLyricAnimationPolicy.clampDurationMs(Long.MAX_VALUE))
    }

    @Test
    fun `stagger is zero for one target and shrinks as target count grows`() {
        val one = CjkLyricAnimationPolicy.staggerMs(600L, 1)
        val two = CjkLyricAnimationPolicy.staggerMs(600L, 2)
        val many = CjkLyricAnimationPolicy.calculateStaggerMs(600L, 12)

        assertEquals(0L, one)
        assertTrue(two > many)
        assertTrue(many >= 0L)
        assertTrue(many <= CjkLyricAnimationPolicy.MAX_STAGGER_MS)
    }

    @Test
    fun `delay clamps malformed metadata and preserves start offset`() {
        assertEquals(0L, CjkLyricAnimationPolicy.startDelayMs(-4, -10L, -5L))
        assertEquals(125L, CjkLyricAnimationPolicy.delayFor(2, 40L, 45L))
        assertEquals(
            CjkLyricAnimationPolicy.MAX_START_DELAY_MS,
            CjkLyricAnimationPolicy.startDelayMs(Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
    }

    @Test
    fun `default matcher accepts mixed CJK and supplementary ideographs`() {
        assertTrue(defaultCjkLyricScriptPredicate("hello 漢字 world"))
        assertTrue(defaultCjkLyricScriptPredicate("かな"))
        assertTrue(defaultCjkLyricScriptPredicate("한글"))
        assertTrue(defaultCjkLyricScriptPredicate("\uD840\uDC00"))
        assertFalse(defaultCjkLyricScriptPredicate("plain latin lyrics"))
    }
}
