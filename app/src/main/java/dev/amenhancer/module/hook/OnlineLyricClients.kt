package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricDocument
import dev.amenhancer.module.lyrics.NeteaseEapi
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.YrcParser
import java.net.URLEncoder
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

/**
 * NetEase lyric client.
 *
 * The word-level YRC track is only available through the EAPI endpoint behind
 * `/lyric/new` — the plain `GET /api/song/lyric` response carries no `yrc` —
 * so this user-triggered import fetches an explicitly supplied NetEase song
 * ID through a bounded EAPI POST (`/api/song/lyric/v1`, see [NeteaseEapi]).
 * No cookies, tokens or account state are sent or persisted.
 *
 * A response without a usable `yrc` yields `null`; word timing is never
 * fabricated from `lrc`.
 */
internal class NeteaseLyricClient(private val transport: LyricHttpTransport) {

    fun fetchYrc(songId: Long): LyricDocument? {
        val response = transport.postForm(
            url = NeteaseEapi.LYRIC_V1_URL,
            body = "params=${NeteaseEapi.lyricV1Params(songId)}",
            extraHeaders = NETEASE_HEADERS,
        ) ?: return null
        return runCatching {
            val root = JSONObject(response)
            if (root.optInt("code", -1) != 200) return@runCatching null
            val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
            YrcParser.parse(yrc)
        }.getOrNull()
    }

    private companion object {
        val NETEASE_HEADERS = mapOf(
            "Origin" to "https://music.163.com",
            "Referer" to "https://music.163.com",
            "User-Agent" to
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/60.0.3112.90 Safari/537.36",
        )
    }
}
