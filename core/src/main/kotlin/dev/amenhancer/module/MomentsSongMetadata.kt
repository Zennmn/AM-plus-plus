package dev.amenhancer.module

import java.net.URI

/** Public song metadata, bound to the shared URL's track ID rather than whatever is playing. */
data class MomentsSongMetadata(val title: String, val artist: String? = null, val artworkUrl: String? = null) {
    companion object {
        fun clean(value: String?): String? = value?.take(1000)?.map { if (it.isISOControl()) ' ' else it }
            ?.joinToString("")?.trim()?.take(200)?.takeIf { it.isNotBlank() }
        fun title(value: String?): String? = clean(value)?.takeUnless {
            it.equals("Apple Music", true) || it.equals("Apple Music - Apple Music", true)
        }
        /** Only public Apple artwork CDN URLs cross the share bridge. */
        fun artwork(value: String?): String? = value?.takeIf { it.length in 1..4096 }?.let {
            runCatching {
                val uri = URI(it)
                it.takeIf { uri.scheme == "https" && uri.host?.lowercase()?.endsWith(".mzstatic.com") == true &&
                    uri.rawUserInfo == null && uri.port in listOf(-1, 443) && !uri.rawPath.isNullOrBlank() && uri.rawFragment == null }
            }.getOrNull()
        }
        fun songId(url: String): Long? {
            if (MomentsLinkContract.validate(url) == null) return null
            val uri = URI(url)
            val path = uri.path.split('/').filter(String::isNotBlank)
            val kind = path.getOrNull(1)
            val raw = when (kind) {
                "song" -> path.lastOrNull()
                "album" -> uri.rawQuery?.split('&')?.filter { it.substringBefore('=') == "i" }
                    ?.singleOrNull()?.substringAfter('=', "")
                else -> null
            }
            return raw?.takeIf { it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 }
        }
        fun fromCurrent(url: String, current: CurrentSongDetails?): MomentsSongMetadata? = current
            ?.takeIf { it.appleMusicId == songId(url) }
            ?.let { details -> title(details.title)?.let { MomentsSongMetadata(it, clean(details.artist)) } }
    }
}
