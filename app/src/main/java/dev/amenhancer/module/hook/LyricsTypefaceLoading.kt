package dev.amenhancer.module.hook

import java.util.Collections
import java.util.WeakHashMap

/**
 * Pure lifecycle of the one-shot background lyrics-font load.
 *
 * The value type is generic so JVM tests can drive every transition without
 * Android classes; the session uses [LyricsTypefaceLoadController] with
 * [android.graphics.Typeface] as its value. All state is guarded by the
 * controller's own lock, so the background loader and the main-thread apply
 * path observe one consistent state.
 */
internal class LyricsTypefaceLoadController<T> {
    enum class Phase { IDLE, LOADING, READY, FAILED }

    private var phase = Phase.IDLE
    private var value: T? = null
    private var failure: String? = null

    /** IDLE -> LOADING; returns false when the load already started or settled. */
    @Synchronized
    fun start(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.LOADING
        return true
    }

    @Synchronized
    fun succeed(value: T) {
        this.value = value
        this.failure = null
        phase = Phase.READY
    }

    /** Terminal; a failed load keeps the original font (fail-open). */
    @Synchronized
    fun fail(message: String) {
        this.failure = message
        this.value = null
        phase = Phase.FAILED
    }

    @Synchronized
    fun phase(): Phase = phase

    @Synchronized
    fun readyValue(): T? = value

    @Synchronized
    fun failureMessage(): String? = failure
}

/**
 * Merges bursty re-apply triggers (recycler child attaches, lyrics-row layout
 * changes) into at most one pending main-thread application per observed
 * root, so the session never stacks an unbounded number of runnables.
 *
 * Keys are the observed views; the map holds them weakly so rows that leave
 * the recycler pool cannot leak through the gate.
 */
internal class DelayedApplyGate {
    private val pending = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** True when the caller should schedule a re-apply; false while one is pending. */
    fun tryAcquire(key: Any): Boolean = synchronized(pending) {
        if (pending.containsKey(key)) {
            false
        } else {
            pending[key] = true
            true
        }
    }

    /** Must be called when the scheduled re-apply runs. */
    fun release(key: Any) {
        synchronized(pending) {
            pending.remove(key)
        }
    }
}
