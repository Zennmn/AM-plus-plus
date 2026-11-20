package dev.amenhancer.glass

import dev.amenhancer.backdrop.BackdropCaptureGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropCaptureGateTest {
    @Test
    fun legacyExplicitTogglePreservesSingleConsumerContract() {
        val gate = BackdropCaptureGate()
        assertTrue(gate.isCaptureAllowed)

        assertFalse(gate.setExplicitEnabled(true))
        assertFalse(gate.setExplicitEnabled(false))
        assertFalse(gate.isCaptureAllowed)

        assertTrue(gate.setExplicitEnabled(true))
        assertTrue(gate.isCaptureAllowed)
    }

    @Test
    fun consumerRefCountingKeepsSharedBackdropAliveUntilLastLeaves() {
        val gate = BackdropCaptureGate()

        // First consumer transitions from untracked-active; second joins while already active.
        assertFalse(gate.acquireConsumer())
        assertFalse(gate.acquireConsumer())
        assertEquals(2, gate.activeConsumers)
        assertTrue(gate.isCaptureAllowed)

        assertFalse(gate.releaseConsumer())
        assertEquals(1, gate.activeConsumers)
        assertTrue(gate.isCaptureAllowed)

        assertTrue(gate.releaseConsumer())
        assertEquals(0, gate.activeConsumers)
        assertFalse(gate.isCaptureAllowed)

        // Extra release clamps cleanly at zero without breaking future acquisitions.
        assertFalse(gate.releaseConsumer())
        assertEquals(0, gate.activeConsumers)
        assertTrue(gate.acquireConsumer())
        assertTrue(gate.isCaptureAllowed)
    }

    @Test
    fun explicitDisableOverridesActiveConsumersUntilReEnabled() {
        val gate = BackdropCaptureGate()
        gate.acquireConsumer()

        assertFalse(gate.setExplicitEnabled(false))
        assertFalse(gate.isCaptureAllowed)
        assertFalse(gate.acquireConsumer())
        assertFalse(gate.isCaptureAllowed)

        assertTrue(gate.setExplicitEnabled(true))
        assertTrue(gate.isCaptureAllowed)
    }

    @Test
    fun throttleAppliesOnlyToUnforcedDirtyFramesWhenReady() {
        val gate = BackdropCaptureGate(minCaptureIntervalNanos = 16_000_000L)

        // Never throttles before first capture or on forced geometry/initial frames.
        assertEquals(0L, gate.remainingThrottleNanos(nowNanos = 100L, ready = false, forced = false))
        assertEquals(0L, gate.remainingThrottleNanos(nowNanos = 100L, ready = true, forced = false))

        gate.markRecorded(1_000_000L)
        assertEquals(11_000_000L, gate.remainingThrottleNanos(nowNanos = 6_000_000L, ready = true, forced = false))
        assertEquals(0L, gate.remainingThrottleNanos(nowNanos = 6_000_000L, ready = true, forced = true))
        assertEquals(0L, gate.remainingThrottleNanos(nowNanos = 17_000_000L, ready = true, forced = false))

        gate.reset()
        assertEquals(0L, gate.remainingThrottleNanos(nowNanos = 17_000_000L, ready = true, forced = false))
    }
}
