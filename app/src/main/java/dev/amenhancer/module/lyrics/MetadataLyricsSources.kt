package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/** The two metadata-driven sources exposed by the manual lyrics editor. */
internal enum class MetadataLyricsSource(
    val manifestSource: String,
    val displayName: String,
) {
    QQ_MUSIC(CustomLyricsSources.QQ_MUSIC, "QQ 音乐"),
    NETEASE_CLOUD_MUSIC(CustomLyricsSources.NETEASE_CLOUD_MUSIC, "网易云音乐"),
}

/** User-visible metadata used to search either external platform. */
internal data class MetadataLyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
)

/** A platform result. The external ID is deliberately not an Apple Music ID. */
internal data class MetadataLyricsCandidate(
    val source: MetadataLyricsSource,
    val externalId: String,
    val externalMid: String? = null,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val versionHint: String = "",
) {
    val displayName: String
        get() = buildList {
            add(title)
            artist.takeIf(String::isNotBlank)?.let(::add)
            album.takeIf(String::isNotBlank)?.let(::add)
            versionHint.takeIf(String::isNotBlank)?.let { add("版本：$it") }
            durationMs.takeIf { it > 0L }?.let {
                add("时长 %d:%02d".format(Locale.ROOT, it / 60_000L, (it % 60_000L) / 1_000L))
            }
        }.joinToString(" · ")
}

internal data class MetadataLyricsWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

internal data class MetadataLyricsLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<MetadataLyricsWord>,
)

/** Canonical millisecond representation shared by QRC and YRC/LRC sources. */
internal data class MetadataLyricsDocument(
    val lines: List<MetadataLyricsLine>,
    val translations: List<String?> = emptyList(),
    val wordTimed: Boolean = false,
) {
    fun hasMeaningfulTranslation(): Boolean = translations.any(::isMeaningfulMetadataTranslation)

    fun hasRealWordTiming(): Boolean = wordTimed && lines.any { line ->
        line.words.count { word -> word.text.isNotBlank() && word.endMs > word.startMs } >= 2
    }
}

private fun isMeaningfulMetadataTranslation(value: String?): Boolean {
    val normalized = value.orEmpty().trim().replace('／', '/')
    return normalized.isNotEmpty() && normalized != "//"
}

/**
 * Normalizes platform metadata without changing the strings shown to the user.
 * Matching is intentionally conservative: title and at least one artist token
 * must match before a result can be presented for manual confirmation.
 */
internal object MetadataLyricsMatcher {
    private val punctuation = Regex("[\\p{Punct}\\p{Z}]+")
    private val versionSuffix = Regex(
        "(?i)\\s*[\\[(（【].*?(live|remix|version|edit|acoustic|instrumental|remaster|现场|翻唱|伴奏).*?[\\])）】]\\s*$",
    )
    private val artistSeparators = Regex("(?i)\\s*(?:,|/|\\\\|&|、|;|\\bx\\b|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\b和\\b)\\s*")

    fun normalize(value: String?): String = value.orEmpty()
        .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        .trim()
        .lowercase(Locale.ROOT)
        .replace(punctuation, "")

    fun baseTitle(value: String?): String = value.orEmpty()
        .replace(versionSuffix, "")
        .let(::normalize)

    fun artistTokens(value: String?): Set<String> = artistSeparators
        .split(value.orEmpty())
        .flatMap { part ->
            val normalizedPart = normalize(part)
            buildList {
                normalizedPart.takeIf(String::isNotBlank)?.let(::add)
                part.split(Regex("\\s+")).map(::normalize).forEach { token ->
                    if (token.length >= 2) add(token)
                }
            }
        }
        .filter { it.isNotBlank() }
        .toSet()

    fun titleMatches(query: String, candidate: String): Boolean =
        normalize(query) == normalize(candidate) || baseTitle(query) == baseTitle(candidate)

    fun score(query: MetadataLyricsQuery, candidate: MetadataLyricsCandidate): Int? {
        if (!titleMatches(query.title, candidate.title)) return null
        val requestedArtists = artistTokens(query.artist)
        val candidateArtists = artistTokens(candidate.artist)
        if (requestedArtists.isEmpty() || candidateArtists.isEmpty()) return null
        if (requestedArtists.intersect(candidateArtists).isEmpty()) return null

        var score = 80
        val requestedAlbum = normalize(query.album)
        val candidateAlbum = normalize(candidate.album)
        if (requestedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            if (requestedAlbum == candidateAlbum || baseTitle(requestedAlbum) == baseTitle(candidateAlbum)) {
                score += 10
            }
        }
        val requestedDuration = query.durationMs?.takeIf { it > 0L }
        if (requestedDuration != null && candidate.durationMs > 0L) {
            if (abs(requestedDuration - candidate.durationMs) > 5_000L) return null
            when {
                abs(requestedDuration - candidate.durationMs) <= 2_000L -> score += 10
                abs(requestedDuration - candidate.durationMs) <= 5_000L -> score += 5
            }
        }
        return score
    }

    fun filterAndRank(
        query: MetadataLyricsQuery,
        candidates: List<MetadataLyricsCandidate>,
    ): List<MetadataLyricsCandidate> = candidates
        .mapNotNull { candidate -> score(query, candidate)?.let { candidate to it } }
        .sortedWith(
            compareByDescending<Pair<MetadataLyricsCandidate, Int>> { it.second }
                .thenBy { query.durationMs?.let { duration -> abs(duration - it.first.durationMs) } ?: 0L },
        )
        .map { it.first }
        .distinctBy { it.externalId }
        .take(MAX_CANDIDATES)

    private const val MAX_CANDIDATES = 20
}

/** Converts canonical source lines to the TTML shape Apple Music accepts. */
internal object MetadataLyricsTtmlWriter {
    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"

    fun build(document: MetadataLyricsDocument): String? {
        val lines = document.lines.filter { line -> line.endMs > line.startMs }
        if (lines.isEmpty()) return null
        val wordTimed = document.wordTimed && lines.any { line ->
            line.words.any { word -> word.text.isNotBlank() && word.endMs > word.startMs }
        }
        val translations = if (document.translations.any { !it.isNullOrBlank() }) {
            lines.indices.map { index ->
                document.translations.getOrNull(index)
                    ?.takeIf(::isMeaningfulMetadataTranslation)
                    ?: run {
                        lines[index].words.joinToString("") { word -> word.text }
                    }
            }
        } else {
            emptyList()
        }
        val timing = if (wordTimed) "Word" else "Line"
        val firstBegin = lines.minOfOrNull { it.startMs } ?: 0L
        val lastEnd = lines.maxOfOrNull { it.endMs } ?: firstBegin + 1L
        return buildString {
            append("<tt xmlns=\"").append(TTML_NAMESPACE)
                .append("\" xmlns:itunes=\"").append(ITUNES_NAMESPACE)
                .append("\" xmlns:ttm=\"").append(TTM_NAMESPACE)
                .append("\" itunes:timing=\"").append(timing)
                .append("\" xml:lang=\"zh-Hans\" xml:space=\"preserve\">")
            append("<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/>")
            if (translations.isNotEmpty()) {
                append("<iTunesMetadata xmlns=\"").append(ITUNES_NAMESPACE).append("\">")
                    .append("<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">")
                lines.indices.forEach { index ->
                    append("<text for=\"L").append(index + 1).append("\">")
                        .append(escape(translations[index])).append("</text>")
                }
                append("</translation></translations></iTunesMetadata>")
            } else {
                append("<iTunesMetadata xmlns=\"").append(ITUNES_NAMESPACE).append("\"/>")
            }
            append("</metadata></head><body dur=\"").append(duration(lastEnd)).append("\">")
                .append("<div begin=\"").append(seconds(firstBegin))
                .append("\" end=\"").append(seconds(lastEnd)).append("\">")
            lines.forEachIndexed { index, line ->
                val lineEnd = line.endMs.coerceAtLeast(line.startMs + 1L)
                append("<p begin=\"").append(seconds(line.startMs))
                    .append("\" end=\"").append(seconds(lineEnd))
                    .append("\" ttm:agent=\"v1\" itunes:key=\"L").append(index + 1).append("\">")
                if (wordTimed) {
                    val words = line.words.filter { it.text.isNotEmpty() }
                    if (words.isEmpty()) {
                        append("<span begin=\"").append(seconds(line.startMs))
                            .append("\" end=\"").append(seconds(lineEnd)).append("\"></span>")
                    } else {
                        words.forEach { word ->
                            val end = word.endMs.coerceAtLeast(word.startMs + 1L)
                            append("<span begin=\"").append(seconds(word.startMs))
                                .append("\" end=\"").append(seconds(end)).append("\">")
                                .append(escape(word.text)).append("</span>")
                        }
                    }
                } else {
                    append(escape(line.words.joinToString("") { it.text }))
                }
                append("</p>")
            }
            append("</div></body></tt>")
        }
    }

    fun seconds(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        return "%d.%03d".format(Locale.ROOT, total / 1_000L, total % 1_000L)
    }

    private fun duration(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        return "%d:%02d.%03d".format(
            Locale.ROOT,
            total / 60_000L,
            (total % 60_000L) / 1_000L,
            total % 1_000L,
        )
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}

/** QRC/YRC/LRC parsing and ±1 second translation alignment. */
internal object MetadataLyricsParser {
    private val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val qrcWordPattern = Regex("((?:(?!\\(\\d+,\\d+\\)).)*)\\((\\d+),(\\d+)\\)")
    private val yrcWordPattern = Regex("\\((\\d+),(\\d+),\\d+\\)([^()]*)")
    private val lrcTimePattern = Regex("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]")
    private val qrcXmlPattern = Regex("(?s)<Lyric_1\\s+LyricType=\\\"1\\\"\\s+LyricContent=\\\"(.*?)\\\"/>")

    fun parseQrc(original: String?, translated: String? = null): MetadataLyricsDocument? {
        val raw = original?.takeIf(String::isNotBlank) ?: return null
        val content = qrcXmlPattern.find(raw)?.groupValues?.getOrNull(1) ?: raw
        val (lines, hasWordTiming) = parseQrcTimedLines(content)
        if (lines.isEmpty()) return null
        return MetadataLyricsDocument(lines, align(lines, parseAuxiliary(translated)), hasWordTiming)
    }

    fun parseYrc(
        yrc: String?,
        lrc: String?,
        translated: String? = null,
    ): MetadataLyricsDocument? {
        val yrcRaw = yrc?.takeIf(String::isNotBlank)
        var (lines, hasWordTiming) = if (yrcRaw != null) {
            parseTimedLines(yrcRaw, yrcWordPattern)
        } else {
            parseLrc(lrc.orEmpty()) to false
        }
        if (lines.isEmpty() && !lrc.isNullOrBlank()) {
            lines = parseLrc(lrc.orEmpty())
            hasWordTiming = false
        }
        if (lines.isEmpty()) return null
        return MetadataLyricsDocument(lines, align(lines, parseLrc(translated.orEmpty())), hasWordTiming)
    }

    fun parseLrcDocument(original: String?, translated: String? = null): MetadataLyricsDocument? {
        val lines = parseLrc(original.orEmpty())
        if (lines.isEmpty()) return null
        return MetadataLyricsDocument(lines, align(lines, parseLrc(translated.orEmpty())), false)
    }

    private fun parseAuxiliary(raw: String?): List<MetadataLyricsLine> {
        val value = raw?.takeIf(String::isNotBlank) ?: return emptyList()
        val content = qrcXmlPattern.find(value)?.groupValues?.getOrNull(1) ?: value
        val qrc = parseQrcTimedLines(content).first
        return qrc.ifEmpty { parseLrc(value) }
    }

    /** QQ QRC associates each timestamp with the text immediately before it. */
    private fun parseQrcTimedLines(content: String): Pair<List<MetadataLyricsLine>, Boolean> {
        val lines = mutableListOf<MetadataLyricsLine>()
        var hasWordTiming = false
        content.lineSequence().forEach { rawLine ->
            val match = linePattern.matchEntire(rawLine.trim()) ?: return@forEach
            val lineStart = match.groupValues[1].toLongOrNull() ?: return@forEach
            val lineDuration = match.groupValues[2].toLongOrNull() ?: return@forEach
            val lineEnd = lineStart + lineDuration
            val text = match.groupValues[3]
            val words = mutableListOf<MetadataLyricsWord>()
            var cursor = 0
            qrcWordPattern.findAll(text).forEach { wordMatch ->
                val wordText = wordMatch.groupValues[1]
                val wordStart = wordMatch.groupValues[2].toLongOrNull() ?: return@forEach
                val wordDuration = wordMatch.groupValues[3].toLongOrNull() ?: return@forEach
                if (wordText.isNotEmpty() && wordText != "\r") {
                    words += MetadataLyricsWord(wordStart, wordStart + wordDuration, wordText)
                }
                cursor = wordMatch.range.last + 1
                hasWordTiming = true
            }
            val remainder = text.substring(cursor)
            if (remainder.isNotBlank()) {
                val start = words.lastOrNull()?.endMs ?: lineStart
                words += MetadataLyricsWord(start, lineEnd.coerceAtLeast(start + 1L), remainder.trim())
            }
            if (words.isEmpty() && text.isNotBlank()) {
                words += MetadataLyricsWord(lineStart, lineEnd, text.trim())
            }
            if (words.isNotEmpty()) lines += MetadataLyricsLine(lineStart, lineEnd, words)
        }
        return lines.sortedBy { it.startMs } to hasWordTiming
    }

    private fun parseTimedLines(
        content: String,
        wordPattern: Regex,
    ): Pair<List<MetadataLyricsLine>, Boolean> {
        val lines = mutableListOf<MetadataLyricsLine>()
        var hasWordTiming = false
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val lineMatch = linePattern.matchEntire(line) ?: return@forEach
            val start = lineMatch.groupValues[1].toLongOrNull() ?: return@forEach
            val duration = lineMatch.groupValues[2].toLongOrNull() ?: return@forEach
            val text = lineMatch.groupValues[3]
            val words = mutableListOf<MetadataLyricsWord>()
            var cursor = 0
            var lastEnd = start
            wordPattern.findAll(text).forEach { match ->
                val wordStart = match.groupValues[1].toLongOrNull() ?: return@forEach
                val wordDuration = match.groupValues[2].toLongOrNull() ?: return@forEach
                val prefix = text.substring(cursor, match.range.first)
                if (prefix.isNotBlank()) words += MetadataLyricsWord(lastEnd, wordStart.coerceAtLeast(lastEnd), prefix.trimStart())
                words += MetadataLyricsWord(wordStart, wordStart + wordDuration, match.groupValues[3])
                lastEnd = wordStart + wordDuration
                cursor = match.range.last + 1
                hasWordTiming = true
            }
            val remainder = text.substring(cursor)
            if (remainder.isNotBlank()) words += MetadataLyricsWord(lastEnd, start + duration, remainder.trim())
            if (words.isEmpty() && text.isNotBlank()) words += MetadataLyricsWord(start, start + duration, text.trim())
            if (words.isNotEmpty()) lines += MetadataLyricsLine(start, start + duration, words)
        }
        return lines.sortedBy { it.startMs } to hasWordTiming
    }

    private fun parseLrc(content: String): List<MetadataLyricsLine> {
        val timed = mutableListOf<Pair<Long, String>>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val tags = lrcTimePattern.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val body = line.substring(tags.last().range.last + 1).trim()
            if (body.isBlank()) return@forEach
            tags.forEach { tag ->
                val minutes = tag.groupValues[1].toLongOrNull() ?: 0L
                val seconds = tag.groupValues[2].toLongOrNull() ?: 0L
                val millis = tag.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                timed += minutes * 60_000L + seconds * 1_000L + millis to body
            }
        }
        val sorted = timed.sortedBy { it.first }
        return sorted.mapIndexed { index, (start, text) ->
            val end = sorted.getOrNull(index + 1)?.first?.coerceAtLeast(start + 1L) ?: start + 3_000L
            MetadataLyricsLine(start, end, listOf(MetadataLyricsWord(start, end, text)))
        }
    }

    private fun align(
        original: List<MetadataLyricsLine>,
        auxiliary: List<MetadataLyricsLine>,
    ): List<String?> {
        if (auxiliary.isEmpty()) return emptyList()
        val used = BooleanArray(auxiliary.size)
        return original.map { line ->
            val index = auxiliary.indices
                .filter { !used[it] && abs(auxiliary[it].startMs - line.startMs) <= MAX_ALIGNMENT_OFFSET_MS }
                .minWithOrNull(compareBy { abs(auxiliary[it].startMs - line.startMs) })
            if (index == null) null else {
                used[index] = true
                auxiliary[index].words.joinToString("") { it.text.trim() }.trim().ifBlank { null }
            }
        }
    }

    private const val MAX_ALIGNMENT_OFFSET_MS = 1_000L
}

/** Small, anonymous QQ Lite session; it contains no user account credentials. */
internal data class QqMusicSession(
    val uid: String,
    val sid: String,
    val userIp: String,
    val expiresAtMs: Long,
)

/** Process-local or file-backed storage seam for the QQ Lite anonymous session. */
internal interface QqMusicSessionStore {
    fun load(): QqMusicSession?
    fun save(session: QqMusicSession): Boolean
    fun clear()
}

internal object DisabledQqMusicSessionStore : QqMusicSessionStore {
    override fun load(): QqMusicSession? = null
    override fun save(session: QqMusicSession): Boolean = false
    override fun clear() = Unit
}

/** Bounded, best-effort cache used only to avoid re-registering the same anonymous QQ client. */
internal class FileQqMusicSessionStore(
    private val file: File,
) : QqMusicSessionStore {
    @Synchronized
    override fun load(): QqMusicSession? = runCatching {
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        QqMusicSession(
            uid = json.optString("uid").trim(),
            sid = json.optString("sid").trim(),
            userIp = json.optString("userIp").trim(),
            expiresAtMs = json.optLong("expiresAtMs", 0L),
        ).takeIf { session ->
            session.uid.isNotBlank() && session.sid.isNotBlank() && session.userIp.isNotBlank() &&
                session.expiresAtMs > 0L
        }
    }.getOrNull()

    @Synchronized
    override fun save(session: QqMusicSession): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val payload = JSONObject()
            .put("uid", session.uid)
            .put("sid", session.sid)
            .put("userIp", session.userIp)
            .put("expiresAtMs", session.expiresAtMs)
            .toString()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(payload, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            temporary.delete()
        }
        true
    }.getOrDefault(false)

    @Synchronized
    override fun clear() {
        runCatching { file.delete() }
    }
}

/** QQ Music client using direct anonymous MusicU requests. */
internal class QqMusicLyricsClient(
    private val transport: LyricHttpTransport,
    @Suppress("UNUSED_PARAMETER") sessionStore: QqMusicSessionStore = DisabledQqMusicSessionStore,
    @Suppress("UNUSED_PARAMETER") nowMs: () -> Long = System::currentTimeMillis,
) {
    private val comm = mapOf(
        "ct" to "11",
        "cv" to "1003006",
        "v" to "1003006",
        "os_ver" to "15",
        "phonetype" to "24122RKC7C",
        "tmeAppID" to "qqmusiclight",
        "nettype" to "NETWORK_WIFI",
    )
    fun search(query: MetadataLyricsQuery): List<MetadataLyricsCandidate> = runCatching {
        val param = JSONObject()
            .put("search_id", Random.nextLong(10_000_000_000_000_000L, 90_000_000_000_000_000L).toString())
            .put("remoteplace", "search.android.keyboard")
            .put("query", "${query.title} ${query.artist}".trim())
            .put("search_type", 0)
            .put("num_per_page", 20)
            .put("page_num", 1)
            .put("highlight", 0)
            .put("nqc_flag", 0)
            .put("page_id", 1)
            .put("grp", 1)
        val body = searchBody(param) ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until body.length()) {
                val item = body.optJSONObject(index) ?: continue
                val id = item.optLong("id", 0L).takeIf { it > 0L } ?: continue
                val singers = item.optJSONArray("singer").joinStrings("name")
                val album = item.optJSONObject("album")
                add(
                    MetadataLyricsCandidate(
                        source = MetadataLyricsSource.QQ_MUSIC,
                        externalId = id.toString(),
                        externalMid = item.optString("mid").trim().takeIf(String::isNotBlank),
                        title = item.optString("title").trim(),
                        artist = singers,
                        album = album?.optString("name").orEmpty().trim(),
                        durationMs = item.optLong("interval", 0L).coerceAtLeast(0L) * 1_000L,
                        versionHint = item.optString("subtitle").trim(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun searchBody(param: JSONObject): JSONArray? =
        request("DoSearchForQQMusicLite", "music.search.SearchCgiService", param)
            ?.optJSONObject("req_0")?.optJSONObject("data")
            ?.optJSONObject("body")?.optJSONArray("item_song")

    fun fetch(candidate: MetadataLyricsCandidate): MetadataLyricsDocument? = runCatching {
        val id = candidate.externalId.toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null
        val body = JSONObject()
            .put("songID", id)
            .put("songName", base64(candidate.title))
            .put("albumName", base64(candidate.album))
            .put("singerName", base64(candidate.artist))
            .put("crypt", 1).put("qrc", 1).put("trans", 1).put("roma", 1)
            .put("cv", 2111).put("ct", 19)
            .put("lrc_t", 0).put("qrc_t", 0).put("roma_t", 0).put("trans_t", 0)
            .put("type", 0).put("interval", candidate.durationMs / 1_000L)
        val data = request("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", body)
            ?.optJSONObject("req_0")?.optJSONObject("data") ?: return@runCatching null
        val original = decodeQrc(data.optString("lyric"))
        val translated = decodeQrc(data.optString("trans"))
        MetadataLyricsParser.parseQrc(original, translated)
            ?: MetadataLyricsParser.parseLrcDocument(original, translated)
    }.getOrNull()

    private fun request(method: String, name: String, param: JSONObject): JSONObject? =
        post(module(method, name, param))

    private fun post(body: JSONObject): JSONObject? = transport.post(
            QQ_ENDPOINT,
            body.toString().toByteArray(Charsets.UTF_8),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "User-Agent" to QQ_LITE_USER_AGENT,
                "Referer" to "https://y.qq.com/",
                "Cookie" to "tmeLoginType=-1;",
            ),
        )?.takeIf { it.statusCode in 200..299 }
            ?.body?.toString(Charsets.UTF_8)?.let(::JSONObject)

    private fun module(method: String, name: String, param: JSONObject): JSONObject = JSONObject()
        .put("comm", JSONObject(comm))
        .put("req_0", JSONObject().put("method", method).put("module", name).put("param", param))

    private fun decodeQrc(value: String): String = when {
        value.isBlank() -> ""
        value.trimStart().startsWith("[") || value.contains("<Lyric_1") -> value
        else -> QrcCrypto.decrypt(value)
    }

    private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val QQ_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QQ_LITE_USER_AGENT = "okhttp/3.14.9"
    }
}

/** NetEase Cloud Music EAPI client with an in-memory anonymous session. */
internal class NeteaseCloudLyricsClient(
    private val transport: LyricHttpTransport,
) {
    private val cookieMap = linkedMapOf<String, String>()
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private val clientSign = generateClientSign()
    private var userId = 0L
    private var initialized = false

    @Synchronized
    fun search(query: MetadataLyricsQuery): List<MetadataLyricsCandidate> = runCatching {
        val raw = doRequest(
            path = SEARCH_PATH,
            params = JSONObject()
                .put("limit", "20")
                .put("offset", "0")
                .put("keyword", "${query.title} ${query.artist}".trim())
                .put("scene", "NORMAL")
                .put("needCorrect", "true"),
        ) ?: return@runCatching emptyList()
        val resources = JSONObject(raw).optJSONObject("data")?.optJSONArray("resources")
            ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until resources.length()) {
                val song = resources.optJSONObject(index)
                    ?.optJSONObject("baseInfo")?.optJSONObject("simpleSongData") ?: continue
                val id = song.optLong("id", 0L).takeIf { it > 0L } ?: continue
                val album = song.optJSONObject("al")
                add(
                    MetadataLyricsCandidate(
                        source = MetadataLyricsSource.NETEASE_CLOUD_MUSIC,
                        externalId = id.toString(),
                        title = song.optString("name").trim(),
                        artist = song.optJSONArray("ar").joinStrings("name"),
                        album = album?.optString("name").orEmpty().trim(),
                        durationMs = song.optLong("dt", 0L).coerceAtLeast(0L),
                        versionHint = song.optJSONArray("alia").joinStrings(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun fetch(candidate: MetadataLyricsCandidate): MetadataLyricsDocument? = runCatching {
        val id = candidate.externalId.toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null
        val raw = doRequest(
            path = LYRIC_PATH,
            params = JSONObject().put("id", id).put("lv", "-1").put("tv", "-1").put("rv", "-1").put("yv", "-1"),
        ) ?: return@runCatching null
        val response = JSONObject(raw)
        MetadataLyricsParser.parseYrc(
            yrc = response.optJSONObject("yrc")?.optString("lyric"),
            lrc = response.optJSONObject("lrc")?.optString("lyric"),
            translated = response.optJSONObject("tlyric")?.optString("lyric"),
        )
    }.getOrNull()

    private fun doRequest(path: String, params: JSONObject, attempt: Int = 0): String? {
        if (!ensureSession()) return null
        val payload = JSONObject(params.toString()).put(
            "header",
            JSONObject()
                .put("clientSign", clientSign)
                .put("osver", OS_VERSION)
                .put("deviceId", deviceId)
                .put("os", "pc")
                .put("appver", APP_VERSION)
                .put("requestId", System.currentTimeMillis().toString())
                .toString(),
        ).put("e_r", true)
        val encryptPath = path.replace("/eapi/", "/api/")
        val encrypted = NeteaseCrypto.encryptParams(encryptPath, payload.toString())
        val response = transport.post(
            NETEASE_ENDPOINT + path,
            "params=${encrypted.toHexUppercase()}".toByteArray(Charsets.UTF_8),
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to USER_AGENT,
                "Referer" to "https://music.163.com/",
                "Cookie" to cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" },
            ),
        ) ?: return null
        val decrypted = response.body?.let(::decodeNeteaseBody).orEmpty()
        val code = runCatching { JSONObject(decrypted).optInt("code", 0) }.getOrDefault(0)
        if ((response.statusCode == 301 || response.statusCode == 401 || code == 301 || code == 401) && attempt == 0) {
            initialized = false
            cookieMap.clear()
            return doRequest(path, params, 1)
        }
        return decrypted.takeIf(String::isNotBlank)
    }

    private fun ensureSession(): Boolean {
        if (initialized && userId > 0L) return true
        val preCookies = linkedMapOf(
            "os" to "pc",
            "deviceId" to deviceId,
            "osver" to "Microsoft-Windows-10--build-19045-64bit",
            "clientSign" to clientSign,
            "channel" to "netease",
            "mode" to "ASUS ROG STRIX Z790",
            "appver" to APP_VERSION,
        )
        val params = JSONObject()
            .put("username", anonymousUsername(deviceId))
            .put("e_r", true)
            .put(
                "header",
                JSONObject()
                    .put("clientSign", clientSign)
                    .put("osver", preCookies.getValue("osver"))
                    .put("deviceId", deviceId)
                    .put("os", "pc")
                    .put("appver", APP_VERSION)
                    .put("requestId", System.currentTimeMillis().toString())
                    .toString(),
            )
        val encrypted = NeteaseCrypto.encryptParams("/api/register/anonimous", params.toString())
        val response = transport.post(
            NETEASE_ENDPOINT + REGISTER_PATH,
            "params=${encrypted.toHexUppercase()}".toByteArray(Charsets.UTF_8),
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to USER_AGENT,
                "Referer" to "https://music.163.com/",
                "Cookie" to preCookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            ),
        ) ?: return false
        val decrypted = response.body?.let(::decodeNeteaseBody).orEmpty()
        val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: return false
        if (json.optInt("code", 0) != 200) return false
        userId = json.optLong("userId", 0L)
        if (userId <= 0L) return false
        cookieMap.clear()
        cookieMap.putAll(preCookies)
        response.headers.cookieValues().forEach { (name, value) -> cookieMap[name] = value }
        cookieMap["WNMCID"] = "${randomLetters(6)}.${System.currentTimeMillis()}.01.0"
        initialized = true
        return true
    }

    private fun decodeNeteaseBody(body: ByteArray): String {
        val text = body.toString(Charsets.UTF_8)
        return if (text.trimStart().startsWith("{")) text else NeteaseCrypto.aesDecrypt(body)
    }

    private fun anonymousUsername(value: String): String {
        val key = "3go8&$8*3*3h0k(2)2"
        val xored = value.mapIndexed { index, char -> (char.code xor key[index % key.length].code).toChar() }
            .joinToString("")
        val digest = MessageDigest.getInstance("MD5").digest(xored.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString("$value ${Base64.getEncoder().encodeToString(digest)}".toByteArray())
    }

    private fun generateClientSign(): String = buildString {
        repeat(6) { if (it > 0) append(':'); append("%02X".format(Random.nextInt(256))) }
        append("@@@")
        append(randomLetters(8, upper = true))
        append("@@@@@@")
        repeat(64) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    private fun randomLetters(length: Int, upper: Boolean = false): String {
        val alphabet = if (upper) "ABCDEFGHIJKLMNOPQRSTUVWXYZ" else "abcdefghijklmnopqrstuvwxyz"
        return buildString { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private companion object {
        const val NETEASE_ENDPOINT = "https://interface.music.163.com"
        const val REGISTER_PATH = "/eapi/register/anonimous"
        const val SEARCH_PATH = "/eapi/search/song/list/page"
        const val LYRIC_PATH = "/eapi/song/lyric/v1"
        const val APP_VERSION = "3.1.3.203419"
        const val OS_VERSION = "Microsoft-Windows-10--build-19045-64bit"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/$APP_VERSION"
    }
}

/** Coordinates search, strict candidate filtering and source-specific conversion. */
internal class MetadataLyricsImporter(
    private val qq: QqMusicLyricsClient,
    private val netease: NeteaseCloudLyricsClient,
) {
    fun searchCandidates(
        source: MetadataLyricsSource,
        query: MetadataLyricsQuery,
    ): List<MetadataLyricsCandidate> {
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        val raw = when (source) {
            MetadataLyricsSource.QQ_MUSIC -> qq.search(query)
            MetadataLyricsSource.NETEASE_CLOUD_MUSIC -> netease.search(query)
        }
        return MetadataLyricsMatcher.filterAndRank(query, raw)
    }

    fun importCandidate(candidate: MetadataLyricsCandidate): CustomLyricsOnlineImportResult {
        val document = fetchDocument(candidate)
            ?: return CustomLyricsOnlineImportResult.Failed("${candidate.source.displayName} 未找到可用逐字歌词")
        val ttml = MetadataLyricsTtmlWriter.build(document)
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("歌词时间轴无效，未导入")
        return CustomLyricsOnlineImportResult.Imported(
            ttml = ttml,
            source = candidate.source.manifestSource,
        )
    }

    fun fetchDocument(candidate: MetadataLyricsCandidate): MetadataLyricsDocument? = runCatching {
        when (candidate.source) {
            MetadataLyricsSource.QQ_MUSIC -> qq.fetch(candidate)
            MetadataLyricsSource.NETEASE_CLOUD_MUSIC -> netease.fetch(candidate)
        }
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() }

    companion object {
        fun withTransport(
            transport: LyricHttpTransport,
            qqSessionStore: QqMusicSessionStore = DisabledQqMusicSessionStore,
        ): MetadataLyricsImporter = MetadataLyricsImporter(
            qq = QqMusicLyricsClient(transport, qqSessionStore),
            netease = NeteaseCloudLyricsClient(transport),
        )
    }
}

internal data class AutomaticMetadataLyricsResult(
    val source: String,
    val ttml: String,
    val displayName: String,
)

internal data class AutomaticMetadataLyricsSource(
    val source: MetadataLyricsSource,
    val search: (MetadataLyricsQuery) -> List<MetadataLyricsCandidate>,
    val fetch: (MetadataLyricsCandidate) -> MetadataLyricsDocument?,
)

/** Source precedence for automatic metadata fallback after Apple-ID sources miss. */
internal fun automaticMetadataSourceOrder(): List<MetadataLyricsSource> = listOf(
    MetadataLyricsSource.NETEASE_CLOUD_MUSIC,
    MetadataLyricsSource.QQ_MUSIC,
)

/** Strict metadata fallback after playback metadata is stable. */
internal class AutomaticMetadataLyricsResolver(
    private val sources: List<AutomaticMetadataLyricsSource>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val logger: (String) -> Unit = {},
) {
    private val hardDeadlineExecutor = ThreadPoolExecutor(
        0,
        1,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "ampp-metadata-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun fetch(query: MetadataLyricsQuery): AutomaticMetadataLyricsResult? {
        if (query.title.isBlank() || query.artist.isBlank() || query.durationMs == null || query.durationMs <= 0L) {
            logger(
                "metadata automatic lyrics rejected incomplete query: " +
                    "title=${query.title}, artist=${query.artist}, duration=${query.durationMs}",
            )
            return null
        }
        val future = runCatching {
            hardDeadlineExecutor.submit<AutomaticMetadataLyricsResult?> { fetchWithinBudget(query) }
        }.getOrElse {
            logger("metadata automatic lyrics worker unavailable")
            return null
        }
        return try {
            future.get(budgetMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            logger("metadata automatic lyrics hard deadline expired after ${budgetMs}ms")
            null
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun fetchWithinBudget(query: MetadataLyricsQuery): AutomaticMetadataLyricsResult? {
        val startedAt = nowMs()
        val durationMs = query.durationMs ?: return null
        sources.forEach { source ->
            if (expired(startedAt)) {
                logger("metadata automatic lyrics budget expired before ${source.source.displayName}")
                return null
            }
            val first = runCatching { source.search(query).firstOrNull() }.getOrNull()
                ?: return@forEach
            if (kotlin.math.abs(first.durationMs - durationMs) > MAX_DURATION_DELTA_MS) {
                logger(
                    "metadata automatic lyrics rejected duration source=${source.source.displayName}, " +
                        "apple=${query.durationMs}, candidate=${first.durationMs}",
                )
                return@forEach
            }
            val document = runCatching { source.fetch(first) }.getOrNull()
                ?: return@forEach
            if (expired(startedAt) || !document.hasRealWordTiming()) {
                logger("metadata automatic lyrics rejected non-Word source=${source.source.displayName}")
                return@forEach
            }
            if (MetadataLyricsLanguagePolicy.requiresTranslation(document) &&
                !document.hasMeaningfulTranslation()
            ) {
                logger("metadata automatic lyrics rejected missing translation source=${source.source.displayName}")
                return@forEach
            }
            val ttml = MetadataLyricsTtmlWriter.build(document)
                ?.takeIf(TtmlInputPolicy::isAcceptable)
                ?: return@forEach
            return AutomaticMetadataLyricsResult(
                source = source.source.manifestSource,
                ttml = ttml,
                displayName = "${first.title} - ${first.artist}",
            ).also {
                logger(
                    "metadata automatic lyrics accepted source=${source.source.displayName}, " +
                        "id=${first.externalId}, duration=${first.durationMs}",
                )
            }
        }
        return null
    }

    private fun expired(startedAt: Long): Boolean = nowMs() - startedAt > budgetMs

    companion object {
        private const val MAX_DURATION_DELTA_MS = 1_000L
        private const val DEFAULT_BUDGET_MS = 10_000L
        private const val WORKER_KEEP_ALIVE_SECONDS = 15L

        fun fixed(
            importer: MetadataLyricsImporter,
            logger: (String) -> Unit = {},
        ): AutomaticMetadataLyricsResolver =
            AutomaticMetadataLyricsResolver(
                automaticMetadataSourceOrder().map { source ->
                    AutomaticMetadataLyricsSource(
                        source,
                        search = { query -> importer.searchCandidates(source, query) },
                        fetch = importer::fetchDocument,
                    )
                },
                logger = logger,
            )
    }
}

/** Conservative lyric-script classifier; credits do not outweigh a Chinese lyric body. */
internal object MetadataLyricsLanguagePolicy {
    fun requiresTranslation(document: MetadataLyricsDocument): Boolean {
        var han = 0
        var foreignLetters = 0
        var kanaOrHangul = false
        document.lines.forEach { line ->
            line.words.forEach { word ->
                val text = word.text
                var offset = 0
                while (offset < text.length) {
                    val codePoint = text.codePointAt(offset)
                    when (Character.UnicodeScript.of(codePoint)) {
                        Character.UnicodeScript.HAN -> han += 1
                        Character.UnicodeScript.HIRAGANA,
                        Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL,
                        -> {
                            foreignLetters += 1
                            kanaOrHangul = true
                        }
                        else -> if (Character.isLetter(codePoint)) foreignLetters += 1
                    }
                    offset += Character.charCount(codePoint)
                }
            }
        }
        if (kanaOrHangul) return true
        val totalLetters = han + foreignLetters
        if (totalLetters == 0) return false
        return han == 0 || foreignLetters * 5 > totalLetters
    }
}

private object NeteaseCrypto {
    private const val KEY = "e82ckenh8dichen8"
    private const val DIGEST = "nobody%suse%smd5forencrypt"

    fun encryptParams(path: String, json: String): ByteArray {
        val digest = md5(DIGEST.format(path, json))
        return aesEncrypt("$path-36cd479b6b5-$json-36cd479b6b5-$digest")
    }

    fun aesDecrypt(bytes: ByteArray): String = runCatching {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        cipher.doFinal(bytes).toString(Charsets.UTF_8)
    }.getOrDefault("")

    private fun aesEncrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun JSONArray?.joinStrings(key: String? = null): String {
    if (this == null) return ""
    return buildList {
        for (index in 0 until length()) {
            val value = opt(index)
            val text = if (key == null) value?.toString().orEmpty() else (value as? JSONObject)?.optString(key).orEmpty()
            text.trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }.joinToString(" / ")
}

private fun Map<String, List<String>>.cookieValues(): Map<String, String> = entries
    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
    .flatMap { (_, values) -> values }
    .mapNotNull { line ->
        val pair = line.substringBefore(';').split('=', limit = 2)
        pair.getOrNull(0)?.trim()?.takeIf(String::isNotBlank)
            ?.let { name -> pair.getOrNull(1)?.trim()?.let { value -> name to value } }
    }
    .toMap()

private fun ByteArray.toHexUppercase(): String = joinToString("") { "%02x".format(it).uppercase(Locale.ROOT) }
