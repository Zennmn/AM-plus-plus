package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.AmllTtmlFormatConverter
import dev.amenhancer.module.model.CustomLyricsSources
import java.net.URLEncoder
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject

/**
 * AMLL TTML DB client. Direct, fixed URL per Adam ID; failures return null.
 */
internal class AmllTtmlClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? =
        transport.get("$AMLL_TTML_DB_BASE/am-lyrics/$adamId.ttml")

    companion object {
        const val AMLL_TTML_DB_BASE =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main"
    }
}

/** One automatic source in the fixed playback lookup order. */
internal data class AutoLyricsSource(
    val name: String,
    val fetch: (Long) -> String?,
)

/**
 * Fetches the first structurally valid Word-TTML candidate. Source-specific
 * conversion stays here so the playback session only handles validation,
 * native parsing, caching, and publication.
 */
internal class AutoLyricsSourceResolver(
    private val sources: List<AutoLyricsSource>,
    private val parallelBudgetMs: Long? = null,
) {
    private val sourceExecutor = parallelBudgetMs
        ?.takeIf { it > 0L && sources.isNotEmpty() }
        ?.let {
            ThreadPoolExecutor(
                0,
                sources.size.coerceAtLeast(1),
                SOURCE_WORKER_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                { runnable -> Thread(runnable, "ampp-auto-lyrics-source").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }

    fun fetch(appleMusicId: Long): AutoLyricsCandidate? {
        if (appleMusicId <= 0L) return null
        val executor = sourceExecutor
        val budgetMs = parallelBudgetMs
        if (executor != null && budgetMs != null) {
            return fetchInParallel(appleMusicId, executor, budgetMs)
        }
        sources.forEach { source ->
            fetchValidated(source, appleMusicId)?.let { return it }
        }
        return null
    }

    private fun fetchInParallel(
        appleMusicId: Long,
        executor: ThreadPoolExecutor,
        budgetMs: Long,
    ): AutoLyricsCandidate? {
        val futures = sources.mapNotNull { source ->
            runCatching {
                executor.submit<AutoLyricsCandidate?> { fetchValidated(source, appleMusicId) }
            }.getOrNull()
        }
        if (futures.isEmpty()) return null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        try {
            futures.forEach { future ->
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return@forEach
                val candidate = await(future, remainingNanos)
                if (candidate != null) return candidate
                if (!future.isDone) return@forEach
            }
            // A higher-priority source may consume the whole budget. At the
            // deadline, select the highest-priority result already completed.
            return futures.firstNotNullOfOrNull { future ->
                if (!future.isDone || future.isCancelled) null else await(future, 0L)
            }
        } finally {
            futures.forEach { future ->
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    private fun await(future: Future<AutoLyricsCandidate?>, timeoutNanos: Long): AutoLyricsCandidate? =
        try {
            if (timeoutNanos <= 0L) future.get() else future.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: Exception) {
            null
        }

    private fun fetchValidated(source: AutoLyricsSource, appleMusicId: Long): AutoLyricsCandidate? {
        val ttml = runCatching { source.fetch(appleMusicId) }.getOrNull() ?: return null
        if (!TtmlInputPolicy.isAcceptable(ttml) || !TtmlTimingPolicy.isWord(ttml)) return null
        return AutoLyricsCandidate(source.name, ttml)
    }

    companion object {
        /** Wires the fixed AMLL → Lunabeat → user's repository priority. */
        fun fixed(
            amll: AmllTtmlClient,
            amLyrics: AmLyricsClient,
            lunabeat: LunabeatClient,
        ): AutoLyricsSourceResolver = AutoLyricsSourceResolver(
            sources = listOf(
                AutoLyricsSource(CustomLyricsSources.AMLL) { raw ->
                    amll.fetch(raw)?.let { AmllTtmlFormatConverter.toAppleFormat(it).ttml }
                },
                AutoLyricsSource(CustomLyricsSources.LUNABEAT, lunabeat::fetch),
                AutoLyricsSource(CustomLyricsSources.AM_LYRICS, amLyrics::fetch),
            ),
            parallelBudgetMs = APPLE_ID_SOURCE_BUDGET_MS,
        )

        private const val APPLE_ID_SOURCE_BUDGET_MS = 4_000L
        private const val SOURCE_WORKER_KEEP_ALIVE_SECONDS = 15L
    }
}

/** User-owned TTML repository indexed by Apple Music Adam ID; settings process only. */
internal data class AmLyricsIndexEntry(
    val appleMusicId: Long,
    val alternateIds: List<Long>,
    val displayName: String,
    val path: String,
    val enabled: Boolean,
    val sizeBytes: Long,
    val sha256: String,
) {
    val allAppleMusicIds: List<Long>
        get() = listOf(appleMusicId) + alternateIds
}

internal data class AmLyricsIndex(
    val entries: List<AmLyricsIndexEntry>,
) {
    fun entryFor(appleMusicId: Long): AmLyricsIndexEntry? = entries.firstOrNull { entry ->
        appleMusicId in entry.allAppleMusicIds
    }
}

internal class AmLyricsClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? {
        if (adamId <= 0L) return null
        val entry = fetchIndex()?.entryFor(adamId) ?: return null
        return fetchTtml(entry)
    }

    fun fetchIndex(): AmLyricsIndex? = runCatching {
        val bytes = transport.getBytes(AM_LYRICS_INDEX_URL) ?: return@runCatching null
        parseIndex(bytes.toString(Charsets.UTF_8))
    }.getOrNull()

    fun fetchTtml(entry: AmLyricsIndexEntry): String? {
        if (!entry.enabled) return null
        val path = encodePath(entry.path) ?: return null
        val bytes = transport.getBytes("$AM_LYRICS_BASE/$path") ?: return null
        if (bytes.size.toLong() != entry.sizeBytes) return null
        if (!sha256(bytes).equals(entry.sha256, ignoreCase = true)) return null
        val ttml = bytes.toString(Charsets.UTF_8)
        return ttml.takeIf(TtmlInputPolicy::isAcceptable)
    }

    internal companion object {
        const val AM_LYRICS_BASE = "https://raw.githubusercontent.com/Zennmn/am-lyrics/main"
        const val AM_LYRICS_INDEX_URL = "$AM_LYRICS_BASE/index.json"
        private const val AM_LYRICS_ROOT = "am-lyrics/"
        private const val INDEX_VERSION = 1
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        fun parseIndex(indexJson: String): AmLyricsIndex? = runCatching {
            val root = JSONObject(indexJson)
            if (root.optInt("version", 0) != INDEX_VERSION) return@runCatching null
            if (root.optString("layout") != "artist-title-id") return@runCatching null
            val entries = root.optJSONArray("entries") ?: return@runCatching null
            val parsed = entries.parseEntries() ?: return@runCatching null
            val allIds = mutableSetOf<Long>()
            parsed.forEach { entry ->
                entry.allAppleMusicIds.forEach { id ->
                    if (!allIds.add(id)) return@runCatching null
                }
            }
            AmLyricsIndex(parsed)
        }.getOrNull()

        private fun JSONArray.parseEntries(): List<AmLyricsIndexEntry>? = buildList {
            for (index in 0 until length()) {
                val raw = optJSONObject(index) ?: return null
                val appleMusicId = parsePositiveLong(raw.opt("appleMusicId"))
                    ?: return null
                val alternateIds = parseAlternateIds(raw.optJSONArray("alternateIds"))
                    ?: return null
                val path = raw.optString("path").takeIf(String::isNotBlank)
                    ?.takeIf { isSafePath(it) } ?: return null
                val sizeBytes = raw.optLong("sizeBytes", 0L)
                    .takeIf { it in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong() }
                    ?: return null
                val sha256 = raw.optString("sha256")
                    .takeIf(SHA256_PATTERN::matches) ?: return null
                val displayName = raw.optString("displayName").trim().ifBlank {
                    listOfNotNull(
                        raw.optString("title").takeIf(String::isNotBlank),
                        raw.optString("artist").takeIf(String::isNotBlank),
                    ).joinToString(" - ").ifBlank { "GitHub 自定义歌词" }
                }
                add(
                    AmLyricsIndexEntry(
                        appleMusicId = appleMusicId,
                        alternateIds = alternateIds,
                        displayName = displayName,
                        path = path,
                        enabled = raw.optBoolean("enabled", true),
                        sizeBytes = sizeBytes,
                        sha256 = sha256.lowercase(),
                    ),
                )
            }
        }

        private fun parseAlternateIds(array: JSONArray?): List<Long>? {
            if (array == null) return emptyList()
            val result = linkedSetOf<Long>()
            for (index in 0 until array.length()) {
                val id = parsePositiveLong(array.opt(index)) ?: return null
                result += id
            }
            return result.toList()
        }

        private fun parsePositiveLong(value: Any?): Long? = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }

        private fun isSafePath(path: String): Boolean =
            path.startsWith(AM_LYRICS_ROOT) &&
                !path.contains('\\') &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun encodePath(path: String): String? {
        if (!isSafePath(path)) return null
        val segments = path.split('/')
        return segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
