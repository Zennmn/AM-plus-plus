package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsTrackComposer
import java.util.concurrent.Executor

/** Immutable source copied before background work; never retains a host pointer. */
data class LlmLyricsRequest(
    val appleMusicId: Long,
    val originalTtml: String,
    val config: LlmLyricsConfig,
    val title: String? = null,
    val artist: String? = null,
)

/** App-owned requests and storage. Both features use the same API configuration. */
data class LlmLyricsRuntime(
    val configuration: () -> LlmLyricsConfig?,
    val translate: (LlmLyricsRequest, isCancelled: () -> Boolean) -> String?,
    val executor: Executor,
    val close: () -> Unit = {},
    /** Save the individual result only after its composed document was accepted. */
    val onPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
    val transliterate: ((LlmLyricsRequest, isCancelled: () -> Boolean) -> String?)? = null,
    val translationEnabled: () -> Boolean = { true },
    val transliterationEnabled: () -> Boolean = { false },
    val onTransliterationPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
    val discardPending: () -> Unit = {},
)

/**
 * Enhances the exact selected document. The host permits translation only for
 * native input; transliteration also accepts selected custom lyrics. Requests
 * run independently, while composition and native publication are serialized.
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
    private val isTranslationAllowed: (Long) -> Boolean = isAllowed,
) : AutoCloseable {
    private val lock = Any()
    private val compositionLock = Any()
    private var activeId: Long? = null
    private var generation = 0L
    private var selected = false
    private var original: String? = null
    private var configuration: LlmLyricsConfig? = null
    private var translationAllowed = false
    private var transliterationAllowed = false
    private var translationAttempted = false
    private var transliterationAttempted = false
    private var translated: String? = null
    private var transliterated: String? = null
    private var ready: Any? = null
    private var closed = false

    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        if (closed || activeId == appleMusicId) return@synchronized
        activeId = appleMusicId
        selected = false
        invalidateLocked()
    }

    fun onSelected(appleMusicId: Long) = synchronized(lock) {
        if (!closed && activeId == appleMusicId) selected = true
    }

    fun onNativeSelected(appleMusicId: Long) = onSelected(appleMusicId)

    /** Compatibility entry for source-selection callers. */
    fun onSourcesMissed(appleMusicId: Long) {
        val wake = synchronized(lock) {
            if (closed || activeId != appleMusicId || selected) false
            else { selected = true; true }
        }
        if (wake) onReplacementPublished(appleMusicId)
    }

    /** The selected source is unavailable or no longer eligible. */
    fun onHigherPriorityResult(appleMusicId: Long) = synchronized(lock) {
        if (activeId == appleMusicId && (selected || original != null || ready != null)) {
            selected = false
            invalidateLocked()
        }
    }

    fun onNativeInputUnavailable(appleMusicId: Long) = onHigherPriorityResult(appleMusicId)

    fun replacementFor(
        appleMusicId: Long,
        originalTtml: String,
        title: String? = null,
        artist: String? = null,
        allowTranslation: Boolean = true,
    ): Any? {
        val config = runtime.configuration()
        val wantTranslation = allowTranslation && runtime.translationEnabled() && isTranslationAllowed(appleMusicId)
        val wantTransliteration = runtime.transliterate != null && runtime.transliterationEnabled()
        val allowed = config?.isConfigured == true && isAllowed(appleMusicId) &&
            (wantTranslation || wantTransliteration)
        synchronized(lock) {
            if (closed || appleMusicId <= 0L || activeId != appleMusicId) return null
            if (!allowed) {
                if (original != null || ready != null) invalidateLocked()
                return null
            }
            if (!selected) return null
            if (original != originalTtml || configuration != config ||
                translationAllowed != wantTranslation || transliterationAllowed != wantTransliteration ||
                (ready != null && !runCatching { isAlive(ready) }.getOrDefault(false))
            ) {
                invalidateLocked()
                original = originalTtml
                configuration = config
                translationAllowed = wantTranslation
                transliterationAllowed = wantTransliteration
            }
            val request = LlmLyricsRequest(appleMusicId, originalTtml, config!!, title, artist)
            val stamp = generation
            if (wantTranslation && !translationAttempted) {
                translationAttempted = true
                enqueue(request, stamp, false)
            }
            if (wantTransliteration && !transliterationAttempted) {
                transliterationAttempted = true
                enqueue(request, stamp, true)
            }
            return ready
        }
    }

    private fun enqueue(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean) {
        try {
            runtime.executor.execute { prepare(request, stamp, pronunciation) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (pronunciation) transliterationAttempted = false else translationAttempted = false
        }
    }

    private fun prepare(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean) {
        try {
            if (!isCurrent(request, stamp, pronunciation)) return
            val cancelled = { !isCurrent(request, stamp, pronunciation) }
            val generated = if (pronunciation) runtime.transliterate?.invoke(request, cancelled)
                else runtime.translate(request, cancelled)
            if (generated == null || cancelled()) return
            // A late answer includes the already accepted sibling track.
            synchronized(compositionLock) {
                if (cancelled()) return
                val tracks = synchronized(lock) {
                    (if (pronunciation) translated else generated) to
                        (if (pronunciation) generated else transliterated)
                }
                val combined = LlmLyricsTrackComposer.compose(request.originalTtml, tracks.first, tracks.second)
                    ?: return
                val pointer = parseTtml(combined) ?: return
                if (cancelled()) return
                if (readAdamId(pointer) != request.appleMusicId && !bindAdamId(pointer, request.appleMusicId)) return
                if (!verifyPtr(pointer) || readAdamId(pointer) != request.appleMusicId) return
                if (cancelled()) return
                if (pronunciation) runtime.onTransliterationPrepared(request, generated)
                else runtime.onPrepared(request, generated)
                if (cancelled()) return
                synchronized(lock) {
                    if (!matchesLocked(request, stamp)) return
                    translated = tracks.first
                    transliterated = tracks.second
                    ready = pointer
                }
            }
            if (isCurrent(request, stamp, pronunciation)) onReplacementPublished(request.appleMusicId)
        } catch (_: Exception) {
            // Fail this feature only; an accepted sibling result remains usable.
        }
    }

    private fun isCurrent(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean): Boolean =
        synchronized(lock) {
            matchesLocked(request, stamp) && (if (pronunciation) transliterationAllowed else translationAllowed)
        } && isAllowed(request.appleMusicId) && runtime.configuration() == request.config &&
            (if (pronunciation) runtime.transliterationEnabled()
                else runtime.translationEnabled() && isTranslationAllowed(request.appleMusicId))

    private fun matchesLocked(request: LlmLyricsRequest, stamp: Long): Boolean =
        !closed && generation == stamp && selected && activeId == request.appleMusicId &&
            original == request.originalTtml && configuration == request.config

    private fun invalidateLocked() {
        generation++
        runtime.discardPending()
        original = null
        configuration = null
        translationAllowed = false
        transliterationAllowed = false
        translationAttempted = false
        transliterationAttempted = false
        translated = null
        transliterated = null
        ready = null
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            selected = false
            activeId = null
            invalidateLocked()
        }
        runtime.close()
    }
}

