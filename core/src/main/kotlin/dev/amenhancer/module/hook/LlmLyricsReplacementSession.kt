package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsStatusSnapshot
import dev.amenhancer.module.lyrics.LlmLyricsTrackComposer
import java.util.concurrent.Executor

/** Immutable source copied before background work; never retains a host pointer. */
data class LlmLyricsRequest(
    val appleMusicId: Long,
    val originalTtml: String,
    val config: LlmLyricsConfig,
    val cacheGeneration: Long,
    val title: String? = null,
    val artist: String? = null,
    /** Bounded, generation-checked UI progress; never carries server response text. */
    val reportStatus: (LlmLyricsPhase) -> Unit = {},
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
    val onStatusChanged: (LlmLyricsStatusSnapshot) -> Unit = {},
    val cacheGeneration: () -> Long = { 0L },
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
    private var translationPhase = LlmLyricsPhase.WAITING
    private var transliterationPhase = LlmLyricsPhase.WAITING
    private var songTitle: String? = null
    private var lastStatus: LlmLyricsStatusSnapshot? = null

    private fun publishStatusLocked(useCurrentPhases: Boolean = selected) {
        val status = LlmLyricsStatusSnapshot(
            appleMusicId = activeId,
            songTitle = songTitle,
            model = configuration?.normalizedModel.orEmpty(),
            configurationKey = configuration?.hashCode(),
            translation = if (useCurrentPhases) translationPhase else LlmLyricsPhase.WAITING,
            transliteration = if (useCurrentPhases) transliterationPhase else LlmLyricsPhase.WAITING,
        )
        if (status == lastStatus) return
        lastStatus = status
        runCatching { runtime.onStatusChanged(status) }
    }

    private fun updatePhase(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean, phase: LlmLyricsPhase) {
        if (!isCurrent(request, stamp, pronunciation)) return
        synchronized(lock) {
            if (!matchesLocked(request, stamp)) return
            if (pronunciation) transliterationPhase = phase else translationPhase = phase
            publishStatusLocked()
        }
    }

    fun onSongChanged(appleMusicId: Long?) = synchronized(lock) {
        if (closed || activeId == appleMusicId) return@synchronized
        activeId = appleMusicId
        selected = false
        invalidateLocked()
        songTitle = null
        publishStatusLocked()
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
            publishStatusLocked()
        }
    }

    fun onNativeInputUnavailable(appleMusicId: Long) = synchronized(lock) {
        if (closed || activeId != appleMusicId) return@synchronized
        invalidateLocked()
        selected = false
        translationPhase = LlmLyricsPhase.UNSUPPORTED
        transliterationPhase = LlmLyricsPhase.UNSUPPORTED
        publishStatusLocked(useCurrentPhases = true)
    }

    /** Retire pending enhancement work while a source choice keeps the current document visible. */
    fun pauseForSourceChange(appleMusicId: Long, originalTtml: String?): Any? = synchronized(lock) {
        if (closed || activeId != appleMusicId) return@synchronized null
        if (selected) {
            generation++
            runtime.discardPending()
            selected = false
            if (translated == null) translationAttempted = false
            if (transliterated == null) transliterationAttempted = false
            publishStatusLocked()
        }
        ready?.takeIf {
            original == originalTtml && configuration == runtime.configuration() &&
                runCatching { isAlive(it) }.getOrDefault(false)
        }
    }

    fun replacementFor(
        appleMusicId: Long,
        originalTtml: String,
        title: String? = null,
        artist: String? = null,
        allowTranslation: Boolean = true,
        translationSkipReason: LlmLyricsPhase = LlmLyricsPhase.NATIVE_ONLY,
    ): Any? {
        val config = runtime.configuration()
        val wantTranslation = allowTranslation && runtime.translationEnabled() && isTranslationAllowed(appleMusicId)
        val wantTransliteration = runtime.transliterate != null && runtime.transliterationEnabled()
        val sourceAllowed = config?.isConfigured == true && isAllowed(appleMusicId)
        val allowed = sourceAllowed &&
            (wantTranslation || wantTransliteration)
        synchronized(lock) {
            if (closed || appleMusicId <= 0L || activeId != appleMusicId) return null
            if (!allowed) {
                if (original != null || ready != null) invalidateLocked()
                configuration = config
                songTitle = title
                translationPhase = when {
                    !runtime.translationEnabled() -> LlmLyricsPhase.OFF
                    !wantTranslation -> translationSkipReason
                    config?.isConfigured != true -> LlmLyricsPhase.CONFIG_REQUIRED
                    else -> LlmLyricsPhase.WAITING
                }
                transliterationPhase = when {
                    !runtime.transliterationEnabled() -> LlmLyricsPhase.OFF
                    config?.isConfigured != true -> LlmLyricsPhase.CONFIG_REQUIRED
                    else -> LlmLyricsPhase.WAITING
                }
                publishStatusLocked()
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
                translationPhase = if (wantTranslation) LlmLyricsPhase.WAITING else translationSkipReason
                transliterationPhase = if (wantTransliteration) LlmLyricsPhase.WAITING else LlmLyricsPhase.OFF
            }
            songTitle = title
            if (!wantTranslation) translationPhase = if (!runtime.translationEnabled()) LlmLyricsPhase.OFF else translationSkipReason
            if (!wantTransliteration) transliterationPhase = LlmLyricsPhase.OFF
            val request = LlmLyricsRequest(appleMusicId, originalTtml, config!!, runtime.cacheGeneration(), title, artist)
            val stamp = generation
            if (wantTranslation && !translationAttempted) {
                translationAttempted = true
                enqueue(request, stamp, false)
            }
            if (wantTransliteration && !transliterationAttempted) {
                transliterationAttempted = true
                enqueue(request, stamp, true)
            }
            publishStatusLocked()
            return ready
        }
    }

    private fun enqueue(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean) {
        updatePhase(request, stamp, pronunciation, LlmLyricsPhase.QUEUED)
        try {
            runtime.executor.execute { prepare(request, stamp, pronunciation) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (pronunciation) transliterationAttempted = false else translationAttempted = false
            updatePhase(request, stamp, pronunciation, LlmLyricsPhase.REQUEST_FAILED)
        }
    }

    private fun prepare(request: LlmLyricsRequest, stamp: Long, pronunciation: Boolean) {
        try {
            if (!isCurrent(request, stamp, pronunciation)) return
            val cancelled = { !isCurrent(request, stamp, pronunciation) }
            var fromCache = false
            val task = request.copy(reportStatus = { phase ->
                if (phase == LlmLyricsPhase.CACHED) fromCache = true
                updatePhase(request, stamp, pronunciation,
                    if (phase == LlmLyricsPhase.CACHED) LlmLyricsPhase.APPLYING else phase)
            })
            val generated = if (pronunciation) runtime.transliterate?.invoke(task, cancelled)
                else runtime.translate(task, cancelled)
            if (cancelled()) return
            if (generated == null) {
                synchronized(lock) {
                    val phase = if (pronunciation) transliterationPhase else translationPhase
                    if (phase in setOf(LlmLyricsPhase.QUEUED, LlmLyricsPhase.GENERATING, LlmLyricsPhase.APPLYING)) {
                        updatePhase(request, stamp, pronunciation, LlmLyricsPhase.REQUEST_FAILED)
                    }
                }
                return
            }
            updatePhase(request, stamp, pronunciation, LlmLyricsPhase.APPLYING)
            // A late answer includes the already accepted sibling track.
            synchronized(compositionLock) {
                if (cancelled()) return
                val tracks = synchronized(lock) {
                    (if (pronunciation) translated else generated) to
                        (if (pronunciation) generated else transliterated)
                }
                val combined = LlmLyricsTrackComposer.compose(request.originalTtml, tracks.first, tracks.second)
                    ?: run { updatePhase(request, stamp, pronunciation, LlmLyricsPhase.INVALID_RESPONSE); return }
                val pointer = parseTtml(combined)
                    ?: run { updatePhase(request, stamp, pronunciation, LlmLyricsPhase.INVALID_RESPONSE); return }
                if (cancelled()) return
                if ((readAdamId(pointer) != request.appleMusicId && !bindAdamId(pointer, request.appleMusicId)) ||
                    !verifyPtr(pointer) || readAdamId(pointer) != request.appleMusicId
                ) {
                    updatePhase(request, stamp, pronunciation, LlmLyricsPhase.INVALID_RESPONSE)
                    return
                }
                if (cancelled()) return
                if (pronunciation) runtime.onTransliterationPrepared(request, generated)
                else runtime.onPrepared(request, generated)
                if (cancelled()) return
                synchronized(lock) {
                    if (!matchesLocked(request, stamp)) return
                    translated = tracks.first
                    transliterated = tracks.second
                    ready = pointer
                    val phase = if (fromCache) LlmLyricsPhase.CACHED else LlmLyricsPhase.READY
                    if (pronunciation) transliterationPhase = phase else translationPhase = phase
                    publishStatusLocked()
                }
            }
            if (isCurrent(request, stamp, pronunciation)) onReplacementPublished(request.appleMusicId)
        } catch (_: Exception) {
            // Fail this feature only; an accepted sibling result remains usable.
            updatePhase(request, stamp, pronunciation, LlmLyricsPhase.REQUEST_FAILED)
        } finally {
            if (!isCurrent(request, stamp, pronunciation)) synchronized(lock) {
                // Configuration/feature cancellation can arrive before another native result.
                // Retire only this old in-flight label; a newer generation owns its own status.
                if (matchesLocked(request, stamp)) {
                    val phase = if (pronunciation) transliterationPhase else translationPhase
                    if (phase == LlmLyricsPhase.QUEUED || phase == LlmLyricsPhase.GENERATING ||
                        phase == LlmLyricsPhase.APPLYING
                    ) {
                        if (pronunciation) transliterationPhase = LlmLyricsPhase.WAITING
                        else translationPhase = LlmLyricsPhase.WAITING
                        publishStatusLocked()
                    }
                }
            }
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
        translationPhase = LlmLyricsPhase.WAITING
        transliterationPhase = LlmLyricsPhase.WAITING
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            selected = false
            activeId = null
            invalidateLocked()
            songTitle = null
            publishStatusLocked()
        }
        runtime.close()
    }
}

