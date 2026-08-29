package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class StableMetadataOutcome {
    NOT_REQUIRED,
    CORRECTED,
    UNCHANGED,
    TIMED_OUT,
}

/** Final playback identity consumed by metadata-driven automatic lyric sources. */
internal data class StablePlaybackMetadata(
    val appleMusicId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val outcome: StableMetadataOutcome,
    val generation: Long,
)

/**
 * Turns raw current-item callbacks and the metadata resolver's terminal event
 * into one replaying, generation-safe stream. No UI polling is involved.
 */
internal class StablePlaybackMetadataCoordinator(
    private val correctionEnabled: Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val schedule: (Long, () -> Unit) -> Unit = ::scheduleDefault,
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<(StablePlaybackMetadata?) -> Unit>()
    private var generation = 0L
    private var raw: CurrentSongDetails? = null
    private var stable: StablePlaybackMetadata? = null
    private val pendingResolutions = object : LinkedHashMap<Long, ResolvedValues>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, ResolvedValues>?,
        ): Boolean = size > 8
    }

    fun onCurrentSong(details: CurrentSongDetails?) {
        var event: StablePlaybackMetadata? = null
        var cleared = false
        var timeout: Pair<Long, Long>? = null
        synchronized(lock) {
            if (details == null) {
                if (raw != null || stable != null) {
                    generation += 1L
                    raw = null
                    stable = null
                    cleared = true
                }
                return@synchronized
            }
            val changedSong = raw?.appleMusicId != details.appleMusicId
            if (changedSong) {
                generation += 1L
                raw = details
                stable = null
                val pending = pendingResolutions.remove(details.appleMusicId)
                if (correctionEnabled && pending != null) {
                    event = resolvedEvent(details, pending, generation)
                    stable = event
                } else if (correctionEnabled) {
                    timeout = details.appleMusicId to generation
                } else {
                    event = buildStable(details, StableMetadataOutcome.NOT_REQUIRED, generation)
                    stable = event
                }
            } else {
                raw = mergeRaw(raw ?: details, details)
                if (!correctionEnabled) {
                    val next = buildStable(raw!!, StableMetadataOutcome.NOT_REQUIRED, generation)
                    if (next != stable) {
                        stable = next
                        event = next
                    }
                }
            }
        }
        if (cleared) listeners.forEach { listener -> runCatching { listener(null) } }
        event?.let(::notifyListeners)
        timeout?.let { (appleMusicId, expectedGeneration) ->
            schedule(timeoutMs) { publishTimeout(appleMusicId, expectedGeneration) }
        }
    }

    fun onResolutionFinished(
        appleMusicId: Long,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
    ) {
        val event = synchronized(lock) {
            val values = ResolvedValues(title, artist, album, durationMs)
            val current = raw?.takeIf { it.appleMusicId == appleMusicId }
            if (current == null) {
                pendingResolutions[appleMusicId] = values
                return
            }
            resolvedEvent(current, values, generation).also { stable = it }
        }
        notifyListeners(event)
    }

    fun addListener(listener: (StablePlaybackMetadata?) -> Unit) {
        listeners += listener
        val snapshot = synchronized(lock) { stable }
        snapshot?.let { runCatching { listener(it) } }
    }

    fun current(): StablePlaybackMetadata? = synchronized(lock) { stable }

    private fun publishTimeout(appleMusicId: Long, expectedGeneration: Long) {
        val event = synchronized(lock) {
            if (generation != expectedGeneration || stable != null) return
            val current = raw?.takeIf { it.appleMusicId == appleMusicId } ?: return
            buildStable(current, StableMetadataOutcome.TIMED_OUT, generation).also { stable = it }
        }
        notifyListeners(event)
    }

    private fun notifyListeners(event: StablePlaybackMetadata) {
        listeners.forEach { listener -> runCatching { listener(event) } }
    }

    private fun buildStable(
        details: CurrentSongDetails,
        outcome: StableMetadataOutcome,
        generation: Long,
    ): StablePlaybackMetadata = StablePlaybackMetadata(
        appleMusicId = details.appleMusicId,
        title = details.title.orEmpty().trim(),
        artist = details.artist.orEmpty().trim(),
        album = details.album.orEmpty().trim(),
        durationMs = details.durationMs?.coerceAtLeast(0L) ?: 0L,
        outcome = outcome,
        generation = generation,
    )

    private fun resolvedEvent(
        current: CurrentSongDetails,
        values: ResolvedValues,
        generation: Long,
    ): StablePlaybackMetadata {
        val finalDetails = CurrentSongDetails(
            appleMusicId = current.appleMusicId,
            title = values.title.nonBlankOr(current.title),
            artist = values.artist.nonBlankOr(current.artist),
            album = values.album.nonBlankOr(current.album),
            durationMs = values.durationMs.takeIf { it > 0L } ?: current.durationMs,
        )
        val outcome = if (metadataChanged(current, finalDetails)) {
            StableMetadataOutcome.CORRECTED
        } else {
            StableMetadataOutcome.UNCHANGED
        }
        return buildStable(finalDetails, outcome, generation)
    }

    private fun mergeRaw(previous: CurrentSongDetails, incoming: CurrentSongDetails) = previous.copy(
        title = previous.title.nonBlankOr(incoming.title),
        artist = previous.artist.nonBlankOr(incoming.artist),
        album = previous.album.nonBlankOr(incoming.album),
        durationMs = incoming.durationMs?.takeIf { it > 0L } ?: previous.durationMs,
    )

    private fun metadataChanged(first: CurrentSongDetails, second: CurrentSongDetails): Boolean =
        normalized(first.title) != normalized(second.title) ||
            normalized(first.artist) != normalized(second.artist) ||
            normalized(first.album) != normalized(second.album)

    private fun normalized(value: String?): String = Normalizer.normalize(
        value.orEmpty(),
        Normalizer.Form.NFKC,
    ).trim().lowercase(Locale.ROOT)

    private fun String?.nonBlankOr(fallback: String?): String? =
        this?.trim()?.takeIf(String::isNotEmpty) ?: fallback

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        val scheduler by lazy {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "ampp-stable-metadata").apply { isDaemon = true }
            }
        }

        fun scheduleDefault(delayMs: Long, task: () -> Unit) {
            scheduler.schedule(task, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }
    }

    private data class ResolvedValues(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
    )
}
