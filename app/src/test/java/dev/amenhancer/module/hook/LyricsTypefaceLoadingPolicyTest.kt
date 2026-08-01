package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable pure-logic coverage for the background state and merged dispatch. */
class LyricsTypefaceLoadingPolicyTest {
    @Test
    fun `controller starts once and holds loading until it settles`() {
        val controller = LyricsTypefaceLoadController<String>()

        assertTrue(controller.start())
        assertFalse(controller.start())

        assertEquals(LyricsTypefaceLoadController.Phase.LOADING, controller.phase())
        assertNull(controller.readyValue())
        assertNull(controller.failureMessage())

        controller.succeed("imported font")

        assertEquals(LyricsTypefaceLoadController.Phase.READY, controller.phase())
        assertEquals("imported font", controller.readyValue())
        assertNull(controller.failureMessage())
        assertFalse(controller.start())
    }

    @Test
    fun `a failed load keeps the original font and reports its diagnostic`() {
        val controller = LyricsTypefaceLoadController<String>()
        controller.start()
        controller.fail("Remote font hash did not match its manifest")

        assertEquals(LyricsTypefaceLoadController.Phase.FAILED, controller.phase())
        assertNull(controller.readyValue())
        assertEquals("Remote font hash did not match its manifest", controller.failureMessage())
        assertFalse(controller.start())
    }

    @Test
    fun `ready value is only visible after success`() {
        val controller = LyricsTypefaceLoadController<Int>()
        assertFalse(controller.readyValue() != null)

        controller.start()
        controller.succeed(7)

        assertEquals(7, controller.readyValue())
    }

    @Test
    fun `delayed apply gate merges bursty triggers into one pending reapply`() {
        val gate = DelayedApplyGate()
        val root = Any()

        assertTrue(gate.tryAcquire(root))
        assertFalse(gate.tryAcquire(root))
        assertFalse(gate.tryAcquire(root))

        gate.release(root)
        assertTrue(gate.tryAcquire(root))

        // A different root is not blocked by the pending one.
        assertTrue(gate.tryAcquire(Any()))
    }
}
