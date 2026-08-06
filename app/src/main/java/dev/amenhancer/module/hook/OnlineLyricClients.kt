package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricDocument
import dev.amenhancer.module.lyrics.NeteaseEapi
import dev.amenhancer.module.lyrics.YrcParser
import java.net.URLEncoder
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
internal class AmLyricsClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? {
        if (adamId <= 0L) return null
        val index = transport.get(AM_LYRICS_INDEX_URL) ?: return null
        val path = resolvePath(index, adamId) ?: return null
        return transport.get("$AM_LYRICS_BASE/$path")
    }

    private fun resolvePath(indexJson: String, adamId: Long): String? = runCatching {
        val entries = JSONObject(indexJson).optJSONArray("entries") ?: return@runCatching null
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optLong("appleMusicId", 0L) != adamId) continue
            if (!entry.optBoolean("enabled", true)) continue
            val path = entry.optString("path").takeIf(String::isNotBlank)
                ?: return@runCatching null
            return@runCatching encodePath(path)
        }
        null
    }.getOrNull()

    private fun encodePath(path: String): String? {
        if (!path.startsWith(AM_LYRICS_ROOT) || path.contains('\\')) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }

    companion object {
        const val AM_LYRICS_BASE = "https://raw.githubusercontent.com/Zennmn/am-lyrics/main"
        const val AM_LYRICS_INDEX_URL = "$AM_LYRICS_BASE/index.json"
        private const val AM_LYRICS_ROOT = "am-lyrics/"
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
