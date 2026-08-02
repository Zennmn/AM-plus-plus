package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves only user-published ID mappings. It intentionally has no metadata
 * matching, network, or refresh behavior in the hook path. Remote
 * files and native parsing are prepared off-hook; I2 only consumes a cache.
 */
internal class CustomLyricsReplacementSession(
    private val manifestProvider: () -> CustomLyricsManifest,
    private val readTtml: (CustomLyricsEntry) -> String?,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val executor: Executor,
    private val logger: (String) -> Unit,
) {
    private data class CacheKey(
        val appleMusicId: Long,
        val fileId: String,
        val sha256: String,
    )

    private val cache = object : LinkedHashMap<CacheKey, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    @Volatile
    private var entriesById: Map<Long, CustomLyricsEntry> = emptyMap()
    private val preloadQueued = AtomicBoolean(false)

    fun start() {
        queuePreload()
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        queuePreload()
        return null
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

    private fun queuePreload() {
        if (!preloadQueued.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    preload()
                } finally {
                    preloadQueued.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            preloadQueued.set(false)
            logger("custom lyrics preloader rejected its task")
        }
    }

    private fun preload() {
        val manifest = runCatching { manifestProvider() }.getOrElse { error ->
            logger("custom lyrics manifest read failed: $error")
            return
        }
        val entries = manifest.entries
            .filter(CustomLyricsEntry::enabled)
            .associateBy(CustomLyricsEntry::appleMusicId)
        entriesById = entries
        val activeKeys = entries.values.mapTo(mutableSetOf()) { entry ->
            CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
        }
        synchronized(cache) {
            cache.keys.retainAll(activeKeys)
        }
        entries.values.forEach(::prepare)
    }

    private fun prepare(entry: CustomLyricsEntry) {
        val key = CacheKey(entry.appleMusicId, entry.fileId, entry.sha256)
        synchronized(cache) {
            cache[key]?.let { cached ->
                if (isPrepared(cached, entry.appleMusicId)) return
                cache.remove(key)
            }
        }
        val ttml = runCatching { readTtml(entry) }.getOrNull() ?: return
        val replacement = runCatching { parseTtml(ttml) }.getOrNull() ?: return
        if (!isPrepared(replacement, entry.appleMusicId) && !bindAdamId(replacement, entry.appleMusicId)) {
            logger("custom lyrics parser returned an unusable SongInfoPtr for ${entry.appleMusicId}")
            return
        }
        if (!isPrepared(replacement, entry.appleMusicId)) {
            logger("custom lyrics SongInfoPtr Adam ID binding failed for ${entry.appleMusicId}")
            return
        }
        synchronized(cache) {
            cache[key] = replacement
        }
    }

    private fun isPrepared(pointer: Any, appleMusicId: Long): Boolean =
        runCatching { verifyPtr(pointer) && readAdamId(pointer) == appleMusicId }.getOrDefault(false)

    private companion object {
        const val CACHE_CAPACITY = 32
    }
}
