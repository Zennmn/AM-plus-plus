package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricDocument
import dev.amenhancer.module.lyrics.NeteaseEapi
import dev.amenhancer.module.lyrics.YrcParser
import org.json.JSONObject

/**
 * AMLL TTML DB client. Direct, fixed URL per Adam ID; a 404 (or any HTTP
 * failure) simply falls through to the next source.
 */
internal class AmllTtmlClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? =
        transport.get("$AMLL_TTML_DB_BASE/am-lyrics/$adamId.ttml")

    companion object {
        const val AMLL_TTML_DB_BASE =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main"
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
