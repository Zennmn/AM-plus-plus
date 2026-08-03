package dev.amenhancer.module.hook

import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Ready-late I2 re-entry ledger. When PlayerLyricsViewFragment.I2 installs a
 * valid native pointer whose custom replacement is still preparing, the I2
 * hook records the exact fragment instance and Apple Music ID here. Once the
 * background session publishes the replacement pointer and this ledger is
 * invoked, every still-live waiting entry for that ID is consumed and I2 is
 * re-entered with the ready replacement — but only while the same fragment
 * is still usable, still reports the same Apple Music ID, and the
 * replacement is still ready.
 *
 * The ledger is keyed by fragment identity, never equals, so a replacement
 * fragment with equal state can never be confused with the recorded one.
 * Keys hold fragments weakly and cleared entries are swept on every access,
 * so a dead fragment cannot keep an entry alive forever and the ledger can
 * never grow without limit. Every entry is consumed exactly once before
 * re-entry, so duplicate publish callbacks cannot loop. [recordMiss],
 * [dismiss] and [onReplacementPublished] are safe to call from any thread;
 * every gate and the re-entry itself fail open, leaving the native pointer
 * installed.
 */
internal class CustomLyricsReadyReapply(
    private val installMethod: Method,
    private val seam: CurrentItemIdentitySeam,
    private val readyReplacementFor: (Long) -> Any?,
    private val isFragmentUsable: (Any) -> Boolean,
    private val logger: (String) -> Unit,
) {
    /**
     * Strongly holds only the weak key wrappers, never the fragment referents.
     * Matching is explicit `===` identity rather than equals/hashCode.
     */
    private val pending = mutableListOf<PendingMiss>()

    /**
     * I2 hot path: remembers a fragment whose replacement was still preparing.
     * Pure in-memory map write — no IO, no native parse. A newer I2 entry for
     * the same fragment supersedes the older one.
     */
    fun recordMiss(fragment: Any, appleMusicId: Long) {
        synchronized(pending) {
            sweepCleared()
            pending.firstOrNull { it.key.get() === fragment }?.let { existing ->
                existing.appleMusicId = appleMusicId
                return@synchronized
            }
            if (pending.size >= MAX_PENDING) pending.removeAt(0)
            pending += PendingMiss(FragmentKey(fragment), appleMusicId)
        }
    }

    /**
     * Drops the pending ready-late entry for a fragment whose I2 call already
     * received its ready replacement, so a posted publish callback cannot
     * double-install the same pointer.
     */
    fun dismiss(fragment: Any) {
        synchronized(pending) {
            sweepCleared()
            pending.removeAll { it.key.get() === fragment }
        }
    }

    /**
     * Publish callback. Consumes every waiting fragment for the id before any
     * re-entry, so failures or duplicate publishes cannot loop, then
     * re-enters I2 for each fragment that still passes every gate; otherwise
     * the fragment is dropped and the native pointer stays.
     */
    fun onReplacementPublished(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        val waiting = synchronized(pending) {
            sweepCleared()
            val matches = mutableListOf<Any>()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.appleMusicId == appleMusicId) {
                    entry.key.get()?.let(matches::add)
                    iterator.remove()
                }
            }
            matches
        }
        for (fragment in waiting) {
            try {
                if (!isFragmentUsable(fragment)) continue
                if (seam.currentItemAdamIdOf(fragment) != appleMusicId) continue
                val replacement = readyReplacementFor(appleMusicId) ?: continue
                installMethod.invoke(fragment, replacement)
            } catch (error: Throwable) {
                logger("custom lyrics ready-late re-entry failed: $error")
            }
        }
    }

    /** Removes entries whose fragment has been collected. Must hold [pending]. */
    private fun sweepCleared() {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.get() == null) {
                iterator.remove()
            }
        }
    }

    private data class PendingMiss(
        val key: FragmentKey,
        var appleMusicId: Long,
    )

    /**
     * The list owns this wrapper, while the wrapper owns only a weak reference
     * to the Fragment. This avoids the weak-key-of-a-weak-key problem that
     * would let a WeakHashMap discard the wrapper before the Fragment itself.
     */
    private class FragmentKey(referent: Any) : WeakReference<Any>(referent)

    private companion object {
        const val MAX_PENDING = 64
    }
}

/**
 * A ready-late candidate is recorded whenever the custom replacement is not
 * ready yet. The original pointer may be null for a song without native
 * lyrics; the ledger applies the same identity and lifecycle gates to both
 * paths.
 */
internal fun shouldRecordReadyLateMiss(original: Any?, replacement: Any?): Boolean =
    replacement == null

/**
 * Fragment usability predicate for ready-late re-entry, backed by the
 * platform's public `isAdded()` when the I2 fragment hierarchy exposes it.
 * The fragment is treated as usable when the platform method is unavailable,
 * so a missing lifecycle surface fails open instead of blocking re-entry.
 */
internal fun fragmentIsAddedPredicate(fragmentClass: Class<*>): (Any) -> Boolean {
    val isAdded = runCatching {
        fragmentClass.getMethod("isAdded")
            .takeIf { method ->
                method.parameterCount == 0 && method.returnType == java.lang.Boolean.TYPE
            }
            ?.apply { isAccessible = true }
    }.getOrNull()
    return { fragment ->
        if (isAdded == null) {
            true
        } else {
            runCatching { isAdded.invoke(fragment) as? Boolean }.getOrNull() ?: true
        }
    }
}
