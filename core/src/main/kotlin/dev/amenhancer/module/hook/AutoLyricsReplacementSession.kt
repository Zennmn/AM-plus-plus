// The app runtime has a same-package Kotlin file with the same name.
@file:kotlin.jvm.JvmName("AutoLyricsReplacementCoreKt")

package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricsPlaybackRuntime
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException


/** A validated candidate returned by one of the automatic lyric sources. */
data class AutoLyricsCandidate(
    val source: String,
    val ttml: String,
    val displayName: String? = null,
)

/** Selects remote providers to query; AM_NATIVE itself is handled by the host. */
fun automaticLyricsLookupSources(
    settings: ModuleSettings,
    nativeAvailable: Boolean,
    metadata: TtmlDocumentMetadata?,
): List<String> {
    if (!settings.customLyricsEnabled || !settings.automaticLyricsEnabled) return emptyList()
    val order = ModuleSettings.normalizeAutomaticLyricsSourceOrder(settings.automaticLyricsSourceOrder)
    val thirdParty = order.filterNot { it == CustomLyricsSources.AM_NATIVE }
    // Source position never relaxes the existing native timing/translation policy.
    // Unobserved live native pointers retain the same conservative handling.
    val nativeMeetsPolicy = nativeAvailable &&
        metadata?.timingMode != TtmlTimingMode.NON_WORD &&
        metadata?.needsTranslationFallback != true
    if (!nativeMeetsPolicy) return thirdParty
    return order.takeWhile { it != CustomLyricsSources.AM_NATIVE }
}

/** Persistent provider cache seam; candidate reads and writes run in the prepare worker. */
interface AutoLyricsCache {
    /** Returns only the stored candidate for [source], never another provider's most recent entry. */
    fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate?
    fun generation(): Long
    fun writeCandidateIfCurrent(
        appleMusicId: Long,
        candidate: AutoLyricsCandidate,
        generation: Long,
    ): Boolean
}

/** Target-process wiring for the opt-in automatic resolver. */
data class AutoLyricsRuntime(
    val resolver: AutoLyricsSourceResolver,
    val cache: AutoLyricsCache,
    val executor: Executor,
    val playback: LyricsPlaybackRuntime,
    val refreshSong: ((Long, () -> Boolean) -> dev.amenhancer.module.model.CustomLyricsEntry?)? = null,
    val refreshExecutor: Executor? = null,
    val closeRefresh: () -> Unit = {},
    val llm: LlmLyricsRuntime? = null,
)

/**
 * Prepares automatic replacements off-hook. The I2 path only checks the
 * bounded native-pointer cache and queues this session when it is cold.
 */
class AutoLyricsReplacementSession(
    private val fetchCandidate: (Long, String) -> AutoLyricsCandidate?,
    private val cache: AutoLyricsCache,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val isAllowed: (Long) -> Boolean = { true },
    private val executor: Executor,
    private val logger: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val onFreshCandidate: ((Long) -> Unit)? = null,
    private val onLookupMiss: ((Long) -> Unit)? = null,
) {
    private val pointers = object : LinkedHashMap<Long, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    private val lock = Any()
    private val pending = mutableMapOf<Long, Long>()
    private val failedUntil = mutableMapOf<Long, Long>()
    private val sourcePlans = mutableMapOf<Long, List<String>>()
    private val currentRequests = mutableMapOf<Long, Long>()
    private val candidates = mutableMapOf<Long, AutoLyricsCandidate>()
    private val lookupMisses = mutableMapOf<Long, Long>()
    private var nextRequestStamp = 0L
    private var activeSongKnown = false
    private var activeAppleMusicId: Long? = null

    /** Invalidates in-flight results and native pointers only when playback changes. */
    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        val sameSong = activeSongKnown == (appleMusicId != null) &&
            activeAppleMusicId == appleMusicId
        if (!sameSong) {
            activeSongKnown = appleMusicId != null
            activeAppleMusicId = appleMusicId
            pending.clear()
            failedUntil.clear()
            sourcePlans.clear()
            currentRequests.clear()
            lookupMisses.clear()
            candidates.clear()
            synchronized(pointers) {
                pointers.clear()
            }
        }
    }

    /** Replaces one song's lookup plan without disturbing work for other songs. */
    fun configureSources(appleMusicId: Long, sources: List<String>) {
        if (appleMusicId <= 0L) return
        val plan = sources.toList()
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return@synchronized
            if (sourcePlans[appleMusicId] == plan) return@synchronized
            sourcePlans[appleMusicId] = plan
            invalidateSongLocked(appleMusicId)
            synchronized(pointers) {
                pointers.remove(appleMusicId)
            }
        }
    }

    /** Loads a changed cache body for the displayed provider without dropping the live lyrics. */
    fun refreshCached(appleMusicId: Long, source: String) {
        if (appleMusicId > 0L && isAllowed(appleMusicId)) request(appleMusicId, refreshSource = source)
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isCurrentSong(appleMusicId) || !isAllowed(appleMusicId)) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId)
        return readyReplacementFor(appleMusicId)
    }

    fun ensureRequested(appleMusicId: Long) {
        if (appleMusicId > 0L && isCurrentSong(appleMusicId) && isAllowed(appleMusicId)) {
            request(appleMusicId)
        }
    }

    /** True only while the active request's source plan has produced no usable candidate. */
    fun hasLookupMiss(appleMusicId: Long): Boolean = synchronized(lock) {
        if (appleMusicId <= 0L || !isCurrentSongLocked(appleMusicId) || !isAllowed(appleMusicId)) {
            false
        } else {
            val plan = sourcePlans[appleMusicId]
            plan != null && plan.isEmpty() ||
                currentRequests[appleMusicId]?.let { lookupMisses[appleMusicId] == it } == true
        }
    }

    fun hasPendingLookup(appleMusicId: Long): Boolean = synchronized(lock) {
        val stamp = pending[appleMusicId]
        stamp != null && isCurrentRequestLocked(appleMusicId, stamp) && lookupMisses[appleMusicId] != stamp
    }

    fun readyReplacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isAllowed(appleMusicId)) return null
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return null
            synchronized(pointers) {
                val pointer = pointers[appleMusicId] ?: return null
                if (runCatching { isAlive(pointer) }.getOrDefault(false)) return pointer
                pointers.remove(appleMusicId)
            }
        }
        return null
    }

    fun candidateFor(appleMusicId: Long): AutoLyricsCandidate? = synchronized(lock) {
        candidates[appleMusicId].takeIf { isCurrentSongLocked(appleMusicId) }
    }

    /** Retire old network work after a cache clear without blanking displayed lyrics. */
    fun cancelPending(appleMusicId: Long) = synchronized(lock) {
        currentRequests.remove(appleMusicId)
        pending.remove(appleMusicId)
        failedUntil.remove(appleMusicId)
    }

    private fun request(appleMusicId: Long, refreshSource: String? = null) {
        var lookupMissStamp: Long? = null
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return
            if (pending[appleMusicId] != null) return
            if (refreshSource != null) {
                if (candidates[appleMusicId]?.source != refreshSource) return
            } else {
                synchronized(pointers) {
                    if (appleMusicId in pointers) return
                }
            }
            val retryAt = failedUntil[appleMusicId] ?: 0L
            if (retryAt > nowMs()) return
            val requestGeneration = ++nextRequestStamp
            currentRequests[appleMusicId] = requestGeneration
            val sources = sourcePlans[appleMusicId].orEmpty()
            if (refreshSource == null && sources.isEmpty()) {
                lookupMisses[appleMusicId] = requestGeneration
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
                lookupMissStamp = requestGeneration
            } else {
                lookupMisses.remove(appleMusicId)
                pending[appleMusicId] = requestGeneration
                val cacheGeneration = cache.generation()
                try {
                    executor.execute { prepare(appleMusicId, requestGeneration, sources, cacheGeneration, refreshSource) }
                } catch (_: RejectedExecutionException) {
                    if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
                    logger("automatic lyrics prepare was rejected for $appleMusicId")
                }
            }
        }
        lookupMissStamp?.let { notifyLookupMissIfCurrent(appleMusicId, it) }
    }

    private fun prepare(
        appleMusicId: Long,
        requestGeneration: Long,
        sources: List<String>,
        cacheGeneration: Long,
        refreshSource: String?,
    ) {
        var lookupMissed = false
        fun current() = isCurrentRequest(appleMusicId, requestGeneration) &&
            cache.generation() == cacheGeneration
        try {
            if (!current()) return
            if (refreshSource != null) {
                val candidate = runCatching { cache.readCandidate(appleMusicId, refreshSource) }
                    .getOrNull()?.takeIf { it.source == refreshSource } ?: return
                if (candidate.ttml == candidateFor(appleMusicId)?.ttml || !current()) return
                val updated = preparePointer(candidate, appleMusicId, requestGeneration, cacheGeneration)
                if (updated != null && current()) {
                    onReplacementPublished?.invoke(appleMusicId)
                }
                return
            }

            val requestedSources = sources.filterNot { it == CustomLyricsSources.AM_NATIVE }
            if (requestedSources.isEmpty()) {
                lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            var fresh = false
            var candidate: AutoLyricsCandidate? = null
            for (source in requestedSources) {
                if (!current()) return
                candidate = runCatching { cache.readCandidate(appleMusicId, source) }.getOrNull()?.takeIf {
                    it.source == source && TtmlInputPolicy.isAcceptable(it.ttml) && TtmlTimingPolicy.isWord(it.ttml)
                }
                if (candidate != null) break
                if (!current()) return
                candidate = runCatching { fetchCandidate(appleMusicId, source) }.getOrNull()
                if (candidate == null) continue
                fresh = true
                break
            }
            if (candidate == null && current()) {
                // Keep the provider-less legacy slot as a cold fallback for a configured remote plan.
                candidate = runCatching {
                    cache.readCandidate(appleMusicId, CustomLyricsSources.AUTO_CACHE)
                }.getOrNull()
            }
            if (!current()) return
            val ready = candidate ?: run {
                lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            val pointer = preparePointer(ready, appleMusicId, requestGeneration, cacheGeneration)
            if (pointer == null) {
                if (current()) lookupMissed = markLookupMissIfCurrent(appleMusicId, requestGeneration)
                return
            }
            if (fresh && current()) {
                onFreshCandidate?.invoke(appleMusicId)
                runCatching { cache.writeCandidateIfCurrent(appleMusicId, ready, cacheGeneration) }
            }
            if (current()) {
                onReplacementPublished?.invoke(appleMusicId)
                synchronized(lock) {
                    if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                        failedUntil.remove(appleMusicId)
                        lookupMisses.remove(appleMusicId)
                    }
                }
            } else {
                removePointerIf(appleMusicId, pointer)
            }
        } finally {
            synchronized(lock) {
                if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
            }
            if (lookupMissed) notifyLookupMissIfCurrent(appleMusicId, requestGeneration)
        }
    }

    private fun preparePointer(
        candidate: AutoLyricsCandidate,
        appleMusicId: Long,
        requestGeneration: Long,
        cacheGeneration: Long,
    ): Any? {
        val ttml = candidate.ttml
        val source = candidate.source
        if (!TtmlTimingPolicy.isWord(ttml)) {
            logger("automatic lyrics candidate rejected as non-word source=$source id=$appleMusicId")
            return null
        }
        val pointer = runCatching { parseTtml(ttml) }.getOrNull() ?: return null
        if (!isPrepared(pointer, appleMusicId) && !runCatching {
                bindAdamId(pointer, appleMusicId)
            }.getOrDefault(false)
        ) {
            logger("automatic lyrics pointer binding failed source=$source id=$appleMusicId")
            return null
        }
        if (!isPrepared(pointer, appleMusicId)) {
            logger("automatic lyrics pointer was unusable source=$source id=$appleMusicId")
            return null
        }
        val accepted = synchronized(lock) {
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration) || cache.generation() != cacheGeneration) {
                false
            } else {
                candidates[appleMusicId] = candidate
                synchronized(pointers) { pointers[appleMusicId] = pointer }
                true
            }
        }
        return pointer.takeIf { accepted }
    }

    private fun markLookupMissIfCurrent(appleMusicId: Long, requestGeneration: Long): Boolean =
        synchronized(lock) {
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                false
            } else {
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
                lookupMisses[appleMusicId] = requestGeneration
                true
            }
        }

    private fun notifyLookupMissIfCurrent(appleMusicId: Long, requestGeneration: Long) {
        val current = synchronized(lock) {
            isCurrentRequestLocked(appleMusicId, requestGeneration) &&
                lookupMisses[appleMusicId] == requestGeneration
        }
        if (current) runCatching { onLookupMiss?.invoke(appleMusicId) }
    }

    private fun isPrepared(pointer: Any?, appleMusicId: Long): Boolean = runCatching {
        pointer != null && verifyPtr(pointer) && readAdamId(pointer) == appleMusicId
    }.getOrDefault(false)

    private fun isCurrentSong(appleMusicId: Long): Boolean = synchronized(lock) {
        isCurrentSongLocked(appleMusicId)
    }

    private fun isCurrentSongLocked(appleMusicId: Long): Boolean =
        !activeSongKnown || activeAppleMusicId == appleMusicId

    private fun isCurrentRequest(appleMusicId: Long, requestGeneration: Long): Boolean =
        synchronized(lock) { isCurrentRequestLocked(appleMusicId, requestGeneration) }

    private fun isCurrentRequestLocked(appleMusicId: Long, requestGeneration: Long): Boolean =
        currentRequests[appleMusicId] == requestGeneration &&
            isCurrentSongLocked(appleMusicId) &&
            isAllowed(appleMusicId)

    private fun invalidateSongLocked(appleMusicId: Long) {
        currentRequests.remove(appleMusicId)
        pending.remove(appleMusicId)
        failedUntil.remove(appleMusicId)
        lookupMisses.remove(appleMusicId)
        candidates.remove(appleMusicId)
    }

    private fun removePointerIf(appleMusicId: Long, pointer: Any?) {
        if (pointer == null) return
        synchronized(pointers) {
            if (pointers[appleMusicId] === pointer) pointers.remove(appleMusicId)
        }
    }

    private companion object {
        const val CACHE_CAPACITY = 32
        const val DEFAULT_RETRY_COOLDOWN_MS = 30_000L
    }
}
