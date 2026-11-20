package dev.amenhancer.backdrop

/**
 * Pure capture-gating state shared by multi-consumer ViewBackdrops.
 *
 * Explicit capture toggles (`setExplicitEnabled`) remain compatible with single-owner
 * callers, while `acquireConsumer`/`releaseConsumer` lets multiple glass surfaces share
 * one backdrop and suspend recording whenever zero surfaces are visible.
 */
class BackdropCaptureGate(val minCaptureIntervalNanos: Long = 0L) {
    init {
        require(minCaptureIntervalNanos >= 0L) {
            "minCaptureIntervalNanos must be non-negative: $minCaptureIntervalNanos"
        }
    }

    private var explicitEnabled = true
    private var consumerTrackingActive = false
    private var consumerCount = 0
    private var lastCaptureNanos = Long.MIN_VALUE

    val isCaptureAllowed: Boolean
        get() = explicitEnabled && (!consumerTrackingActive || consumerCount > 0)

    val activeConsumers: Int
        get() = consumerCount

    /** Returns true only when overall capture transitions from paused to active. */
    fun setExplicitEnabled(enabled: Boolean): Boolean {
        val wasAllowed = isCaptureAllowed
        explicitEnabled = enabled
        return !wasAllowed && isCaptureAllowed
    }

    /** Enables consumer ref-counting and returns true if capture transitioned to active. */
    fun acquireConsumer(): Boolean {
        val wasAllowed = isCaptureAllowed
        consumerTrackingActive = true
        consumerCount++
        return !wasAllowed && isCaptureAllowed
    }

    /** Decrements active consumers and returns true if capture transitioned to paused. */
    fun releaseConsumer(): Boolean {
        val wasAllowed = isCaptureAllowed
        if (consumerCount > 0) consumerCount--
        return wasAllowed && !isCaptureAllowed
    }

    /** Remaining throttle delay in nanos before the next unforced dirty recapture. */
    fun remainingThrottleNanos(nowNanos: Long, ready: Boolean, forced: Boolean): Long {
        if (!ready || forced || minCaptureIntervalNanos <= 0L || lastCaptureNanos == Long.MIN_VALUE) {
            return 0L
        }
        val elapsed = nowNanos - lastCaptureNanos
        if (elapsed < 0L) return 0L
        return (minCaptureIntervalNanos - elapsed).coerceAtLeast(0L)
    }

    fun markRecorded(nowNanos: Long) {
        lastCaptureNanos = nowNanos
    }

    fun reset() {
        lastCaptureNanos = Long.MIN_VALUE
    }
}
