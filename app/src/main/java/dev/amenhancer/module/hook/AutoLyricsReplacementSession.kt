package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray

private const val AUTO_CACHE_DIRECTORY = "ampp-auto-lyrics"

/** A validated candidate returned by one of the automatic lyric sources. */
internal data class AutoLyricsCandidate(
    val source: String,
    val ttml: String,
    val displayName: String? = null,
)

/** Persistent raw-TTML cache seam; all calls happen off the I2/main hot path. */
internal interface AutoLyricsCache {
    fun read(appleMusicId: Long): String?
    fun write(appleMusicId: Long, ttml: String): Boolean
    fun delete(appleMusicId: Long): Boolean = false
    fun cachedIds(): List<Long> = emptyList()
}

internal enum class AutoLyricsPublishResult {
    PUBLISHED,
    ALREADY_CONFIGURED,
    FAILED,
}

/** Publishes a validated automatic lyric into the normal configured index. */
internal fun interface AutoLyricsPublisher {
    fun publish(appleMusicId: Long, candidate: AutoLyricsCandidate): AutoLyricsPublishResult
}

/** No-op cache used when a target adapter cannot provide host-private storage. */
internal object DisabledAutoLyricsCache : AutoLyricsCache {
    override fun read(appleMusicId: Long): String? = null
    override fun write(appleMusicId: Long, ttml: String): Boolean = false
}

/** Target-process wiring for the opt-in automatic resolver. */
internal data class AutoLyricsRuntime(
    val resolver: AutoLyricsSourceResolver,
    val cache: AutoLyricsCache,
    val executor: Executor,
    val publisher: AutoLyricsPublisher? = null,
    val suppressedIds: Set<Long> = emptySet(),
)

internal fun createAutoLyricsRuntime(
    application: Application,
    suppressedIds: Set<Long> = emptySet(),
): AutoLyricsRuntime {
    val root = File(application.filesDir, AUTO_CACHE_DIRECTORY)
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = FileLunabeatCatalogCache(File(root, "lunabeat")),
    )
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = AmllTtmlClient(lyricTransport),
        amLyrics = AmLyricsClient(lyricTransport),
        lunabeat = lunabeat,
    )
    val cache = FileAutoLyricsCache(root)
    val configuredContent = EmbeddedContentManager(
        session = EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(application)),
    )
    val publisher = AutoLyricsPublisher { appleMusicId, candidate ->
        val existing = runCatching {
            configuredContent.listLyrics().firstOrNull { it.appleMusicId == appleMusicId }
        }.getOrNull()
        when {
            existing != null && existing.enabled -> AutoLyricsPublishResult.ALREADY_CONFIGURED
            existing != null -> AutoLyricsPublishResult.FAILED
            else -> {
                val displayName = candidate.displayName
                    ?.takeIf(String::isNotBlank)
                    ?: "自动缓存歌词 · $appleMusicId"
                when (
                    runCatching {
                        configuredContent.saveLyrics(
                            CustomLyricsDraft(
                                appleMusicId = appleMusicId,
                                displayName = displayName,
                                ttml = candidate.ttml,
                                source = candidate.source,
                            ),
                        )
                    }.getOrNull()
                ) {
                    is CustomLyricsSaveResult.Saved -> AutoLyricsPublishResult.PUBLISHED
                    else -> AutoLyricsPublishResult.FAILED
                }
            }
        }
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
    runCatching {
        executor.execute {
            cache.cachedIds().forEach { appleMusicId ->
                if (appleMusicId in suppressedIds) return@forEach
                val ttml = cache.read(appleMusicId)
                    ?.takeIf(TtmlTimingPolicy::isWord)
                    ?: return@forEach
                when (
                    runCatching {
                        publisher.publish(
                            appleMusicId,
                            AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, ttml),
                        )
                    }.getOrDefault(AutoLyricsPublishResult.FAILED)
                ) {
                    AutoLyricsPublishResult.PUBLISHED,
                    AutoLyricsPublishResult.ALREADY_CONFIGURED,
                    -> cache.delete(appleMusicId)
                    AutoLyricsPublishResult.FAILED -> Unit
                }
            }
        }
    }
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        publisher = publisher,
        suppressedIds = suppressedIds,
    )
}

/**
 * Small atomic file cache kept outside the user-managed custom-lyrics index.
 * The raw TTML is useful across Apple Music process restarts; native pointers
 * are deliberately never persisted because their JavaCPP address is process
 * local. A compact ID index bounds disk growth and lets old files be removed.
 */
internal class FileAutoLyricsCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) : AutoLyricsCache {
    private val indexFile = File(directory, INDEX_FILE_NAME)

    override fun read(appleMusicId: Long): String? {
        if (appleMusicId <= 0L) return null
        val file = lyricFile(appleMusicId) ?: return null
        return runCatching {
            if (!file.isFile || file.length() !in 1L..MAX_TTML_BYTES) return@runCatching null
            FileInputStream(file).use { input ->
                CustomLyricsFilePolicy.readBounded(input).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    override fun delete(appleMusicId: Long): Boolean {
        if (appleMusicId <= 0L) return false
        val file = lyricFile(appleMusicId) ?: return false
        return runCatching { file.isFile && file.delete() }.getOrDefault(false)
    }

    override fun cachedIds(): List<Long> = readIds()

    override fun write(appleMusicId: Long, ttml: String): Boolean {
        if (
            appleMusicId <= 0L ||
            !TtmlInputPolicy.isAcceptable(ttml) ||
            !TtmlTimingPolicy.isWord(ttml)
        ) return false
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        val file = lyricFile(appleMusicId) ?: return false
        if (bytes.size > MAX_TTML_BYTES) return false
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching false
            atomicWrite(file, bytes)
            val ids = readIds().toMutableList().apply {
                remove(appleMusicId)
                add(appleMusicId)
            }
            val keep = ids.takeLast(maxEntries.coerceAtLeast(1))
            ids.dropLast(keep.size).forEach { oldId -> lyricFile(oldId)?.delete() }
            val index = JSONArray().apply { keep.forEach(::put) }
            atomicWrite(indexFile, index.toString().toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false)
    }

    private fun readIds(): List<Long> = runCatching {
        if (!indexFile.isFile || indexFile.length() !in 1L..MAX_INDEX_BYTES) {
            return@runCatching emptyList()
        }
        val array = JSONArray(indexFile.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                array.optLong(index, -1L).takeIf { it > 0L }?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun lyricFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { File(directory, "$FILE_PREFIX$it$FILE_SUFFIX") }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val pending = File.createTempFile("pending_", ".tmp", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val FILE_PREFIX = "lyric_"
        const val FILE_SUFFIX = ".ttml"
        const val INDEX_FILE_NAME = "index.json"
        const val MAX_ENTRIES = 64
        const val MAX_TTML_BYTES = 512 * 1024L
        const val MAX_INDEX_BYTES = 16 * 1024L
    }
}

/**
 * Prepares automatic replacements off-hook. The I2 path only checks the
 * bounded native-pointer cache and queues this session when it is cold.
 */
internal class AutoLyricsReplacementSession(
    private val fetchCandidate: (Long) -> AutoLyricsCandidate?,
    private val cache: AutoLyricsCache,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val publisher: AutoLyricsPublisher? = null,
    private val isAllowed: (Long) -> Boolean = { true },
    private val executor: Executor,
    private val logger: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
) {
    private val pointers = object : LinkedHashMap<Long, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    private val lock = Any()
    private val pending = mutableMapOf<Long, Long>()
    private val failedUntil = mutableMapOf<Long, Long>()
    private var generation = 0L
    private var activeSongKnown = false
    private var activeAppleMusicId: Long? = null
    private val activeTakeovers = mutableSetOf<Long>()

    /** Invalidates in-flight results and native pointers only when playback changes. */
    fun onSongChanged(appleMusicId: Long?) {
        val changed = synchronized(lock) {
            val sameSong = activeSongKnown == (appleMusicId != null) &&
                activeAppleMusicId == appleMusicId
            if (!sameSong) {
                generation += 1L
                activeSongKnown = appleMusicId != null
                activeAppleMusicId = appleMusicId
                pending.clear()
                failedUntil.clear()
                activeTakeovers.clear()
            }
            !sameSong
        }
        if (changed) {
            synchronized(pointers) {
                pointers.clear()
            }
        }
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

    fun isTracking(appleMusicId: Long): Boolean = synchronized(lock) {
        appleMusicId > 0L && isCurrentSongLocked(appleMusicId) && isAllowed(appleMusicId) &&
            (appleMusicId in pending || synchronized(pointers) { appleMusicId in pointers })
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

    fun replacementOrPrepareFor(appleMusicId: Long): Any? = replacementFor(appleMusicId)

    /** Records that an automatic pointer has actually been installed into I2. */
    fun markTakeoverApplied(appleMusicId: Long) {
        synchronized(lock) {
            if (isCurrentSongLocked(appleMusicId)) activeTakeovers += appleMusicId
        }
    }

    /**
     * Keeps an already-visible automatic pointer across an unrelated native
     * refresh for the same song. A newly observed higher-quality native
     * document is allowed to win; an unknown pointer fails open to the current
     * automatic display once takeover has already happened.
     */
    fun takeoverReplacementFor(
        appleMusicId: Long,
        original: Any?,
        metadata: TtmlDocumentMetadata?,
    ): Any? {
        val replacement = readyReplacementFor(appleMusicId) ?: return null
        if (replacement === original) return replacement
        synchronized(lock) {
            if (appleMusicId !in activeTakeovers) return null
        }
        if (metadata?.timingMode == TtmlTimingMode.NON_WORD ||
            metadata?.needsTranslationFallback == true ||
            metadata == null
        ) {
            return replacement
        }
        synchronized(lock) { activeTakeovers.remove(appleMusicId) }
        return null
    }

    private fun request(appleMusicId: Long) {
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return
            val requestGeneration = generation
            if (pending[appleMusicId] == requestGeneration) return
            synchronized(pointers) {
                if (appleMusicId in pointers) return
            }
            val retryAt = failedUntil[appleMusicId] ?: 0L
            if (retryAt > nowMs()) return
            pending[appleMusicId] = requestGeneration
            try {
                executor.execute { prepare(appleMusicId, requestGeneration) }
            } catch (_: RejectedExecutionException) {
                if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
                logger("automatic lyrics prepare was rejected for $appleMusicId")
            }
        }
    }

    private fun prepare(appleMusicId: Long, requestGeneration: Long) {
        var published = false
        var preparedCandidate: AutoLyricsCandidate? = null
        var preparedPointer: Any? = null
        try {
            if (!isCurrentRequest(appleMusicId, requestGeneration)) return
            val cached = runCatching { cache.read(appleMusicId) }.getOrNull()
            if (cached != null && isCurrentRequest(appleMusicId, requestGeneration)) {
                val candidate = AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, cached)
                preparedPointer = preparePointer(
                    candidate.ttml,
                    appleMusicId,
                    source = candidate.source,
                    requestGeneration = requestGeneration,
                )
                published = preparedPointer != null
                if (published) preparedCandidate = candidate
            }
            if (!published && isCurrentRequest(appleMusicId, requestGeneration)) {
                val candidate = runCatching { fetchCandidate(appleMusicId) }.getOrNull()
                if (candidate != null && isCurrentRequest(appleMusicId, requestGeneration)) {
                    preparedPointer = preparePointer(
                        candidate.ttml,
                        appleMusicId,
                        candidate.source,
                        requestGeneration,
                    )
                    published = preparedPointer != null
                    if (published) {
                        preparedCandidate = candidate
                        if (isCurrentRequest(appleMusicId, requestGeneration)) {
                            runCatching { cache.write(appleMusicId, candidate.ttml) }
                        }
                    }
                }
            }
            if (!published || preparedCandidate == null ||
                !isCurrentRequest(appleMusicId, requestGeneration)
            ) {
                removePointerIf(appleMusicId, preparedPointer)
                if (!published || preparedCandidate == null) {
                    markFailedIfCurrent(appleMusicId, requestGeneration)
                }
                return
            }
            val publishResult = publisher?.let {
                runCatching { it.publish(appleMusicId, preparedCandidate!!) }
                    .getOrDefault(AutoLyricsPublishResult.FAILED)
            }
            when (publishResult) {
                null,
                AutoLyricsPublishResult.PUBLISHED -> {
                    if (publishResult == AutoLyricsPublishResult.PUBLISHED) {
                        runCatching { cache.delete(appleMusicId) }
                    }
                    if (isCurrentRequest(appleMusicId, requestGeneration)) {
                        onReplacementPublished?.invoke(appleMusicId)
                        synchronized(lock) {
                            if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                                failedUntil.remove(appleMusicId)
                            }
                        }
                    } else {
                        removePointerIf(appleMusicId, preparedPointer)
                    }
                }
                AutoLyricsPublishResult.ALREADY_CONFIGURED -> {
                    runCatching { cache.delete(appleMusicId) }
                    removePointerIf(appleMusicId, preparedPointer)
                }
                AutoLyricsPublishResult.FAILED -> {
                    removePointerIf(appleMusicId, preparedPointer)
                    markFailedIfCurrent(appleMusicId, requestGeneration)
                }
            }
        } finally {
            synchronized(lock) {
                if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
            }
        }
    }

    private fun preparePointer(
        ttml: String,
        appleMusicId: Long,
        source: String,
        requestGeneration: Long,
    ): Any? {
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
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                false
            } else {
                synchronized(pointers) { pointers[appleMusicId] = pointer }
                true
            }
        }
        return pointer.takeIf { accepted }
    }

    private fun markFailedIfCurrent(appleMusicId: Long, requestGeneration: Long) {
        synchronized(lock) {
            if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
            }
        }
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
        generation == requestGeneration &&
            isCurrentSongLocked(appleMusicId) &&
            isAllowed(appleMusicId)

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
