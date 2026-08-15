package dev.amenhancer.module.hook

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Bounded hand-off state for the artist id attached to one player action
 * sheet.  The sheet entry point and its response callback are not guaranteed
 * to run back-to-back (or on the same thread), so ids are retained until the
 * response consumes them or the short TTL expires.  A new sheet deliberately
 * does not clear older entries: a concurrent or delayed response still owns
 * the id it was given.
 *
 * The state is intentionally a tiny synchronized map of bounded request-token
 * queues rather than a process-lifetime concurrent set.  Every public
 * operation performs bounded expiry work and [consume] removes one token under
 * the same lock as the lookup, making consumption atomic even when response
 * maps are delivered in parallel or the same artist is opened twice.
 */
internal class ActionSheetArtistIdTracker(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, ArrayDeque<Long>>(maxEntries)
    private var tokenCount = 0

    /** Records one action-sheet request token. Blank ids are ignored. */
    fun record(id: String?) {
        val normalized = normalize(id) ?: return
        synchronized(lock) {
            val now = clock()
            evictExpiredLocked(now)
            entries.getOrPut(normalized) { ArrayDeque() }.addLast(now)
            tokenCount += 1
            while (tokenCount > maxEntries) evictOldestTokenLocked()
        }
    }

    /**
     * Atomically checks and consumes [id]. A consumed id cannot be reused by a
     * later response, while a different id remains available for its own
     * delayed/concurrent response.
     */
    fun consume(id: String?): Boolean {
        val normalized = normalize(id) ?: return false
        return synchronized(lock) {
            val now = clock()
            evictExpiredLocked(now)
            val timestamps = entries[normalized]
            if (timestamps == null) {
                false
            } else {
                timestamps.removeFirst()
                tokenCount -= 1
                if (timestamps.isEmpty()) entries.remove(normalized)
                true
            }
        }
    }

    /** Current non-expired request-token count, bounded by [maxEntries]. */
    val size: Int
        get() = synchronized(lock) {
            val now = clock()
            evictExpiredLocked(now)
            tokenCount
        }

    private fun evictExpiredLocked(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val timestamps = iterator.next().value
            while (timestamps.isNotEmpty()) {
                val recordedAt = timestamps.first
                if (now < recordedAt || now - recordedAt < ttlMillis) break
                timestamps.removeFirst()
                tokenCount -= 1
            }
            if (timestamps.isEmpty()) iterator.remove()
        }
    }

    private fun evictOldestTokenLocked() {
        var oldestId: String? = null
        var oldestAt = Long.MAX_VALUE
        entries.forEach { (id, timestamps) ->
            val recordedAt = timestamps.firstOrNull() ?: return@forEach
            if (recordedAt < oldestAt) {
                oldestAt = recordedAt
                oldestId = id
            }
        }
        val id = oldestId ?: return
        val timestamps = entries[id] ?: return
        timestamps.removeFirst()
        tokenCount -= 1
        if (timestamps.isEmpty()) entries.remove(id)
    }

    private fun normalize(id: String?): String? = id
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
