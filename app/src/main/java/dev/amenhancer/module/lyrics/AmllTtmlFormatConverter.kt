package dev.amenhancer.module.lyrics

internal data class TtmlFormatConversion(
    val ttml: String,
    val converted: Boolean,
)

/**
 * Rewrites the AMLL TTML format into the Apple Music format that
 * `TTMLParser$TTMLParserNative.songInfoFromTTML` parses.
 *
 * Two structural differences separate the formats:
 *
 * 1. AMLL serializes `<metadata>` and `<div>` with `xmlns=""`, which drops the
 *    whole lyric subtree out of the TTML namespace and leaves Apple's parser
 *    with no lines. Dropping the override restores the inherited namespace.
 * 2. AMLL omits `itunes:timing` on the root, so a document carrying timed
 *    `<span>`s is marked `Word` explicitly rather than left to auto-detection.
 *
 * The rewrite is textual on purpose: whitespace between `<span>` tags carries
 * word separation in word-by-word lyrics, and a DOM round-trip would not
 * preserve it. Only markup is examined; text nodes are copied verbatim.
 */
internal object AmllTtmlFormatConverter {

    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"

    private val EMPTY_DEFAULT_NAMESPACE = Regex("""\s+xmlns\s*=\s*(?:""|'')""")
    private val TIMING_ATTRIBUTE = Regex("""\situnes:timing\s*=""")
    private val ITUNES_DECLARATION = Regex("""\sxmlns:itunes\s*=""")
    private val BEGIN_ATTRIBUTE = Regex("""\sbegin\s*=""")

    fun toAppleFormat(ttml: String): TtmlFormatConversion {
        if (ttml.isEmpty()) return TtmlFormatConversion(ttml, false)
        val markWordTiming = hasTimedSpan(ttml)
        val output = StringBuilder(ttml.length)
        var index = 0
        var rootSeen = false
        var changed = false
        while (index < ttml.length) {
            val open = ttml.indexOf('<', index)
            if (open < 0) {
                output.append(ttml, index, ttml.length)
                break
            }
            output.append(ttml, index, open)
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                output.append(ttml, open, literal)
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) {
                output.append(ttml, open, ttml.length)
                break
            }
            val tag = ttml.substring(open, end + 1)
            val rewritten = when {
                !rootSeen && isElement(tag, "tt") -> {
                    rootSeen = true
                    if (markWordTiming) withWordTiming(tag) else tag
                }
                rootSeen -> withoutEmptyDefaultNamespace(tag)
                else -> tag
            }
            if (rewritten != tag) changed = true
            output.append(rewritten)
            index = end + 1
        }
        return if (changed) {
            TtmlFormatConversion(output.toString(), true)
        } else {
            TtmlFormatConversion(ttml, false)
        }
    }

    private fun withoutEmptyDefaultNamespace(tag: String): String =
        if (tag.startsWith("</")) tag else EMPTY_DEFAULT_NAMESPACE.replace(tag, "")

    private fun withWordTiming(tag: String): String {
        if (TIMING_ATTRIBUTE.containsMatchIn(tag)) return tag
        val additions = buildString {
            if (!ITUNES_DECLARATION.containsMatchIn(tag)) {
                append(" xmlns:itunes=\"").append(ITUNES_NAMESPACE).append('"')
            }
            append(" itunes:timing=\"Word\"")
        }
        val insertAt = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        return tag.substring(0, insertAt).trimEnd() + additions + tag.substring(insertAt)
    }

    private fun hasTimedSpan(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) return false
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) return false
            val tag = text.substring(open, end + 1)
            if (isElement(tag, "span") && BEGIN_ATTRIBUTE.containsMatchIn(tag)) return true
            index = end + 1
        }
        return false
    }

    /** Exclusive end of a comment, CDATA section or processing instruction. */
    private fun literalEnd(text: String, open: Int): Int {
        val terminator = when {
            text.startsWith("<!--", open) -> "-->"
            text.startsWith("<![CDATA[", open) -> "]]>"
            text.startsWith("<?", open) -> "?>"
            else -> return -1
        }
        val close = text.indexOf(terminator, open)
        return if (close < 0) text.length else close + terminator.length
    }

    /** Index of the `>` closing the tag, ignoring `>` inside attribute values. */
    private fun tagEnd(text: String, open: Int): Int {
        var quote: Char? = null
        var index = open + 1
        while (index < text.length) {
            val character = text[index]
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index
            }
            index += 1
        }
        return -1
    }

    private fun isElement(tag: String, name: String): Boolean {
        if (!tag.startsWith("<")) return false
        if (!tag.regionMatches(1, name, 0, name.length, ignoreCase = true)) return false
        val next = tag.getOrNull(1 + name.length) ?: return false
        return next == '>' || next == '/' || next.isWhitespace()
    }
}
