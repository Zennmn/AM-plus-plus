package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingMode
import java.util.Locale

/**
 * One OpenAI-compatible translation pass over a Line/Word TTML document.
 *
 * The model only sees the source vocals and request IDs in [translationLines].
 * Body word timings, agents, whitespace, romanization and vocals stay in place.
 * [withTranslations] writes the answers as Apple's native subtitle track
 * (`<text for="itunes:key">` entries under `<translation type="subtitle"
 * xml:lang="zh-Hans">`), which is the shape Apple reads, instead of asking
 * the model for XML.
 *
 * Eligibility is decided once in [prepare]:
 *
 * - The root `<tt>` language is authoritative. Any Chinese declaration -
 *   `zh`, `zh-Hans`, `zh-Hant`, a region subtag, or a Chinese topolect such
 *   as `yue`/`cmn`/`nan` - rejects the document, because those lyrics already
 *   read as the fixed Simplified Chinese target. [TtmlTimingPolicy] owns that
 *   alias list so the fallback and the native pipeline cannot drift apart.
 * - An existing translation track rejects the document too: any
 *   `<translation>` element, whatever language it declares, and any inline
 *   AMLL `ttm:role="x-translation"` span, nested in a background voice or not.
 *   The host shows those instead.
 * - Only Line/Word documents qualify. Every `<p>` needs a readable `begin`.
 *   Existing line keys must be unique; missing keys receive stable identifiers
 *   only in the translated copy so Apple's subtitle references can resolve.
 * - Without a language declaration the text is judged conservatively, so kana
 *   keeps Japanese lyrics out of the Chinese reading even though they carry
 *   Han characters.
 *
 * [translationLines] holds vocals worth translating: non-blank, carrying at
 * least one letter, and not Chinese once the declared language has had its
 * say. Answers match request IDs, so repeated lyrics remain distinct. Chinese
 * lines inside a foreign song, humming and blanks retain their place in the track.
 */
public class LlmLyricsDocument private constructor(
    private val ttml: String,
    private val lines: List<LlmLine>,
    private val insertion: LlmInsertion,
    /** Main request IDs in paragraph order, each with its own backing vocals. */
    public val translationLines: Map<String, LlmLyricsLine>,
    /** The declared root `<tt>` language, or `null` when the header omits it. */
    public val sourceLanguage: String?,
) {

    /**
     * Returns the document with an Apple-native `zh-Hans` subtitle track added,
     * or `null` when the IDs or answers do not match, or [TtmlInputPolicy]
     * would refuse the result. The caller keeps the original lyrics in that case.
     */
    public fun withTranslations(translations: Map<String, LlmLyricsLine>): String? {
        if (translations.keys != translationLines.keys) return null
        for ((id, source) in translationLines) {
            val answer = translations.getValue(id)
            if ((answer.main == null) != (source.main == null) || answer.main?.isBlank() == true) return null
            if (answer.background.keys != source.background.keys) return null
        }
        val track = buildString {
            append("<translation type=\"").append(TRANSLATION_TYPE)
            append("\" xml:lang=\"").append(TRANSLATION_LANGUAGE).append("\">")
            var paragraphTranslation: LlmLyricsLine? = null
            lines.forEachIndexed { index, line ->
                if (line.background) {
                    val answer = paragraphTranslation?.background?.get(line.id)
                    val text = if (answer != null) {
                        formatBackgroundSubtitle(answer) ?: return null
                    } else {
                        formatBackgroundSubtitle(line.text) ?: line.text.ifBlank { ABSENT_TEXT }
                    }
                    append(" <span xmlns=\"").append(TTML_NAMESPACE)
                    append("\" xmlns:ttm=\"").append(TTML_METADATA_NAMESPACE)
                    append("\" ttm:role=\"").append(ROLE_BACKGROUND).append("\">")
                    append(escapeText(text)).append("</span>")
                } else {
                    paragraphTranslation = translations[line.id]
                    if (index > 0) append("</text>")
                    append("<text for=\"").append(escapeAttribute(line.key)).append("\">")
                    append(escapeText(paragraphTranslation?.main?.trim() ?: line.text.ifBlank { ABSENT_TEXT }))
                }
            }
            append("</text></translation>")
        }
        val output = buildString {
            append(ttml, 0, insertion.start)
            append(insertion.open).append(track).append(insertion.close)
            var copiedThrough = insertion.end
            lines.forEach { line ->
                if (line.keyInsertion >= 0) {
                    if (line.keyInsertion < copiedThrough) return null
                    append(ttml, copiedThrough, line.keyInsertion)
                    val paragraphStart = ttml.lastIndexOf('<', line.keyInsertion)
                    val namespace = attribute(
                        ttml.substring(paragraphStart, line.keyInsertion),
                        ITUNES_NAMESPACE_ATTRIBUTE,
                    )
                    if (namespace != null && namespace != ITUNES_NAMESPACE) return null
                    // Bind locally when metadata is the only namespace owner,
                    // without duplicating a declaration already on this line.
                    if (namespace == null) append(" xmlns:itunes=\"").append(ITUNES_NAMESPACE).append('"')
                    append(" itunes:key=\"").append(escapeAttribute(line.key)).append('"')
                    copiedThrough = line.keyInsertion
                }
            }
            append(ttml, copiedThrough, ttml.length)
        }
        return output.takeIf(TtmlInputPolicy::isAcceptable)
    }

    public companion object {

        /**
         * Inspects [ttml] once and returns the translation pass it allows, or
         * `null` when the host must keep what it already has: Chinese lyrics,
         * an existing translation, no line/word timeline, or nothing to
         * translate. The document is never modified here.
         */
        public fun prepare(ttml: String): LlmLyricsDocument? {
            if (!TtmlInputPolicy.isAcceptable(ttml)) return null
            // A textual scanner never resolves entities, so a document declaring
            // a DTD or entity is refused outright rather than half-read.
            val lowercased = ttml.lowercase(Locale.ROOT)
            if (lowercased.contains("<!doctype") || lowercased.contains("<!entity")) return null
            val root = TtmlScanner.element(ttml, "tt", 0) ?: return null
            val declaredTiming = attribute(root.attributes, TIMING_ATTRIBUTE)?.trim()
            if (declaredTiming != null &&
                !declaredTiming.equals("Line", ignoreCase = true) &&
                !declaredTiming.equals("Word", ignoreCase = true)
            ) {
                return null
            }
            val language = languageOf(root.attributes)
            if (language != null && !isForeignLanguageTag(language)) return null
            if (hasTranslation(ttml)) return null
            val body = TtmlScanner.element(ttml, "body", root.contentStart)
                ?.takeIf { it.end <= root.end } ?: return null
            val parsedLines = TtmlScanner.lines(ttml, body) ?: return null
            if (parsedLines.isEmpty()) return null
            val existingKeys = parsedLines.filterNot(LlmLine::background)
                .map(LlmLine::key).filter(String::isNotBlank)
            if (existingKeys.distinct().size != existingKeys.size) return null
            val usedKeys = existingKeys.toMutableSet()
            // Reserve identifiers outside paragraphs too. Extra matches inside
            // literal text merely reserve a name; they never rewrite that text.
            listOf(KEY_ATTRIBUTE, BARE_KEY_ATTRIBUTE).forEach { pattern ->
                pattern.findAll(ttml).forEach { match ->
                    usedKeys += decodeEntities(match.groups[1]?.value ?: match.groupValues[2])
                }
            }
            var nextKey = 1
            var paragraphKey: String? = null
            val lines = parsedLines.map { line ->
                if (line.background) {
                    line.copy(key = paragraphKey ?: return null)
                } else {
                    val resolvedKey = if (line.keyInsertion < 0) line.key else {
                        var generatedKey: String
                        do {
                            generatedKey = "ampp-llm-line-${nextKey++}"
                        } while (!usedKeys.add(generatedKey))
                        generatedKey
                    }
                    paragraphKey = resolvedKey
                    line.copy(key = resolvedKey)
                }
            }
            if (language == null && isChineseText(lines.joinToString("\n") { it.text })) return null
            val translationLines = buildMap {
                var index = 0
                while (index < lines.size) {
                    val main = lines[index++]
                    val mainText = main.text.takeIf { isTranslatable(it, main.language, language) }
                    val background = buildMap {
                        while (index < lines.size && lines[index].background) {
                            val vocal = lines[index++]
                            if (isTranslatable(vocal.text, vocal.language, language)) put(vocal.id, vocal.text)
                        }
                    }
                    if (mainText != null || background.isNotEmpty()) {
                        put(main.id, LlmLyricsLine(mainText, background))
                    }
                }
            }
            if (translationLines.isEmpty()) return null
            val head = TtmlScanner.element(ttml, "head", root.contentStart)
                ?.takeIf { it.end <= root.end }
            return LlmLyricsDocument(
                ttml = ttml,
                lines = lines,
                insertion = insertionPoint(ttml, head, root),
                translationLines = translationLines,
                sourceLanguage = language,
            )
        }

        /**
         * Any translation the document already carries, wherever it sits: a
         * `<translation>` element of any language, or an inline AMLL
         * `ttm:role="x-translation"` span. Every tag is visited rather than every
         * element, because a translation nested inside a background span is
         * still a translation. The host shows those, so the fallback must not
         * compete with them.
         */
        private fun hasTranslation(ttml: String): Boolean {
            var index = 0
            while (index < ttml.length) {
                val open = ttml.indexOf('<', index)
                if (open < 0) return false
                val literal = TtmlScanner.literalEnd(ttml, open)
                if (literal > 0) {
                    index = literal
                    continue
                }
                val end = TtmlScanner.tagEnd(ttml, open)
                if (end < 0) return false
                val node = TtmlScanner.tag(ttml, open, end)
                if (!node.closing) {
                    if (node.name.equals("translation", ignoreCase = true)) return true
                    if (node.name.equals("span", ignoreCase = true) &&
                        roleOf(node.attributes) == ROLE_TRANSLATION
                    ) {
                        return true
                    }
                }
                index = end + 1
            }
            return false
        }

        /**
         * The metadata seam the track goes into. Head tracks already carry the
         * iTunes namespace, so an existing `<iTunesMetadata>` keeps it and a new
         * one is opened ahead of whatever the head holds instead.
         */
        private fun insertionPoint(ttml: String, head: TtmlElement?, root: TtmlElement): LlmInsertion {
            if (head == null) {
                return LlmInsertion(
                    start = root.contentStart,
                    end = root.contentStart,
                    open = "<head><metadata>$ITUNES_OPEN<translations>",
                    close = "</translations>$ITUNES_CLOSE</metadata></head>",
                )
            }
            val metadata = TtmlScanner.element(ttml, "metadata", head.contentStart)
                ?.takeIf { it.end <= head.contentEnd }
            if (metadata == null) {
                return container(
                    head,
                    "<metadata>$ITUNES_OPEN<translations>",
                    "</translations>$ITUNES_CLOSE</metadata>",
                    "head",
                )
            }
            val itunes = TtmlScanner.element(ttml, "iTunesMetadata", metadata.contentStart)
                ?.takeIf { it.end <= metadata.contentEnd }
            return if (itunes != null) {
                val translations = TtmlScanner.element(ttml, "translations", itunes.contentStart)
                    ?.takeIf { !itunes.selfClosing && it.end <= itunes.contentEnd }
                if (translations != null) {
                    container(translations, "", "", "translations")
                } else {
                    container(itunes, "<translations>", "</translations>", "iTunesMetadata")
                }
            } else {
                container(metadata, "$ITUNES_OPEN<translations>", "</translations>$ITUNES_CLOSE", "metadata")
            }
        }

        /** An empty container is expanded in place instead of nested after it. */
        private fun container(element: TtmlElement, open: String, close: String, name: String): LlmInsertion =
            if (element.selfClosing) {
                LlmInsertion(element.end - 2, element.end, ">$open", "$close</$name>")
            } else {
                LlmInsertion(element.contentStart, element.contentStart, open, close)
            }
    }
}

internal const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
internal const val TTML_METADATA_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
internal const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
private const val ITUNES_OPEN = "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">"
private const val ITUNES_CLOSE = "</iTunesMetadata>"
internal const val CDATA_OPEN = "<![CDATA["
internal const val CDATA_CLOSE = "]]>"
private const val TRANSLATION_TYPE = "subtitle"
private const val TRANSLATION_LANGUAGE = "zh-Hans"
internal const val ROLE_TRANSLATION = "x-translation"
internal const val ROLE_ROMAN = "x-roman"
internal const val ROLE_BACKGROUND = "x-bg"

/** Stands in for a line the track has no text for, keeping its entry there. */
private const val ABSENT_TEXT = " "

private val WHITESPACE = Regex("\\s+")
private val CLOCK_OFFSET = Regex("\\d+(?:\\.\\d+)?(?:ms|s|m|h)")
private val CLOCK_PART = Regex("\\d+(?:\\.\\d+)?")
private val CLOCK_WHOLE = Regex("\\d+")
private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
internal val TIMING_ATTRIBUTE = attributePattern("itunes:timing")
internal val ITUNES_NAMESPACE_ATTRIBUTE = attributePattern("xmlns:itunes")
internal val KEY_ATTRIBUTE = attributePattern("itunes:key")
internal val BARE_KEY_ATTRIBUTE = attributePattern("key")
internal val BEGIN_ATTRIBUTE = attributePattern("begin")
internal val ROLE_ATTRIBUTE = attributePattern("ttm:role")
internal val BARE_ROLE_ATTRIBUTE = attributePattern("role")
internal val LANG_ATTRIBUTE = attributePattern("xml:lang")
internal val BARE_LANG_ATTRIBUTE = attributePattern("lang")

/** One vocal. [id] stays in the model protocol; [key] anchors the native subtitle. */
internal data class LlmLine(
    val id: String,
    val key: String,
    val text: String,
    val language: String?,
    /** Original start-tag offset, or -1 when the paragraph already has a key. */
    val keyInsertion: Int,
    val background: Boolean = false,
)

/**
 * The splice that adds the subtitle track. [start] and [end] bound the text the
 * insertion replaces: nothing at all for a container that simply takes a child,
 * or the empty-element ending, which is expanded in place instead of leaving
 * the track beside the element rather than inside it.
 */
private data class LlmInsertion(val start: Int, val end: Int, val open: String, val close: String)

internal fun attributePattern(name: String) = Regex(
    "(?<![\\w:])" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"|" +
        "(?<![\\w:])" + Regex.escape(name) + "\\s*=\\s*'([^']*)'",
)

internal fun attribute(attributes: String, pattern: Regex): String? {
    val match = pattern.find(attributes) ?: return null
    return if (match.groups[1] != null) match.groupValues[1] else match.groupValues[2]
}

internal fun roleOf(attributes: String): String? =
    (attribute(attributes, ROLE_ATTRIBUTE) ?: attribute(attributes, BARE_ROLE_ATTRIBUTE))
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.ifEmpty { null }

/** Word separation lives in literal whitespace, so runs collapse to one space. */
private fun normalizeText(raw: String): String = WHITESPACE.replace(raw, " ").trim()

/** Strip complete outer pairs, then wrap nonempty backing text once in ASCII parentheses. */
private fun formatBackgroundSubtitle(value: String): String? {
    var start = 0
    var end = value.length
    while (true) {
        while (start < end && value[start].isWhitespace()) start++
        while (start < end && value[end - 1].isWhitespace()) end--
        if (start == end) return null
        val first = value[start]
        val last = value[end - 1]
        if ((first != '(' || last != ')') && (first != '（' || last != '）')) break
        start++
        end--
    }
    val text = value.substring(start, end)
    if (text.any { it in "()（）" }) return null
    return "($text)"
}

/** A line's own xml:lang attribute, which outranks the document language. */
internal fun languageOf(attributes: String): String? =
    (attribute(attributes, LANG_ATTRIBUTE) ?: attribute(attributes, BARE_LANG_ATTRIBUTE))
        ?.trim()
        ?.ifEmpty { null }

/** The native pipeline's Chinese alias list (zh, zh-Hant, yue, cmn, nan, ...). */
private fun isForeignLanguageTag(tag: String): Boolean =
    TtmlDocumentMetadata(TtmlTimingMode.NON_WORD, tag, false).isForeign

/**
 * Explicit per-line language wins. Japanese and Korean may use Han-only lines;
 * keep those eligible using their declared context. In other foreign songs,
 * Chinese lyric lines are preserved without asking the model to rewrite them.
 */
private fun isTranslatable(text: String, lineLanguage: String?, documentLanguage: String?): Boolean {
    if (text.isEmpty() || text.none { it.isLetter() }) return false
    if (lineLanguage != null) return isForeignLanguageTag(lineLanguage)
    val context = documentLanguage?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')?.substringBefore('-')
    if (context in setOf("ja", "jpn", "ko", "kor")) return true
    return !isChineseText(text)
}

/** XML character references are decoded once, so the track escapes them once. */
internal fun decodeEntities(raw: String): String {
    if ('&' !in raw) return raw
    return ENTITY.replace(raw) { match ->
        when (val reference = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> "\u00A0"
            else -> decodeCodePoint(reference) ?: match.value
        }
    }
}

private fun decodeCodePoint(reference: String): String? {
    val codePoint = when {
        reference.startsWith("#x") || reference.startsWith("#X") -> reference.substring(2).toIntOrNull(16)
        reference.startsWith("#") -> reference.substring(1).toIntOrNull()
        else -> null
    } ?: return null
    if (codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
    return String(Character.toChars(codePoint))
}

/**
 * Kana is decisive: Japanese lyrics carry Han characters too, so a document
 * without a language declaration is only read as Chinese when Han characters
 * dominate and no kana appear anywhere.
 */
private fun isChineseText(text: String): Boolean {
    var han = 0
    var kana = 0
    var other = 0
    for (character in text) {
        if (!character.isLetter()) continue
        when {
            isKana(character) -> kana += 1
            isHan(character) -> han += 1
            else -> other += 1
        }
    }
    if (han == 0 || kana > 0) return false
    return han >= other
}

private fun isHan(character: Char): Boolean = when (Character.UnicodeBlock.of(character)) {
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
    -> true

    else -> false
}

private fun isKana(character: Char): Boolean = when (Character.UnicodeBlock.of(character)) {
    Character.UnicodeBlock.HIRAGANA,
    Character.UnicodeBlock.KATAKANA,
    Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
    -> true

    else -> false
}

/** Clock offsets Apple writes: `3:02.550`, `34.339`, `1500ms`, `1.5s`. */
internal fun isClock(value: String?): Boolean {
    // Only finite, non-negative clock numbers pass, so a stray dot or sign
    // cannot pose as the timeline the subtitle track is aligned against.
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (CLOCK_OFFSET.matchEntire(text) != null) {
        val numeric = if (text.endsWith("ms")) text.dropLast(2) else text.dropLast(1)
        return numeric.toDoubleOrNull()?.isFinite() == true
    }
    val parts = text.split(':')
    if (parts.size !in 1..3) return false
    return parts.all { CLOCK_PART.matchEntire(it) != null } &&
        parts.dropLast(1).all { CLOCK_WHOLE.matchEntire(it) != null } &&
        parts.all { it.toDoubleOrNull()?.isFinite() == true }
}

internal fun escapeText(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(character)
        }
    }
}

internal fun escapeAttribute(value: String): String = buildString(value.length) {
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

internal class TtmlElement(
    val start: Int,
    val attributes: String,
    val contentStart: Int,
    val contentEnd: Int,
    val end: Int,
    val selfClosing: Boolean,
)

internal class TtmlTag(
    val start: Int,
    val name: String,
    val attributes: String,
    val end: Int,
    val selfClosing: Boolean,
    val closing: Boolean,
)

/** Open markup and the separate main/background text it owns, if any. */
private class LlmFrame(
    val name: String,
    val auxiliary: Boolean,
    val line: Boolean,
    val declaredLanguage: String?,
    var language: String?,
    val text: StringBuilder? = null,
) {
    var hasExplicitLanguage = declaredLanguage != null
}

/**
 * The scanner [AmllTtmlFormatConverter] already relies on, repeated here because
 * the whitespace between word spans carries word separation and a DOM
 * round-trip would not preserve it. Only markup is read; text nodes are copied
 * verbatim, and comments, CDATA and processing instructions are stepped over
 * instead of being parsed.
 */
internal object TtmlScanner {

    fun element(text: String, name: String, from: Int): TtmlElement? {
        var index = from.coerceAtLeast(0)
        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) return null
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) return null
            val candidate = tag(text, open, end)
            if (!candidate.closing && candidate.name.equals(name, ignoreCase = true)) {
                return parse(text, candidate)
            }
            index = end + 1
        }
        return null
    }

    /** Each paragraph contributes a main entry followed by its independent backgrounds. */
    fun lines(ttml: String, body: TtmlElement): List<LlmLine>? {
        val lines = mutableListOf<LlmLine>()
        val frames = ArrayDeque<LlmFrame>()
        val backgrounds = mutableListOf<LlmFrame>()
        var main: LlmFrame? = null
        var key: String? = null
        var keyInsertion = -1
        var paragraphNumber = 0

        fun finishParagraph(frame: LlmFrame): Boolean {
            val paragraphKey = key ?: return false
            val id = "L${++paragraphNumber}"
            lines += LlmLine(
                id, paragraphKey, normalizeText(frame.text?.toString().orEmpty()), frame.language, keyInsertion,
            )
            backgrounds.forEachIndexed { index, background ->
                lines += LlmLine(
                    "$id.bg${index + 1}", paragraphKey,
                    normalizeText(background.text?.toString().orEmpty()), background.language,
                    keyInsertion = -1, background = true,
                )
            }
            backgrounds.clear()
            main = null
            key = null
            keyInsertion = -1
            return true
        }

        var index = body.contentStart
        while (index < body.contentEnd) {
            val open = ttml.indexOf('<', index)
            if (open < 0 || open >= body.contentEnd) break
            val vocal = frames.lastOrNull { it.text != null }
            val current = vocal?.text.takeIf { frames.none(LlmFrame::auxiliary) }
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                if (current != null) {
                    // The run leading up to the literal is text either way, and
                    // then only a CDATA body joins the line: a comment carries no
                    // text, and a processing instruction is not text at all.
                    appendText(current, ttml, index, open)
                    if (ttml.startsWith(CDATA_OPEN, open)) {
                        val content = open + CDATA_OPEN.length
                        current.append(ttml, content, (literal - CDATA_CLOSE.length).coerceAtLeast(content))
                    }
                }
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0 || end >= body.contentEnd) return null
            if (current != null) appendText(current, ttml, index, open)
            val node = tag(ttml, open, end)
            if (node.closing) {
                val frame = popTo(frames, node.name) ?: return null
                if (frame.line) {
                    if (!finishParagraph(frame)) return null
                }
                index = end + 1
                continue
            }
            val line = node.name.equals("p", ignoreCase = true)
            val declaredLanguage = languageOf(node.attributes)
            val inheritedLanguage = frames.lastOrNull { it.declaredLanguage != null }?.declaredLanguage
            val role = if (node.name.equals("span", ignoreCase = true)) roleOf(node.attributes) else null
            val auxiliary = role == ROLE_TRANSLATION || role == ROLE_ROMAN
            val background = main != null && role == ROLE_BACKGROUND && frames.none(LlmFrame::auxiliary)
            val frame = LlmFrame(
                name = node.name,
                auxiliary = auxiliary,
                line = line,
                declaredLanguage = declaredLanguage,
                language = declaredLanguage ?: inheritedLanguage,
                text = if (line || background) StringBuilder() else null,
            )
            if (line) {
                if (main != null || !isClock(attribute(node.attributes, BEGIN_ATTRIBUTE))) return null
                val declaredKey = attribute(node.attributes, KEY_ATTRIBUTE)
                    ?: attribute(node.attributes, BARE_KEY_ATTRIBUTE)
                val decodedKey = declaredKey?.let(::decodeEntities)
                if (decodedKey != null && decodedKey.isBlank()) return null
                val localNamespace = attribute(node.attributes, ITUNES_NAMESPACE_ATTRIBUTE)
                if (declaredKey == null && localNamespace != null && localNamespace != ITUNES_NAMESPACE) return null
                main = frame
                key = decodedKey.orEmpty()
                keyInsertion = if (declaredKey == null) {
                    if (node.selfClosing) {
                        var slash = end - 1
                        while (slash > open && ttml[slash].isWhitespace()) slash -= 1
                        slash
                    } else {
                        end
                    }
                } else {
                    -1
                }
            } else if (background) {
                backgrounds += frame
            } else if (vocal != null && current != null && !vocal.hasExplicitLanguage &&
                declaredLanguage != null && !auxiliary
            ) {
                // A vocal's own/span language outranks an inherited wrapper;
                // an inferred main language must never leak into its background.
                vocal.language = declaredLanguage
                vocal.hasExplicitLanguage = true
            }
            if (node.selfClosing) {
                if (line) {
                    if (!finishParagraph(frame)) return null
                } else if (node.name.equals("br", ignoreCase = true)) {
                    current?.append(' ')
                }
            } else {
                frames.addLast(frame)
            }
            index = end + 1
        }
        if (frames.isNotEmpty() || main != null) return null
        return lines
    }

    /** Character data reaches the model decoded, so the track escapes it once. */
    private fun appendText(target: StringBuilder, text: String, from: Int, to: Int) {
        if (to <= from) return
        target.append(decodeEntities(text.substring(from, to)))
    }

    /** Exclusive end of a comment, CDATA section or processing instruction. */
    fun literalEnd(text: String, open: Int): Int {
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
    fun tagEnd(text: String, open: Int): Int {
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

    fun tag(text: String, open: Int, end: Int): TtmlTag {
        val closing = open + 1 < end && text[open + 1] == '/'
        var index = if (closing) open + 2 else open + 1
        val nameStart = index
        while (index < end) {
            val character = text[index]
            if (character.isWhitespace() || character == '/' || character == '>') break
            index += 1
        }
        val name = text.substring(nameStart, index)
        val selfClosing = !closing && text[end - 1] == '/'
        val attributesEnd = if (selfClosing) end - 1 else end
        val attributes = if (attributesEnd > index) text.substring(index, attributesEnd) else ""
        return TtmlTag(open, name, attributes, end, selfClosing, closing)
    }

    /** Parse an already-located start tag, including its matching end when not empty. */
    fun elementAt(text: String, open: TtmlTag): TtmlElement? = parse(text, open)

    private fun parse(text: String, open: TtmlTag): TtmlElement? {
        val name = open.name
        if (open.selfClosing) {
            return TtmlElement(open.start, open.attributes, open.end + 1, open.end + 1, open.end + 1, true)
        }
        var depth = 1
        var index = open.end + 1
        while (index < text.length) {
            val nextOpen = text.indexOf('<', index)
            if (nextOpen < 0) return null
            val literal = literalEnd(text, nextOpen)
            if (literal > 0) {
                index = literal
                continue
            }
            val nextEnd = tagEnd(text, nextOpen)
            if (nextEnd < 0) return null
            val candidate = tag(text, nextOpen, nextEnd)
            if (candidate.name.equals(name, ignoreCase = true)) {
                if (candidate.closing) {
                    depth -= 1
                    if (depth == 0) {
                        return TtmlElement(
                            open.start,
                            open.attributes,
                            open.end + 1,
                            nextOpen,
                            nextEnd + 1,
                            false,
                        )
                    }
                } else if (!candidate.selfClosing) {
                    depth += 1
                }
            }
            index = nextEnd + 1
        }
        return null
    }

    private fun popTo(frames: ArrayDeque<LlmFrame>, name: String): LlmFrame? {
        while (frames.isNotEmpty()) {
            val frame = frames.removeLast()
            if (frame.name.equals(name, ignoreCase = true)) return frame
        }
        return null
    }
}
