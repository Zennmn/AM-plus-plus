package dev.amenhancer.module.lyrics

import java.util.Locale

/**
 * The single serializer that converts the unified [LyricDocument] into the
 * Apple Music Word-TTML accepted by `TTMLParser$TTMLParserNative.songInfoFromTTML`.
 *
 * The emitted structure mirrors the amll-ttml-db files that Apple Music
 * already renders: seconds-based `begin`/`end` on `p` and `span`, the
 * `itunes:timing="Word"` attribute, `itunes:key` line ids, and literal
 * whitespace spans between words.
 *
 * Every text node is XML-escaped; a document without lines serializes to
 * `null` so the caller fails open to the original lyrics.
 */
object WordTtmlSerializer {

    fun serialize(
        document: LyricDocument,
        title: String? = null,
        artist: String? = null,
    ): String? {
        val lines = document.lines
        if (lines.isEmpty()) return null
        val totalEndMs = lines.maxOf { it.endMs }

        return buildString {
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
            append("xmlns:amll=\"http://www.example.com/ns/amll\" ")
            append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" ")
            append("xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
            append("itunes:timing=\"Word\">")
            append("<head><metadata>")
            append("<ttm:agent type=\"person\" xml:id=\"v1\"/>")
            if (!title.isNullOrBlank()) {
                append("<amll:meta key=\"musicName\" value=\"").append(escape(title)).append("\"/>")
            }
            if (!artist.isNullOrBlank()) {
                append("<amll:meta key=\"artists\" value=\"").append(escape(artist)).append("\"/>")
            }
            append("</metadata></head>")
            append("<body dur=\"").append(seconds(totalEndMs)).append("\">")
            append("<div begin=\"0.000\" end=\"").append(seconds(totalEndMs)).append("\">")
            lines.forEachIndexed { index, line ->
                append("<p begin=\"").append(seconds(line.startMs)).append("\" ")
                append("end=\"").append(seconds(line.endMs)).append("\" ")
                append("itunes:key=\"L").append(index + 1).append("\" ttm:agent=\"v1\">")
                line.words.forEach { word ->
                    append("<span begin=\"").append(seconds(word.startMs)).append("\" ")
                    append("end=\"").append(seconds(word.endMs)).append("\">")
                    append(escape(word.text))
                    append("</span>")
                }
                append("</p>")
            }
            append("</div></body></tt>")
        }
    }

    internal fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    internal fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }
}
