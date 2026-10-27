package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import java.util.concurrent.Executor

/** Immutable copied input; no host pointer, ViewModel or credentials are written to disk here. */
data class LlmLyricsRequest(
    val appleMusicId: Long,
    val originalTtml: String,
    val config: LlmLyricsConfig,
    val title: String? = null,
    val artist: String? = null,
)

/** App-owned network/storage wiring consumed through the existing automatic lyrics capability. */
data class LlmLyricsRuntime(
    val configuration: () -> LlmLyricsConfig?,
    val translate: (LlmLyricsRequest, isCancelled: () -> Boolean) -> String?,
    val executor: Executor,
    val close: () -> Unit = {},
    /** Persist only after the current host has accepted the generated TTML. */
    val onPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
)

/**
 * Native lyric translation for one playback visit. The host must confirm native selection
 * before either disk cache or network is touched. Native pointers are retained
 * only for the current visit; each subsequent visit repeats source selection.
 */
class LlmLyricsReplacementSession(
    private val runtime: LlmLyricsRuntime,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val isAllowed: (Long) -> Boolean,
    private val onReplacementPublished: (Long) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var activeId: Long? = null
    private var generation = 0L
    private var nativeSelected = false
    private var original: String? = null
    private var configuration: LlmLyricsConfig? = null
    private var attempted = false
    private var ready: Any? = null
    private var closed = false

    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        if (closed || activeId == appleMusicId) return@synchronized
        activeId = appleMusicId
        nativeSelected = false
        invalidateLocked()
    }

    /** Opens the LLM path for the native document selected during this host selection pass. */
    fun onNativeSelected(appleMusicId: Long) = synchronized(lock) {
        if (!closed && activeId == appleMusicId) nativeSelected = true
    }

    /** Compatibility entry for callers that have not moved to explicit native selection. */
    fun onSourcesMissed(appleMusicId: Long) {
        val wake = synchronized(lock) {
            if (closed || activeId != appleMusicId || nativeSelected) false
            else { nativeSelected = true; true }
        }
        if (wake) onReplacementPublished(appleMusicId)
    }

    /** A manual or third-party result always revokes a pending or prepared LLM result. */
    fun onHigherPriorityResult(appleMusicId: Long) = synchronized(lock) {
        if (activeId == appleMusicId && (nativeSelected || original != null || ready != null)) {
            nativeSelected = false
            invalidateLocked()
        }
    }

    /** Missing exact native input revokes old work without inventing a new source outcome. */
    fun onNativeInputUnavailable(appleMusicId: Long) {
        synchronized(lock) {
            if (activeId == appleMusicId) {
                nativeSelected = false
                invalidateLocked()
            }
        }
    }

    /** The host supplies raw TTML associated with its verified original pointer. */
    fun replacementFor(
        appleMusicId: Long,
        originalTtml: String,
        title: String? = null,
        artist: String? = null,
    ): Any? {
        val config = runtime.configuration()
        val allowed = config?.isConfigured == true && isAllowed(appleMusicId)
        synchronized(lock) {
            if (closed || appleMusicId <= 0L || activeId != appleMusicId) return null
            if (!allowed) {
                if (original != null || ready != null) invalidateLocked()
                return null
            }
            if (original != originalTtml || configuration != config) {
                invalidateLocked()
                original = originalTtml
                configuration = config
            }
            if (!nativeSelected) return null
            ready?.let { pointer ->
                if (runCatching { isAlive(pointer) }.getOrDefault(false)) return pointer
                // Rebuild a released native wrapper from the independent raw cache.
                ready = null
                attempted = false
            }
            if (attempted) return null
            attempted = true
            val stamp = generation
            val request = LlmLyricsRequest(appleMusicId, originalTtml, config, title, artist)
            runCatching { runtime.executor.execute { prepare(request, stamp) } }
            return null
        }
    }

    private fun prepare(request: LlmLyricsRequest, stamp: Long) {
        try {
            if (!isCurrent(request, stamp)) return
            val ttml = runtime.translate(request) { !isCurrent(request, stamp) } ?: return
            if (!isCurrent(request, stamp)) return
            val pointer = parseTtml(ttml) ?: return
            if (!isCurrent(request, stamp)) return
            if (readAdamId(pointer) != request.appleMusicId && !bindAdamId(pointer, request.appleMusicId)) return
            if (!verifyPtr(pointer) || readAdamId(pointer) != request.appleMusicId) return
            if (!isCurrent(request, stamp)) return
            runCatching { runtime.onPrepared(request, ttml) }
            if (!isCurrent(request, stamp)) return
            synchronized(lock) {
                if (!matchesLocked(request, stamp)) return
                ready = pointer
            }
            if (isCurrent(request, stamp)) onReplacementPublished(request.appleMusicId)
        } catch (_: Exception) {
            // A native binding failure must not reach the shared worker thread.
        }
    }

    private fun isCurrent(request: LlmLyricsRequest, stamp: Long): Boolean =
        synchronized(lock) { matchesLocked(request, stamp) } &&
            isAllowed(request.appleMusicId) && runtime.configuration() == request.config

    private fun matchesLocked(request: LlmLyricsRequest, stamp: Long): Boolean =
        !closed && generation == stamp && nativeSelected && activeId == request.appleMusicId &&
            original == request.originalTtml && configuration == request.config

    private fun invalidateLocked() {
        generation++
        original = null
        configuration = null
        ready = null
        attempted = false
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            nativeSelected = false
            activeId = null
            invalidateLocked()
        }
        runtime.close()
    }
}
