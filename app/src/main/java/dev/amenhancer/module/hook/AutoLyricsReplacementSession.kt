package dev.amenhancer.module.hook


import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import android.app.Application
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.lyrics.embeddedLunabeatCatalogCache
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import dev.amenhancer.module.lyrics.LlmTransliterationDocument
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun createAutoLyricsRuntime(
    application: Application,
    suppressedIds: Set<Long> = emptySet(),
    configuration: () -> ModuleSettings = { ModuleSettings() },
): AutoLyricsRuntime {
    val configuredContent = EmbeddedContentManager(
        session = EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(application)),
    )
    val sourceStore = configuredContent.sourceCache
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
        cache = embeddedLunabeatCatalogCache(application, sourceStore),
        onFailure = { failures.set(it) },
    )
    val amll = AmllTtmlClient(lyricTransport, onFailure = { failures.set(it) })
    val amLyrics = AmLyricsClient(lyricTransport, onFailure = { failures.set(it) })
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = amll,
        amLyrics = amLyrics,
        lunabeat = lunabeat,
    )
    val cache = object : AutoLyricsCache {
        override fun read(appleMusicId: Long): String? = readCandidate(appleMusicId)?.ttml
        override fun readCandidate(appleMusicId: Long): AutoLyricsCandidate? = sourceStore.readCandidate(appleMusicId)
        override fun readCandidateForSource(appleMusicId: Long, source: String): AutoLyricsCandidate? =
            sourceStore.readCandidate(appleMusicId, source)
        override fun write(appleMusicId: Long, ttml: String): Boolean =
            writeCandidate(appleMusicId, AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, ttml))
        override fun writeCandidate(appleMusicId: Long, candidate: AutoLyricsCandidate): Boolean =
            sourceStore.writeCandidate(appleMusicId, candidate)
        override fun writeCandidateIfCurrent(appleMusicId: Long, candidate: AutoLyricsCandidate, generation: Long): Boolean =
            sourceStore.writeCandidate(appleMusicId, candidate, generation)
        override fun generation(): Long = sourceStore.generation()
        override fun delete(appleMusicId: Long): Boolean = sourceStore.remove(appleMusicId)
        override fun cachedIds(): List<Long> = sourceStore.cachedIds()
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
            llmCache.write(request.appleMusicId, request.originalTtml, request.config, translated)
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
            llmCache.write(request.appleMusicId, request.originalTtml, request.config, ttml, kind = "transliteration")
        },
        discardPending = { llmExecutor.queue.clear() },
        onStatusChanged = { LyricsPlaybackRuntimeRegistry.publishLlmStatus(llmExecutor, it) },
    )
    val playbackExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "ampp-lyrics-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val playback = EmbeddedLyricsPlaybackRuntime(
        content = configuredContent,
        settings = configuration,
        executor = playbackExecutor,
        fetch = { id, source ->
            failures.remove()
            val result = resolver.fetchSelected(id, source) {
                failures.set(dev.amenhancer.module.lyrics.source.LyricFetchFailure.INVALID)
            }
            result to failures.get().also { failures.remove() }
        },
    )
    LyricsPlaybackRuntimeRegistry.attach(playback)
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        llm = llm,
        playback = playback,
        suppressedIds = suppressedIds,
        refreshExecutor = Executor { task ->
            refreshExecutor.queue.clear()
            refreshExecutor.execute(task)
        },
        closeRefresh = {
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
                (current.preferredSource == LyricsPlaybackSources.AUTOMATIC && !TtmlTimingPolicy.isWord(candidate.ttml)) ||
                sourceStore.entryFor(appleMusicId, source) != previous ||
                !sourceStore.writeCandidate(appleMusicId, candidate, epoch)
            ) return@refresh null
            sourceStore.entryFor(appleMusicId, source)?.takeIf { it.sha256 != previous.sha256 }
        },
    )
}
