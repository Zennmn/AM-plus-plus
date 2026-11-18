package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.*
import dev.amenhancer.module.lyrics.source.LyricFetchFailure
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Per-song choices and prepared display copies; the host owns all native parsing and submission. */
internal class EmbeddedLyricsPlaybackRuntime(
    private val content: EmbeddedContentManager,
    private val settings: () -> ModuleSettings,
    private val executor: Executor,
    private val fetch: (Long, String) -> Pair<AutoLyricsCandidate?, LyricFetchFailure?>,
) : LyricsPlaybackRuntime {
    private data class Binding(val prepare: (Long, String) -> Any?, val alive: (Any?) -> Boolean,
        val ready: (Long) -> Unit)
    private data class OffsetCopy(val source: String, val original: String, val offset: Int, val payload: Any)
    private data class PreparedChoice(val choice: LyricsPlaybackChoice, val version: Pair<String, String>?)
    private val store get() = content.sourceCache
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    // Workers only. Never hold lock while waiting here or doing store I/O.
    private val commitLock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var binding: Binding? = null
    private var songId: Long? = null
    private var generation = 0L
    private var pendingSource: String? = null
    private var pendingInteractive = false
    private var preparingChoice = false
    private var failedPreferred = false
    private var message: String? = null
    private var selected: LyricsPlaybackChoice? = null
    private var displayed: LyricsPlaybackChoice? = null
    private var nativeChoice: LyricsPlaybackChoice? = null
    private val prepared = mutableMapOf<String, PreparedChoice>()
    private var offsetGeneration = 0L
    private var offsetCopy: OffsetCopy? = null
    private var pendingOffset: Triple<String, String, Int>? = null
    private var failedOffset: Triple<String, String, Int>? = null
    private var clearingCache = false
    private var closed = false
    private val subscription = store.observe { notifyChanged(wake = true) }

    override fun bind(prepare: (Long, String) -> Any?, isAlive: (Any?) -> Boolean,
        onReady: (Long) -> Unit): AutoCloseable {
        val next = Binding(prepare, isAlive, onReady)
        synchronized(lock) { binding = next }
        synchronized(lock) { songId }?.let(::preparePreferredSource)
        return AutoCloseable { synchronized(lock) { if (binding === next) binding = null } }
    }

    override fun onSettingsChanged() {
        notifyChanged(wake = true)
    }

    override fun onSongChanged(appleMusicId: Long?) {
        val changed = synchronized(lock) {
            if (songId == appleMusicId) false else {
                songId = appleMusicId
                generation++
                pendingSource = null
                pendingInteractive = false
                preparingChoice = false
                failedPreferred = false
                message = null
                selected = null
                displayed = null
                nativeChoice = null
                prepared.clear()
                offsetCopy = null
                pendingOffset = null
                failedOffset = null
                true
            }
        }
        if (changed) {
            notifyChanged()
            appleMusicId?.let(::preparePreferredSource)
        }
    }

    private fun preparePreferredSource(id: Long) {
        if (!settings().customLyricsEnabled) return
        store.preferredSource(id)?.takeIf { it in REMOTE_SOURCES || it == CustomLyricsSources.MANUAL }?.let {
            queueChoice(id, it, interactive = false)
        }
    }

    override fun hasReadyLyrics(appleMusicId: Long): Boolean {
        val source = store.preferredSource(appleMusicId) ?: return false
        val version = sourceVersion(appleMusicId, source)
        val enabled = sourceEnabled(appleMusicId, source)
        return synchronized(lock) {
            if (closed || songId != appleMusicId || failedPreferred || !enabled) return@synchronized false
            val backend = binding ?: return@synchronized false
            preparedChoiceLocked(source, version)?.let { backend.alive(it.payload) } == true
        }
    }

    override fun hasCachedLyrics(appleMusicId: Long, automaticSources: List<String>): Boolean {
        if (!settings().customLyricsEnabled || synchronized(lock) { closed || clearingCache }) return false
        val source = preferredSource(appleMusicId, includeUpcoming = true)
        if (source != null) {
            return source in REMOTE_SOURCES && store.entryFor(appleMusicId, source)?.enabled == true
        }
        if (automaticSources.isEmpty() || store.manualEntry(appleMusicId)?.enabled == true ||
            store.isSuppressed(appleMusicId)) return false
        return store.entries().any {
            it.appleMusicId == appleMusicId && it.enabled &&
                (it.source in automaticSources || it.source == CustomLyricsSources.AUTO_CACHE)
        }
    }

    override fun isSuppressed(appleMusicId: Long): Boolean = store.isSuppressed(appleMusicId)
    override fun manualEntry(appleMusicId: Long): CustomLyricsEntry? = store.manualEntry(appleMusicId)
    override fun cacheGeneration(): Long = store.generation()
    override fun invalidateSource(appleMusicId: Long, source: String) {
        synchronized(lock) {
            if (songId != appleMusicId) return
            prepared.remove(source)
            if (offsetCopy?.source == source) offsetCopy = null
        }
        notifyChanged(wake = true)
    }

    override fun preferredSource(appleMusicId: Long): String? =
        preferredSource(appleMusicId, includeUpcoming = false)

    private fun preferredSource(appleMusicId: Long, includeUpcoming: Boolean): String? {
        val configured = store.preferredSource(appleMusicId)?.takeIf { sourceEnabled(appleMusicId, it) }
        return synchronized(lock) {
            when {
                closed -> null
                songId != appleMusicId -> configured.takeIf { includeUpcoming }
                failedPreferred -> null
                else -> pendingSource.takeIf { pendingInteractive } ?: configured
            }
        }
    }

    override fun snapshot(appleMusicId: Long): LyricsPlaybackSnapshot {
        val preferred = store.preferredSource(appleMusicId) ?: LyricsPlaybackSources.AUTOMATIC
        val manual = store.manualEntry(appleMusicId)?.enabled == true
        val current = synchronized(lock) { selected.takeIf { songId == appleMusicId } }
        val offset = current?.source?.let { store.offsetMs(appleMusicId, it) } ?: 0
        return synchronized(lock) {
            LyricsPlaybackSnapshot(appleMusicId, preferred, current?.source,
                pendingSource.takeIf { songId == appleMusicId }, offset, manual,
                current?.ttml?.let { TIMED_LYRICS.containsMatchIn(it) } == true,
                message.takeIf { songId == appleMusicId },
                isCurrentSong = songId == appleMusicId && !closed)
        }
    }

    override fun select(appleMusicId: Long, native: Any?, nativeTtml: String?, manual: Any?,
        manualTtml: String?): LyricsPlaybackChoice? {
        onSongChanged(appleMusicId)
        val configured = store.preferredSource(appleMusicId)
        val enabled = configured == null || sourceEnabled(appleMusicId, configured)
        val version = configured?.let { sourceVersion(appleMusicId, it) }
        val currentBinding = synchronized(lock) { binding } ?: return null
        var request: String? = null
        val result = synchronized(lock) {
            nativeChoice = native?.let { LyricsPlaybackChoice(CustomLyricsSources.AM_NATIVE, it, nativeTtml) }
            if (closed || failedPreferred) return@synchronized null
            val source = pendingSource.takeIf { pendingInteractive } ?: configured ?: return@synchronized null
            if (preparingChoice || clearingCache || source == LyricsPlaybackSources.AUTOMATIC) {
                return@synchronized selected?.takeIf { currentBinding.alive(it.payload) }
            }
            if (!enabled) {
                prepared.remove(source)
                return@synchronized null
            }
            if (source == CustomLyricsSources.AM_NATIVE && !pendingInteractive && native != null) {
                return@synchronized nativeChoice
            }
            if (source == CustomLyricsSources.MANUAL && !pendingInteractive && manual != null) {
                return@synchronized LyricsPlaybackChoice(source, manual, manualTtml)
            }
            preparedChoiceLocked(source, version)?.takeIf { currentBinding.alive(it.payload) }
                ?.let { return@synchronized it }
            if (!preparingChoice) request = source
            selected?.takeIf { currentBinding.alive(it.payload) }
        }
        request?.let { queueChoice(appleMusicId, it, interactive = false) }
        return result
    }

    override fun selectSource(appleMusicId: Long, source: String) {
        if (source !in SOURCES || synchronized(lock) { songId != appleMusicId || closed }) return
        if (!settings().customLyricsEnabled) return
        if (source == LyricsPlaybackSources.AUTOMATIC) {
            val epoch = store.generation()
            val stamp = synchronized(lock) {
                if (closed || clearingCache || songId != appleMusicId) return
                generation++
                pendingSource = LyricsPlaybackSources.AUTOMATIC
                pendingInteractive = true
                preparingChoice = true
                pendingOffset = null
                failedPreferred = false
                message = null
                generation
            }
            notifyChanged(wake = true)
            val failed: () -> Unit = {
                if (choiceCurrent(appleMusicId, stamp, epoch)) {
                    releaseChoice(appleMusicId, stamp, ModuleText.LYRICS_PREFERENCE_SAVE_FAILED.text())
                }
            }
            execute(onRejected = failed) {
                if (!choiceCurrent(appleMusicId, stamp, epoch)) return@execute
                val committed = commitPreference(appleMusicId, stamp, epoch, null) {
                    synchronized(lock) {
                        if (!isCurrentLocked(appleMusicId, stamp)) false else {
                            pendingSource = null
                            pendingInteractive = false
                            preparingChoice = false
                            true
                        }
                    }
                }
                if (!committed) {
                    if (choiceCurrent(appleMusicId, stamp, epoch)) failed()
                    return@execute
                }
                notifyChanged(wake = true)
            }
        } else queueChoice(appleMusicId, source, interactive = true)
    }

    private fun queueChoice(id: Long, source: String, interactive: Boolean) {
        val epoch = store.generation()
        val (backend, native, stamp) = synchronized(lock) {
            if (closed || clearingCache || songId != id || (!interactive && pendingSource != null)) return
            val backend = binding ?: return
            generation++
            pendingSource = source
            pendingInteractive = interactive
            preparingChoice = true
            pendingOffset = null
            failedPreferred = false
            message = null
            Triple(backend, nativeChoice, generation)
        }
        notifyChanged(wake = true)
        execute(onRejected = {
            if (choiceCurrent(id, stamp, epoch)) failChoice(id, stamp, interactive, LyricFetchFailure.REQUEST)
        }) {
            if (!choiceCurrent(id, stamp, epoch)) return@execute
            fun restoreAllowed() = store.preferredSource(id) == source && sourceEnabled(id, source)
            if (!interactive && !restoreAllowed()) {
                releaseChoice(id, stamp)
                return@execute
            }
            var failure: LyricFetchFailure? = null
            var version = sourceVersion(id, source)
            var downloaded: AutoLyricsCandidate? = null
            val cached = when (source) {
                CustomLyricsSources.AM_NATIVE -> null
                CustomLyricsSources.MANUAL -> store.manualEntry(id)?.takeIf { it.enabled }
                    ?.let { content.readLyrics(id) }?.let { AutoLyricsCandidate(source, it) }
                else -> store.readCandidate(id, source)
            }
            if (!choiceCurrent(id, stamp, epoch)) return@execute
            if (sourceVersion(id, source) != version) {
                releaseChoice(id, stamp)
                return@execute
            }
            fun prepare(found: AutoLyricsCandidate?): LyricsPlaybackChoice? {
                if (found == null) return null
                val payload = if (found.source == source && TtmlInputPolicy.isAcceptable(found.ttml)) {
                    runCatching { backend.prepare(id, found.ttml) }.getOrNull()
                } else null
                if (payload == null) failure = LyricFetchFailure.INVALID
                return payload?.let { LyricsPlaybackChoice(source, it, found.ttml) }
            }
            var choice = if (source == CustomLyricsSources.AM_NATIVE) {
                native?.takeIf { backend.alive(it.payload) }
            } else prepare(cached)
            // One remote retry covers both a cache miss and a body rejected by native parsing.
            if (choice == null && source in REMOTE_SOURCES) {
                if (!choiceCurrent(id, stamp, epoch)) return@execute
                if (!interactive && !restoreAllowed()) {
                    releaseChoice(id, stamp)
                    return@execute
                }
                val fetched = fetch(id, source)
                downloaded = fetched.first
                failure = fetched.second ?: failure
                if (!choiceCurrent(id, stamp, epoch)) return@execute
                choice = prepare(downloaded)
            }
            if (!choiceCurrent(id, stamp, epoch)) return@execute
            if (sourceVersion(id, source) != version) {
                releaseChoice(id, stamp)
                return@execute
            }
            if (!interactive && !restoreAllowed()) {
                releaseChoice(id, stamp)
                return@execute
            }
            val ready = choice ?: run {
                failChoice(id, stamp, interactive, failure ?: LyricFetchFailure.NOT_FOUND)
                return@execute
            }
            val fresh = downloaded
            if (fresh != null) {
                // Use the same store mutation lock as imports, enable/disable and clear.
                // Record our own write's version before another mutation can replace it.
                val usable = store.writeIfCurrent(epoch) {
                    val entry = store.entryFor(id, source)
                    if (!isCurrent(id, stamp) || entry?.bodyVersion() != version ||
                        (!interactive && !restoreAllowed())
                    ) return@writeIfCurrent false
                    if (store.writeCandidate(id, fresh, epoch, enabled = entry?.enabled ?: true)) {
                        version = sourceVersion(id, source)
                    }
                    // A disk failure leaves the prepared lyrics usable for this visit.
                    true
                }
                if (!usable) {
                    releaseChoice(id, stamp)
                    return@execute
                }
                if (!choiceCurrent(id, stamp, epoch)) return@execute
            }
            val readyVersion = version
            val publish = {
                val available = if (interactive) sourceEnabled(id, source) else restoreAllowed()
                val unchanged = sourceVersion(id, source) == readyVersion
                synchronized(lock) {
                    if (!isCurrentLocked(id, stamp) || binding !== backend || !available || !unchanged) false
                    else {
                        prepared[source] = PreparedChoice(ready, readyVersion)
                        pendingSource = null
                        pendingInteractive = false
                        preparingChoice = false
                        failedPreferred = false
                        message = null
                        true
                    }
                }
            }
            val committed = if (interactive) commitPreference(id, stamp, epoch, source, readyVersion, publish)
            else store.writeIfCurrent(epoch, publish)
            if (!committed) {
                if (!choiceCurrent(id, stamp, epoch)) return@execute
                if (!interactive || sourceVersion(id, source) != readyVersion) {
                    releaseChoice(id, stamp)
                } else {
                    releaseChoice(id, stamp, ModuleText.LYRICS_PREFERENCE_SAVE_FAILED.text())
                }
                return@execute
            }
            notifyChanged(wake = true)
        }
    }

    private fun CustomLyricsEntry.bodyVersion(): Pair<String, String> = fileId to sha256

    private fun sourceVersion(id: Long, source: String): Pair<String, String>? =
        if (source in REMOTE_SOURCES) store.entryFor(id, source)?.bodyVersion() else null

    private fun sourceEnabled(id: Long, source: String): Boolean = when (source) {
        CustomLyricsSources.MANUAL -> store.manualEntry(id)?.enabled == true
        in REMOTE_SOURCES -> store.entryFor(id, source)?.enabled != false
        else -> true
    }

    private fun preparedChoiceLocked(source: String, version: Pair<String, String>?): LyricsPlaybackChoice? {
        val cached = prepared[source] ?: return null
        if (cached.version == version) return cached.choice
        prepared.remove(source)
        return null
    }

    private fun choiceCurrent(id: Long, stamp: Long, epoch: Long): Boolean {
        if (!isCurrent(id, stamp)) return false
        // Rejection callbacks can run on main; only workers enter the store mutation lock.
        if (store.generation() == epoch) return isCurrent(id, stamp)
        releaseChoice(id, stamp)
        return false
    }

    private fun releaseChoice(id: Long, stamp: Long, error: String? = null) {
        synchronized(lock) {
            if (!isCurrentLocked(id, stamp)) return
            pendingSource = null
            pendingInteractive = false
            preparingChoice = false
            message = error
        }
        notifyChanged(wake = true)
    }

    private fun commitPreference(
        id: Long,
        stamp: Long,
        epoch: Long,
        source: String?,
        version: Pair<String, String>? = null,
        publish: () -> Boolean,
    ): Boolean = synchronized(commitLock) {
        val current = {
            isCurrent(id, stamp) && (source == null || sourceVersion(id, source) == version)
        }
        if (!store.writeIfCurrent(epoch, current)) return@synchronized false
        val disabled = source?.takeIf { it in REMOTE_SOURCES }
            ?.let { store.entryFor(id, it) }?.takeIf { !it.enabled }
        if (disabled != null && !store.setSourceEnabled(id, disabled.source, true)) return@synchronized false

        var committed = false
        try {
            committed = persistAndPublish(epoch, source, { store.preferredSource(id) },
                { store.setPreferredSource(id, it) }, current, publish)
            committed
        } finally {
            if (!committed && disabled != null && sourceVersion(id, disabled.source) == disabled.bodyVersion()) {
                restoreValue { store.setSourceEnabled(id, disabled.source, false) }
            }
        }
    }

    /** Runs under the worker-only commit lock. The store gate wraps only in-memory checks. */
    private fun <T> persistAndPublish(
        epoch: Long,
        value: T,
        read: () -> T,
        write: (T) -> Boolean,
        current: () -> Boolean,
        publish: () -> Boolean,
    ): Boolean {
        val previous = read()
        if (!store.writeIfCurrent(epoch, current)) return false
        val changed = previous != value
        if (changed && !write(value)) return false
        var committed = false
        try {
            committed = store.writeIfCurrent(epoch, publish)
            return committed
        } finally {
            if (!committed && changed && read() == value) restoreValue { write(previous) }
        }
    }

    private fun restoreValue(write: () -> Boolean) {
        if (!runCatching(write).getOrDefault(false)) {
            ModernXposedRuntime.log("could not restore stale lyric preference")
        }
    }

    private fun failChoice(id: Long, stamp: Long, interactive: Boolean, failure: LyricFetchFailure) {
        synchronized(lock) {
            if (!isCurrentLocked(id, stamp)) return
            val failedName = sourceNameForMessage()
            pendingSource = null
            pendingInteractive = false
            preparingChoice = false
            failedPreferred = !interactive
            message = if (!interactive) ModuleText.LYRICS_SOURCE_FALLBACK.text() else when (failure) {
                LyricFetchFailure.NOT_FOUND -> ModuleText.LYRICS_SOURCE_NOT_FOUND.text(failedName)
                LyricFetchFailure.REQUEST -> ModuleText.LYRICS_SOURCE_REQUEST_FAILED.text()
                LyricFetchFailure.INVALID -> ModuleText.LYRICS_SOURCE_INVALID.text()
            }
        }
        notifyChanged(wake = true)
    }

    private fun sourceNameForMessage(): String = when (pendingSource) {
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics"
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        CustomLyricsSources.AM_NATIVE -> "Apple Music"
        else -> "TTML"
    }

    override fun onSelected(appleMusicId: Long, source: String, payload: Any, ttml: String?) {
        val changed = synchronized(lock) {
            if (songId != appleMusicId || closed) return
            val old = selected
            selected = LyricsPlaybackChoice(source, payload, ttml)
            if (old?.source != source || old.ttml != ttml) {
                pendingOffset = null
                failedOffset = null
                true
            } else false
        }
        if (changed) notifyChanged()
    }

    override fun withOffset(appleMusicId: Long, source: String, payload: Any, ttml: String?): Any {
        synchronized(lock) {
            if (songId != appleMusicId || closed) return payload
            if (displayed?.source != source || displayed?.ttml != ttml) {
                offsetGeneration++
                pendingOffset = null
                failedOffset = null
            }
            displayed = LyricsPlaybackChoice(source, payload, ttml)
        }
        val offset = store.offsetMs(appleMusicId, source)
        if (offset == 0 || ttml == null) return payload
        val backend = synchronized(lock) { binding } ?: return payload
        val key = Triple(source, ttml, offset)
        val existing = synchronized(lock) { offsetCopy }
        val previousDisplay = existing?.takeIf {
            it.source == source && it.original == ttml && backend.alive(it.payload)
        }?.payload ?: payload
        if (existing != null && existing.source == source && existing.original == ttml &&
            existing.offset == offset && backend.alive(existing.payload)) return existing.payload
        val (stamp, displayStamp) = synchronized(lock) {
            if (songId != appleMusicId || closed || clearingCache || pendingOffset == key ||
                failedOffset == key || displayed?.source != source || displayed?.ttml != ttml
            ) return previousDisplay
            pendingOffset = key
            generation to offsetGeneration
        }
        execute(onRejected = {
            synchronized(lock) {
                if (isCurrentLocked(appleMusicId, stamp) && offsetGeneration == displayStamp &&
                    pendingOffset == key
                ) pendingOffset = null
            }
        }) {
            val shifted = TtmlTimelineOffset.apply(ttml, offset, amllTiming = source == CustomLyricsSources.AMLL)
            val pointer = shifted?.let { backend.prepare(appleMusicId, it) }
            synchronized(lock) {
                if (!isCurrentLocked(appleMusicId, stamp) || offsetGeneration != displayStamp ||
                    pendingOffset != key
                ) return@execute
                pendingOffset = null
                if (store.offsetMs(appleMusicId, source) != offset) return@execute
                if (pointer == null) {
                    failedOffset = key
                    message = ModuleText.LYRICS_OFFSET_UNAVAILABLE.text()
                } else offsetCopy = OffsetCopy(source, ttml, offset, pointer)
            }
            notifyChanged(wake = pointer != null)
        }
        return previousDisplay
    }

    override fun setOffset(appleMusicId: Long, source: String, offsetMs: Int): Boolean {
        val epoch = store.generation()
        var request = 0L
        val (current, backend, stamp) = synchronized(lock) {
            if (closed || clearingCache || preparingChoice || songId != appleMusicId) return false
            val display = displayed?.takeIf { it.source == source && it.ttml != null } ?: return false
            val backend = binding ?: return false
            request = ++offsetGeneration
            pendingOffset = null
            Triple(display, backend, generation)
        }
        val raw = current.ttml ?: return false
        fun currentLocked() = isCurrentLocked(appleMusicId, stamp) && offsetGeneration == request &&
            binding === backend && displayed?.source == source && displayed?.ttml == raw
        fun fail(error: ModuleText): Boolean {
            if (store.writeIfCurrent(epoch) {
                    synchronized(lock) {
                        if (!currentLocked()) false else {
                            message = error.text()
                            true
                        }
                    }
                }) notifyChanged()
            return false
        }
        val shifted = TtmlTimelineOffset.apply(raw, offsetMs, amllTiming = source == CustomLyricsSources.AMLL)
            ?: return fail(ModuleText.LYRICS_OFFSET_UNAVAILABLE)
        val pointer = if (offsetMs == 0) current.payload else {
            runCatching { backend.prepare(appleMusicId, shifted) }.getOrNull()
                ?: return fail(ModuleText.LYRICS_OFFSET_UNAVAILABLE)
        }
        val committed = synchronized(commitLock) {
            persistAndPublish(epoch, offsetMs, { store.offsetMs(appleMusicId, source) },
                { store.setOffsetMs(appleMusicId, source, it) }, { synchronized(lock) { currentLocked() } }) {
                synchronized(lock) {
                    if (!currentLocked()) false else {
                        offsetCopy = OffsetCopy(source, raw, offsetMs, pointer)
                        pendingOffset = null
                        failedOffset = null
                        message = null
                        true
                    }
                }
            }
        }
        if (!committed) return fail(ModuleText.LYRICS_OFFSET_SAVE_FAILED)
        notifyChanged(wake = true)
        return true
    }

    override fun cacheUsageBytes(): Long = store.sizeBytes()
    override fun clearCache(): Boolean {
        synchronized(lock) {
            if (clearingCache || closed) return false
            clearingCache = true
            generation++
            pendingSource = null
            pendingInteractive = false
            preparingChoice = false
            pendingOffset = null
            prepared.clear()
        }
        return try {
            synchronized(commitLock) { store.clear() }
        } finally {
            synchronized(lock) {
                selected?.let { selected ->
                    prepared[selected.source] = PreparedChoice(selected, null)
                }
                clearingCache = false
            }
            notifyChanged(wake = true)
        }
    }

    override fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun notifyChanged(wake: Boolean = false) {
        main.post {
            if (synchronized(lock) { closed }) return@post
            listeners.forEach { runCatching(it) }
            if (wake) {
                val target = synchronized(lock) { songId?.let { it to binding } }
                target?.second?.ready?.invoke(target.first)
            }
        }
    }

    private fun execute(onRejected: () -> Unit = {}, block: () -> Unit) {
        try { executor.execute { runCatching(block).onFailure {
            ModernXposedRuntime.log("lyric menu operation failed: ${it.javaClass.simpleName}")
            onRejected()
        } } } catch (_: RejectedExecutionException) { onRejected() }
    }
    private fun isCurrent(id: Long, stamp: Long): Boolean = synchronized(lock) { isCurrentLocked(id, stamp) }
    private fun isCurrentLocked(id: Long, stamp: Long): Boolean = !closed && songId == id && generation == stamp
    override fun close() {
        synchronized(lock) { closed = true; generation++; binding = null; prepared.clear(); selected = null; displayed = null; offsetCopy = null }
        subscription.close()
        listeners.clear()
        LyricsPlaybackRuntimeRegistry.detach(this)
    }

    private companion object {
        val REMOTE_SOURCES = setOf(CustomLyricsSources.AMLL, CustomLyricsSources.AM_LYRICS, CustomLyricsSources.LUNABEAT)
        val SOURCES = REMOTE_SOURCES + setOf(LyricsPlaybackSources.AUTOMATIC, CustomLyricsSources.AM_NATIVE, CustomLyricsSources.MANUAL)
        val TIMED_LYRICS = Regex("""(?i)\bbegin\s*=\s*[\"']""")
    }
}
