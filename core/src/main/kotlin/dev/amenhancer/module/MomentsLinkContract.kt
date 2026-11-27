package dev.amenhancer.module

import java.net.URI

/** Restricted, public-data-only IPC to NetEase Cloud Music. No hooks run in WeChat. */
object MomentsLinkContract {
    const val INTERMEDIARY_PACKAGE = "com.netease.cloudmusic"
    const val OPEN = "dev.amenhancer.module.action.OPEN_MOMENTS_LINK"
    const val PERMISSION = "dev.amenhancer.module.permission.SHARE_MOMENTS_LINK"
    const val URL = "music_url"
    const val TITLE = "music_title"
    const val ARTWORK = "music_artwork"
    const val CHINA_STOREFRONT = "share_china_storefront"
    const val ARTIST = "music_artist"
    const val REQUEST_ID = "share_request_id"
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val RESULT_PENDING = 1
    const val RESULT_OPENED = -1
    const val RESULT_NOT_READY = 2
    const val RESULT_UNSUPPORTED = 3
    const val RESULT_INVALID = 4
    const val RESULT_SIGNATURE = 5
    const val RESULT_CONTRACT = 6
    const val RESULT_LAUNCH = 7
    const val RESULT_BUSY = 8
    const val RESULT_RELAY = 9
    const val RESULT_MISSING = 10
    /** Opt-in replacement of the country segment; preserve the exact encoded path, IDs and query. */
    fun withChinaStorefront(value: String?, enabled: Boolean): String? = validate(value)?.let { original ->
        if (!enabled) return@let original
        val uri = URI(original)
        val country = Regex("^/[A-Za-z]{2}(?=/)").find(uri.rawPath) ?: return@let original
        val pathStart = original.indexOf(uri.rawPath, original.indexOf("://") + 3)
        original.replaceRange(pathStart + country.range.first + 1, pathStart + country.range.last + 1, "cn")
    }
    fun extract(text: String): String? = Regex("https://music\\.apple\\.com/[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .find(text.take(16384))?.value?.trimEnd('.', ',', ')', '，', '。')?.let(::validate)
    fun validate(value: String?): String? = value?.takeIf { it.length in 1..4096 }?.let {
        runCatching {
            val uri = URI(it)
            it.takeIf { uri.scheme.equals("https", true) && uri.host.equals("music.apple.com", true) &&
                uri.rawUserInfo == null && uri.port in listOf(-1, 443) && !uri.rawPath.isNullOrBlank() }
        }.getOrNull()
    }
}
