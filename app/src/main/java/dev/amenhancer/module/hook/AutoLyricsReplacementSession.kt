package dev.amenhancer.module.hook


import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import android.app.Application
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.cachedSongDisplayName
import dev.amenhancer.module.config.isCompleteCacheSongName
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import dev.amenhancer.module.lyrics.LlmTransliterationDocument
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCache
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun createAutoLyricsRuntime(
    application: Application,
    content: EmbeddedContentManager,
    lunabeatCatalogCache: LunabeatCatalogCache,
    currentSong: CurrentSongIdentityCache,
    configuration: () -> ModuleSettings,
): AutoLyricsRuntime {
    val sourceStore = content.sourceCache
    val failures = ThreadLocal<dev.amenhancer.module.lyrics.source.LyricFetchFailure?>()
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
        onFailure = { failures.set(it) },
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
        onFailure = { failures.set(it) },
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = lunabeatCatalogCache,
        onFailure = { failures.set(it) },
    )
    val amll = AmllTtmlClient(lyricTransport, onFailure = { failures.set(it) })
    val amLyrics = AmLyricsClient(lyricTransport, onFailure = { failures.set(it) })
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = amll,
        amLyrics = amLyrics,
        lunabeat = lunabeat,
    )

    fun cacheSongName(title: String?, artist: String?): String? {
        val songTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return listOfNotNull(songTitle, artist?.trim()?.takeIf(String::isNotEmpty)).joinToString(" - ")
    }

    fun AutoLyricsCandidate.withSongTitle(appleMusicId: Long, existingName: String? = null): AutoLyricsCandidate {
        fun usableName(name: String?): String? =
            name?.trim()?.takeIf { it.isNotEmpty() && it != source }

        val ownName = usableName(displayName)
        val previousName = usableName(existingName)
            ?: sourceStore.entryFor(appleMusicId, source)?.cachedSongDisplayName()
        val details = currentSong.current()?.details?.takeIf { it.appleMusicId == appleMusicId }
        val currentName = cacheSongName(details?.title, details?.artist)
        val resolvedName = when {
            ownName?.isCompleteCacheSongName() == true -> ownName
            previousName?.isCompleteCacheSongName() == true -> previousName
            currentName != null -> currentName
            ownName != null -> ownName
            else -> previousName
        }
        return if (resolvedName == null) this else copy(displayName = resolvedName)
    }

    fun lookupCacheSongNames(
        appleMusicIds: List<Long>,
        isCancelled: () -> Boolean,
    ): Map<Long, String> {
        failures.remove()
        return try {
            val requested = appleMusicIds.asSequence()
                .filter { it > 0L }
                .toCollection(linkedSetOf())
            if (requested.isEmpty() || isCancelled()) return emptyMap()

            val names = linkedMapOf<Long, String>()
            currentSong.current()?.details?.let { details ->
                if (details.appleMusicId in requested) {
                    cacheSongName(details.title, details.artist)
                        ?.takeIf(String::isCompleteCacheSongName)
                        ?.let { names[details.appleMusicId] = it }
                }
            }

            var remaining = requested - names.keys
            if (remaining.isNotEmpty() && !isCancelled()) {
                val index = runCatching { AmLyricsClient(indexTransport).fetchIndex() }.getOrNull()
                if (index != null) {
                    for (entry in index.entries) {
                        if (isCancelled()) return names
                        for (appleMusicId in entry.allAppleMusicIds) {
                            if (appleMusicId in remaining &&
                                entry.displayName.isCompleteCacheSongName()
                            ) {
                                names.putIfAbsent(appleMusicId, entry.displayName)
                            }
                        }
                    }
                }
                remaining = requested - names.keys
            }

            if (remaining.isNotEmpty() && !isCancelled()) {
                val catalog = runCatching { lunabeat.loadCatalog() }.getOrNull()
                if (catalog != null) {
                    for (song in catalog.songs) {
                        if (isCancelled()) return names
                        for (appleMusicId in song.appleMusicIds) {
                            if (appleMusicId in remaining && song.displayName.isCompleteCacheSongName()) {
                                names.putIfAbsent(appleMusicId, song.displayName)
                            }
                        }
                    }
                }
            }
            names
        } finally {
            failures.remove()
        }
    }
    val cache = object : AutoLyricsCache {
        override fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? =
            sourceStore.readCandidate(appleMusicId, source)
        override fun writeCandidateIfCurrent(appleMusicId: Long, candidate: AutoLyricsCandidate, generation: Long): Boolean =
            sourceStore.writeCandidate(appleMusicId, candidate.withSongTitle(appleMusicId), generation)
        override fun generation(): Long = sourceStore.generation()
    }
    val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        // Keep only one queued task; a newer song must replace stale work.
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-auto-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    val refreshExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-lyrics-refresh").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    refreshExecutor.execute { sourceStore.prepare() }
    val llmExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "ampp-llm-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val llmCache = FileLlmLyricsCache(File(application.filesDir, "ampp-llm-lyrics"))
    val llmClient = LlmLyricsClient()
    LyricsPlaybackRuntimeRegistry.attachLlmStatus(llmExecutor)
    fun failurePhase(reason: String, status: Int?): LlmLyricsPhase = when {
        status == 401 || status == 403 -> LlmLyricsPhase.AUTH_FAILED
        status == 429 -> LlmLyricsPhase.RATE_LIMITED
        reason == "timeout" -> LlmLyricsPhase.TIMED_OUT
        reason == "invalid_response" || reason == "response_limit" -> LlmLyricsPhase.INVALID_RESPONSE
        else -> LlmLyricsPhase.REQUEST_FAILED
    }
    val llm = LlmLyricsRuntime(
        configuration = {
            configuration().let { settings ->
                if (settings.llmLyricsTransliterationEnabled ||
                    (settings.customLyricsEnabled && settings.automaticLyricsEnabled && settings.llmLyricsEnabled)
                ) {
                    LlmLyricsConfig.fromSettings(settings)
                } else null
            }
        },
        translate = translate@{ request, isCancelled ->
            if (isCancelled()) return@translate null
            // Eligibility always precedes cache reads, including on later visits.
            val document = LlmLyricsDocument.prepare(request.originalTtml) ?: run {
                val metadata = TtmlTimingPolicy.metadataOf(request.originalTtml)
                request.reportStatus(when {
                    metadata.hasTranslation -> LlmLyricsPhase.EXISTS
                    metadata.language != null && !metadata.isForeign -> LlmLyricsPhase.NOT_NEEDED
                    else -> LlmLyricsPhase.UNSUPPORTED
                })
                return@translate null
            }
            if (isCancelled()) return@translate null
            llmCache.read(request.appleMusicId, request.originalTtml, request.config)?.let { cached ->
                request.reportStatus(LlmLyricsPhase.CACHED)
                return@translate cached.takeUnless { isCancelled() }
            }
            request.reportStatus(LlmLyricsPhase.GENERATING)
            val translations = llmClient.translate(
                config = request.config,
                lines = document.translationLines,
                title = request.title,
                artist = request.artist,
                sourceLanguage = document.sourceLanguage,
                onFailure = { reason, status -> request.reportStatus(failurePhase(reason, status)) },
                isCancelled = isCancelled,
            ) ?: return@translate null
            if (isCancelled()) return@translate null
            val translated = document.withTranslations(translations) ?: run {
                request.reportStatus(LlmLyricsPhase.INVALID_RESPONSE)
                return@translate null
            }
            if (isCancelled()) return@translate null
            translated.takeUnless { isCancelled() }
        },
        executor = Executor { task ->
            llmExecutor.execute(task)
        },
        close = {
            llmExecutor.shutdownNow()
            LyricsPlaybackRuntimeRegistry.detachLlmStatus(llmExecutor)
        },
        onPrepared = { request, translated ->
            // Persist only a host-accepted result; never use the custom-lyrics publisher.
            llmCache.write(
                request.appleMusicId, request.originalTtml, request.config, translated,
                expectedGeneration = request.cacheGeneration,
            )
        },
        translationEnabled = {
            configuration().let { it.customLyricsEnabled && it.automaticLyricsEnabled && it.llmLyricsEnabled }
        },
        transliterationEnabled = { configuration().llmLyricsTransliterationEnabled },
        transliterate = transliterate@{ request, isCancelled ->
            if (isCancelled()) return@transliterate null
            val document = LlmTransliterationDocument.prepare(request.originalTtml) ?: run {
                request.reportStatus(if (LlmTransliterationDocument.hasPronunciation(request.originalTtml))
                    LlmLyricsPhase.EXISTS else LlmLyricsPhase.UNSUPPORTED)
                return@transliterate null
            }
            if (document.isLatinOnly) {
                request.reportStatus(LlmLyricsPhase.NOT_NEEDED)
                return@transliterate null
            }
            if (isCancelled()) return@transliterate null
            llmCache.read(request.appleMusicId, request.originalTtml, request.config, kind = "transliteration")
                ?.let {
                    request.reportStatus(LlmLyricsPhase.CACHED)
                    return@transliterate it.takeUnless { isCancelled() }
                }
            request.reportStatus(LlmLyricsPhase.GENERATING)
            llmClient.transliterate(
                config = request.config,
                document = document,
                title = request.title,
                artist = request.artist,
                onFailure = { reason, status -> request.reportStatus(failurePhase(reason, status)) },
                isCancelled = isCancelled,
            )?.takeUnless { isCancelled() }
        },
        onTransliterationPrepared = { request, ttml ->
            llmCache.write(
                request.appleMusicId, request.originalTtml, request.config, ttml,
                kind = "transliteration", expectedGeneration = request.cacheGeneration,
            )
        },
        discardPending = { llmExecutor.queue.clear() },
        onStatusChanged = { LyricsPlaybackRuntimeRegistry.publishLlmStatus(llmExecutor, it) },
        cacheGeneration = llmCache::generation,
    )
    val playbackExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "ampp-lyrics-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val playback = EmbeddedLyricsPlaybackRuntime(
        content = content,
        settings = configuration,
        executor = playbackExecutor,
        fetch = { id, source ->
            failures.remove()
            val result = resolver.fetchSelected(id, source) {
                failures.set(dev.amenhancer.module.lyrics.source.LyricFetchFailure.INVALID)
            }
            result?.withSongTitle(id) to failures.get().also { failures.remove() }
        },
        lookupCacheSongNames = ::lookupCacheSongNames,
        llmCache = llmCache,
    )
    LyricsPlaybackRuntimeRegistry.attach(playback)
    val songNameSubscription = currentSong.addListener { song ->
        val details = song?.details ?: return@addListener
        val artist = details.artist?.takeIf(String::isNotBlank) ?: return@addListener
        val name = cacheSongName(details.title, artist) ?: return@addListener
        playback.backfillCacheSongName(details.appleMusicId, name)
    }
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        llm = llm,
        playback = playback,
        refreshExecutor = Executor { task ->
            refreshExecutor.queue.clear()
            refreshExecutor.execute(task)
        },
        closeRefresh = {
            songNameSubscription.close()
            refreshExecutor.shutdownNow()
            playbackExecutor.shutdownNow()
            executor.shutdownNow()
            playback.close()
        },
        refreshSong = refresh@{ appleMusicId, isCancelled ->
            val source = playback.snapshot(appleMusicId).activeSource?.takeIf {
                it == CustomLyricsSources.AMLL || it == CustomLyricsSources.AM_LYRICS || it == CustomLyricsSources.LUNABEAT
            } ?: return@refresh null
            val previous = sourceStore.entryFor(appleMusicId, source) ?: return@refresh null
            if (!previous.enabled || isCancelled()) return@refresh null
            val epoch = sourceStore.generation()
            val candidate = resolver.fetchSelected(appleMusicId, source) ?: return@refresh null
            val current = playback.snapshot(appleMusicId)
            if (isCancelled() || !current.isCurrentSong || current.activeSource != source ||
                (current.preferredSource == LyricsPlaybackSources.AUTOMATIC && !TtmlTimingPolicy.isWord(candidate.ttml))
            ) return@refresh null
            val latest = sourceStore.entryFor(appleMusicId, source) ?: return@refresh null
            // A display-name backfill does not invalidate a refresh of the same cached lyrics.
            if (latest.copy(displayName = previous.displayName) != previous ||
                !sourceStore.writeCandidate(appleMusicId, candidate.withSongTitle(appleMusicId, latest.displayName), epoch)
            ) return@refresh null
            sourceStore.entryFor(appleMusicId, source)?.takeIf { it.sha256 != previous.sha256 }
        },
    )
}
