package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Pure-Kotlin seam for the lightweight Apple-Music-ID → entry index consumed
 * by [CustomLyricsReplacementSession]. Today the remote-preferences manifest
 * backs it; the unbounded-index task can later swap in a sharded remote-file
 * index without changing the session. The seam never guesses a remote-file
 * format — it only promises an in-memory snapshot of the current mappings.
 */
internal fun interface CustomLyricsIndexProvider {
    /** Loads the current index snapshot, or null when the index is unavailable. */
    fun load(): Map<Long, CustomLyricsEntry>?
}

/**
 * Resolves only user-published ID mappings. It intentionally has no metadata
 * matching, network, or refresh behavior in the hook path. Remote
 * files and native parsing are prepared off-hook; I2 only consumes a cache.
 *
 * The index is loaded off-hook as a lightweight snapshot; lyric bodies and
 * native parsing are prepared per requested Apple Music ID on a single
 * background thread, deduplicated by ID. A miss for an unknown ID triggers
 * one background index refresh to discover mappings published after startup.
 *
 * Every successful prepare publishes its Apple Music ID through
 * [onReplacementPublished] on the preparing thread; callers hop to the main
 * thread when a UI re-entry is needed.
 */
internal class CustomLyricsReplacementSession(
    private val index: CustomLyricsIndexProvider,
    private val readTtml: (CustomLyricsEntry) -> String?,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val executor: Executor,
    private val logger: (String) -> Unit,
) {
    private data class CacheKey(
        val appleMusicId: Long,
        val fileId: String,
        val sha256: String,
    )

    /**
     * Bounded, access-order pointer cache whose capacity is independent of
     * the mapping count; entries that fall out are re-prepared on demand.
     */
    private val cache = object : LinkedHashMap<CacheKey, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    @Volatile
    private var entriesById: Map<Long, CustomLyricsEntry> = emptyMap()
    private val lock = Any()
    private val pendingPrepares = mutableSetOf<Long>()
    private var refreshQueued = false

    /** Queues the initial lightweight index load; never prepares lyric bodies. */
    fun start() {
        synchronized(lock) {
            if (refreshQueued) return
            refreshQueued = true
            enqueueRefresh()
        }
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId, refreshOnUnknown = true)
        return readyReplacementFor(appleMusicId)
    }

    /** Ready cache only; safe for availability predicates and other hot paths. */
    fun readyReplacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L) return null
        val entry = entriesById[appleMusicId]
            ?: return null
        val key = CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
        synchronized(cache) {
            cache[key]?.let { cached ->
                if (runCatching { isAlive(cached) }.getOrDefault(false)) return cached
                cache.remove(key)
            }
        }
        return null
    }

    /** Returns a ready pointer or queues off-hook recovery for a known mapping. */
    fun replacementOrPrepareFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || appleMusicId !in entriesById) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId, refreshOnUnknown = false)
        return readyReplacementFor(appleMusicId)
    }

    /**
     * Deduplicated background request: a known ID queues a single-entry
     * prepare, an unknown ID queues a single index refresh so mappings
     * published after startup can be discovered. An unknown ID arriving
     * while a refresh is already queued is still registered so the in-flight
     * refresh can resolve it; a rejected refresh drops the unknown pending
     * IDs so the same IDs can retry from a later request.
     */
    private fun request(appleMusicId: Long, refreshOnUnknown: Boolean) {
        synchronized(lock) {
            if (appleMusicId in pendingPrepares) return
            val entry = entriesById[appleMusicId]
            when {
                entry != null -> {
                    pendingPrepares += appleMusicId
                    enqueuePrepare(appleMusicId)
                }
                refreshOnUnknown -> {
                    pendingPrepares += appleMusicId
                    if (!refreshQueued) {
                        refreshQueued = true
                        enqueueRefresh()
                    }
                }
            }
        }
    }

    private fun enqueuePrepare(appleMusicId: Long) {
        try {
            executor.execute { prepare(appleMusicId) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { pendingPrepares.remove(appleMusicId) }
            logger("custom lyrics prepare was rejected for $appleMusicId")
        }
    }

    private fun enqueueRefresh() {
        try {
            executor.execute { refreshIndex() }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                refreshQueued = false
                pendingPrepares.retainAll { it in entriesById }
            }
            logger("custom lyrics index refresh was rejected")
        }
    }

    /** Reloads the lightweight index only; never reads lyric bodies or parses. */
    private fun refreshIndex() {
        val loaded = runCatching { index.load() }.getOrElse { error ->
            logger("custom lyrics index read failed: $error")
            synchronized(lock) {
                refreshQueued = false
                pendingPrepares.clear()
            }
            return
        }
        val refreshed = loaded.orEmpty().filterValues(CustomLyricsEntry::enabled)
        val appeared = mutableListOf<Long>()
        synchronized(lock) {
            refreshQueued = false
            entriesById = refreshed
            val activeKeys = refreshed.values.mapTo(mutableSetOf()) { entry ->
                CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
            }
            synchronized(cache) {
                cache.keys.retainAll(activeKeys)
            }
            appeared += pendingPrepares.filter { it in refreshed }
            pendingPrepares.retainAll { it in refreshed }
        }
        appeared.forEach(::prepare)
    }

    private fun prepare(appleMusicId: Long) {
        try {
            val entry = synchronized(lock) { entriesById[appleMusicId] } ?: return
            val key = CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
            synchronized(cache) {
                cache[key]?.let { cached ->
                    if (isPrepared(cached, appleMusicId)) return
                    cache.remove(key)
                }
            }
            val ttml = runCatching { readTtml(entry) }.getOrNull() ?: return
            val replacement = runCatching { parseTtml(ttml) }.getOrNull() ?: return
            if (!isPrepared(replacement, appleMusicId) && !bindAdamId(replacement, appleMusicId)) {
                logger("custom lyrics parser returned an unusable SongInfoPtr for $appleMusicId")
                return
            }
            if (!isPrepared(replacement, appleMusicId)) {
                logger("custom lyrics SongInfoPtr Adam ID binding failed for $appleMusicId")
                return
            }
            synchronized(cache) {
                cache[key] = replacement
            }
            onReplacementPublished?.invoke(appleMusicId)
        } finally {
            synchronized(lock) {
                pendingPrepares.remove(appleMusicId)
            }
        }
    }

    private fun isPrepared(pointer: Any, appleMusicId: Long): Boolean =
        runCatching { verifyPtr(pointer) && readAdamId(pointer) == appleMusicId }.getOrDefault(false)

    private companion object {
        const val CACHE_CAPACITY = 32
    }
}
