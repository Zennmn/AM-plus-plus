package dev.amenhancer.module.lyrics

internal data class TtmlFormatConversion(
    val ttml: String,
    val converted: Boolean,
)

/**
 * Rewrites the AMLL TTML format into the Apple Music format.
 *
 * The formats differ in where auxiliary tracks live. AMLL keeps translation and
 * romanization inline in every lyric line:
 *
 * ```
 * <p itunes:key="L1"> ...word spans...
 *   <span ttm:role="x-translation" xml:lang="zh-CN">…</span>
 *   <span ttm:role="x-roman">…</span>
 * </p>
 * ```
 *
 * Apple Music instead declares them once in the head, linked back to each line
 * through `itunes:key`, and leaves the body carrying only lyrics:
 *
 * ```
 * <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
 *   <translations><translation xml:lang="zh-Hans"><text for="L1">…</text></translation></translations>
 *   <transliterations><transliteration xml:lang="ko-Latn"><text for="L1">…</text></transliteration></transliterations>
 * </iTunesMetadata>
 * ```
 *
 * A `<text>` carries whatever the source span held, so a line-by-line track
 * stays plain text and a word-by-word track keeps its timed `<span>`s.
 * Auxiliary spans nested in a background vocal (`ttm:role="x-bg"`) move to the
 * same `<text>` wrapped in an `x-bg` span and in parentheses, which is how
 * Apple sets a background translation apart from the line it accompanies.
 *
 * AMLL already writes an `<iTunesMetadata>` of its own to carry
 * `<songwriters>`, so the tracks join that element ahead of what it holds
 * rather than opening a second one beside it.
 *
 * The background vocal left in the body is rewritten too. Its `begin` / `end`
 * go, because Apple reads the vocal's extent from its own syllables, and a
 * vocal starting ahead of the first lyric syllable moves to the front of the
 * line, because Apple places the highlight in document order.
 *
 * The language tags are pinned rather than carried over: Android Apple Music's
 * TTML parser only renders a translation and a transliteration together when
 * the lyrics are Korean, so the root is marked `ko`, translations `zh-Hans` and
 * transliterations `ko-Latn`. Because only one track of each kind survives that
 * constraint, the first translation and the first romanization of each line win.
 *
 * The root also gets the `itunes:timing` Apple expects — `Word` when the body
 * carries timed syllables, `Line` otherwise.
 *
 * AMLL additionally serializes `<metadata>` and `<div>` with `xmlns=""`, which
 * drops the lyric subtree out of the TTML namespace and leaves Apple's parser
 * with no lines; the override is removed.
 *
 * The rewrite is textual on purpose: whitespace between `<span>` tags carries
 * word separation, and a DOM round-trip would not preserve it. Only markup is
 * examined; lyric text nodes are copied verbatim.
 */
internal object AmllTtmlFormatConverter {

    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private const val ROLE_BACKGROUND = "x-bg"

    /** Pinned so Apple Music on Android renders both auxiliary tracks. */
    private const val LYRIC_LANGUAGE = "ko"
    private const val TRANSLATION_LANGUAGE = "zh-Hans"
    private const val TRANSLITERATION_LANGUAGE = "ko-Latn"

    private val EMPTY_DEFAULT_NAMESPACE = Regex("""\s+xmlns\s*=\s*(?:""|'')""")
    private val ROLE_ATTRIBUTE = attributePattern("ttm:role")
    private val LANGUAGE_ATTRIBUTE = attributePattern("xml:lang")
    private val KEY_ATTRIBUTE = attributePattern("itunes:key")
    private val BEGIN_ATTRIBUTE_VALUE = attributePattern("begin")
    private val TIMING_ATTRIBUTE_VALUE = attributePattern("itunes:timing")
    private val ITUNES_DECLARATION = Regex("""\sxmlns:itunes\s*=""")
    private val BEGIN_ATTRIBUTE = Regex("""\sbegin\s*=""")
    private val SPAN_TIMING =
        Regex("""\s(?:begin|end)\s*=\s*"[^"]*"|\s(?:begin|end)\s*=\s*'[^']*'""")
    private val PARENTHESIZED = Regex("""^\s*[(（].*[)）]\s*$""", RegexOption.DOT_MATCHES_ALL)

    private val AUXILIARY_ROLES = setOf(ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND)

    private fun attributePattern(name: String) =
        Regex("""\s${Regex.escape(name)}\s*=\s*"([^"]*)"|\s${Regex.escape(name)}\s*=\s*'([^']*)'""")

    fun toAppleFormat(ttml: String): TtmlFormatConversion {
        if (ttml.isEmpty()) return TtmlFormatConversion(ttml, false)
        // A declared track means the auxiliary spans already moved to the head,
        // and migrating again would duplicate them. An `<iTunesMetadata>` on its
        // own is no such signal: AMLL writes one to carry `<songwriters>`.
        if (declaresTracks(ttml)) return TtmlFormatConversion(ttml, false)
        val migrated = migrateAuxiliaryTracks(ttml)
        val result = normalizeTags(migrated, wordTimed = hasTimedSpan(migrated))
        return TtmlFormatConversion(result, result != ttml)
    }

    private fun declaresTracks(ttml: String) =
        nextElement(ttml, "translations", 0) != null ||
            nextElement(ttml, "transliterations", 0) != null

    /** Moves inline `x-translation` / `x-roman` spans into a head `<iTunesMetadata>`. */
    private fun migrateAuxiliaryTracks(ttml: String): String {
        val metadata = nextElement(ttml, "metadata", 0) ?: return ttml

        val translations = mutableListOf<TrackEntry>()
        val transliterations = mutableListOf<TrackEntry>()
        val edits = mutableListOf<Edit>()

        var cursor = metadata.end
        while (true) {
            val line = nextElement(ttml, "p", cursor) ?: break
            cursor = line.end
            val key = attribute(line.attributes, KEY_ATTRIBUTE) ?: continue
            collectLine(ttml, line, key, translations, transliterations, edits)
        }

        if (translations.isEmpty() && transliterations.isEmpty()) return ttml

        edits += declareTracks(ttml, metadata, buildTracks(translations, transliterations))
        return applyEdits(ttml, edits)
    }

    /**
     * Places the tracks in the head, joining the `<iTunesMetadata>` AMLL already
     * wrote for `<songwriters>` — ahead of what it holds, the order Apple uses —
     * rather than opening a second one beside it.
     */
    private fun declareTracks(ttml: String, metadata: Element, tracks: String): Edit {
        val container = nextElement(ttml, "iTunesMetadata", metadata.start)
            ?.takeIf { it.end <= metadata.contentEnd }
        return when {
            container == null && metadata.selfClosing -> Edit(
                metadata.start,
                metadata.end,
                "<metadata${metadata.attributes}>${wrapTracks(tracks)}</metadata>",
            )
            container == null -> Edit(metadata.contentEnd, metadata.contentEnd, wrapTracks(tracks))
            container.selfClosing -> Edit(
                container.start,
                container.end,
                "<iTunesMetadata${container.attributes}>$tracks</iTunesMetadata>",
            )
            else -> Edit(container.contentStart, container.contentStart, tracks)
        }
    }

    private fun collectLine(
        ttml: String,
        line: Element,
        key: String,
        translations: MutableList<TrackEntry>,
        transliterations: MutableList<TrackEntry>,
        edits: MutableList<Edit>,
    ) {
        // Only one track of each kind survives the parser constraint, so the
        // first translation and first romanization of the line are kept.
        var translation: String? = null
        var roman: String? = null
        var backgroundTranslation: String? = null
        var backgroundRoman: String? = null

        val children = childElements(ttml, line)
        children.forEach { child ->
            when (attribute(child.attributes, ROLE_ATTRIBUTE)) {
                ROLE_TRANSLATION -> {
                    if (translation == null) translation = content(ttml, child)
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_ROMAN -> {
                    if (roman == null) roman = content(ttml, child)
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_BACKGROUND -> {
                    val nested = childElements(ttml, child)
                    val removals = mutableListOf<Edit>()
                    nested.forEach { span ->
                        when (attribute(span.attributes, ROLE_ATTRIBUTE)) {
                            ROLE_TRANSLATION -> {
                                if (backgroundTranslation == null) {
                                    backgroundTranslation = content(ttml, span)
                                }
                                removals += removal(ttml, span, child.contentStart)
                            }
                            ROLE_ROMAN -> {
                                if (backgroundRoman == null) backgroundRoman = content(ttml, span)
                                removals += removal(ttml, span, child.contentStart)
                            }
                        }
                    }
                    edits += backgroundEdits(ttml, line, children, child, nested, removals)
                }
            }
        }

        trackText(translation, backgroundTranslation)?.let { translations += TrackEntry(key, it) }
        trackText(roman, backgroundRoman)?.let { transliterations += TrackEntry(key, it) }
    }

    /**
     * Rewrites the background vocal that stays in the body: its `begin` / `end`
     * go, because Apple reads the vocal's extent from its syllables, and a vocal
     * starting ahead of the lyrics moves to the front of the line, because Apple
     * places the highlight in document order.
     */
    private fun backgroundEdits(
        ttml: String,
        line: Element,
        siblings: List<Element>,
        background: Element,
        nested: List<Element>,
        auxiliaryRemovals: List<Edit>,
    ): List<Edit> {
        // Without syllables to read the extent from, the attributes are the only
        // timing the vocal has, so they stay and it keeps its place.
        val syllable = firstSyllableBegin(ttml, nested) ?: return auxiliaryRemovals

        val openTag = ttml.substring(background.start, background.contentStart)
        val rewrite = auxiliaryRemovals +
            Edit(background.start, background.contentStart, SPAN_TIMING.replace(openTag, ""))
        val lyric = firstSyllableBegin(ttml, siblings)
        if (siblings.firstOrNull() === background || lyric == null || syllable >= lyric) return rewrite

        return listOf(
            Edit(line.contentStart, line.contentStart, render(ttml, background.start, background.end, rewrite)),
            removal(ttml, background, line.contentStart),
        )
    }

    /** Begin time of the first timed lyric span among [candidates], if any. */
    private fun firstSyllableBegin(ttml: String, candidates: List<Element>): Double? =
        candidates.asSequence()
            .filter { attribute(it.attributes, ROLE_ATTRIBUTE) !in AUXILIARY_ROLES }
            .mapNotNull { clockSeconds(attribute(it.attributes, BEGIN_ATTRIBUTE_VALUE)) }
            .firstOrNull()

    /** Seconds in a clock value such as `3:02.550`, or `null` when unreadable. */
    private fun clockSeconds(value: String?): Double? {
        val parts = value?.split(':')?.takeIf { it.size in 1..3 } ?: return null
        var seconds = 0.0
        parts.forEach { part ->
            seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        }
        return seconds
    }

    private fun trackText(main: String?, background: String?): String? {
        if (main == null && background == null) return null
        return buildString {
            append(main.orEmpty())
            if (background != null) {
                append("<span ttm:role=\"").append(ROLE_BACKGROUND).append("\">")
                append(parenthesized(background))
                append("</span>")
            }
        }
    }

    /** Apple parenthesizes a background track; AMLL leaves that to the reader. */
    private fun parenthesized(text: String) =
        if (PARENTHESIZED.matches(text)) text else "($text)"

    private fun wrapTracks(tracks: String) =
        "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">$tracks</iTunesMetadata>"

    private fun buildTracks(
        translations: List<TrackEntry>,
        transliterations: List<TrackEntry>,
    ): String = buildString {
        appendTrack(translations, "translations", "translation", TRANSLATION_LANGUAGE)
        appendTrack(transliterations, "transliterations", "transliteration", TRANSLITERATION_LANGUAGE)
    }

    private fun StringBuilder.appendTrack(
        entries: List<TrackEntry>,
        container: String,
        item: String,
        language: String,
    ) {
        if (entries.isEmpty()) return
        append('<').append(container).append('>')
        append('<').append(item).append(" xml:lang=\"").append(language).append("\">")
        entries.forEach { entry ->
            append("<text for=\"").append(entry.key).append("\">")
            append(entry.text)
            append("</text>")
        }
        append("</").append(item).append('>')
        append("</").append(container).append('>')
    }

    private fun content(ttml: String, element: Element): String =
        if (element.selfClosing) "" else ttml.substring(element.contentStart, element.contentEnd)

    /** Deletes the auxiliary span together with the whitespace that preceded it. */
    private fun removal(ttml: String, element: Element, lowerBound: Int): Edit {
        var start = element.start
        while (start > lowerBound && ttml[start - 1].isWhitespace()) start -= 1
        return Edit(start, element.end, "")
    }

    /**
     * Declares the timing mode and lyric language on the root and drops the
     * empty default namespace override from every other element.
     */
    private fun normalizeTags(ttml: String, wordTimed: Boolean): String {
        val output = StringBuilder(ttml.length)
        var index = 0
        var rootSeen = false
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
            output.append(
                when {
                    !rootSeen && isElement(ttml, open, "tt") -> {
                        rootSeen = true
                        withRootAttributes(tag, wordTimed)
                    }
                    tag.startsWith("</") -> tag
                    else -> EMPTY_DEFAULT_NAMESPACE.replace(tag, "")
                },
            )
            index = end + 1
        }
        return output.toString()
    }

    private fun withRootAttributes(tag: String, wordTimed: Boolean): String {
        val insertAt = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        // Both attributes are authoritative here, so any inherited value is
        // dropped before the pinned one is appended.
        var head = tag.substring(0, insertAt)
        head = LANGUAGE_ATTRIBUTE.replace(head, "")
        head = TIMING_ATTRIBUTE_VALUE.replace(head, "")
        val additions = buildString {
            if (!ITUNES_DECLARATION.containsMatchIn(head)) {
                append(" xmlns:itunes=\"").append(ITUNES_NAMESPACE).append('"')
            }
            append(" itunes:timing=\"").append(if (wordTimed) "Word" else "Line").append('"')
            append(" xml:lang=\"").append(LYRIC_LANGUAGE).append('"')
        }
        return head.trimEnd() + additions + tag.substring(insertAt)
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
            if (isElement(text, open, "span") &&
                BEGIN_ATTRIBUTE.containsMatchIn(text.substring(open, end + 1))
            ) {
                return true
            }
            index = end + 1
        }
        return false
    }

    private fun applyEdits(ttml: String, edits: List<Edit>): String =
        if (edits.isEmpty()) ttml else render(ttml, 0, ttml.length, edits)

    /** [ttml] between [from] and [to], with every edit falling inside applied. */
    private fun render(ttml: String, from: Int, to: Int, edits: List<Edit>): String =
        buildString(to - from) {
            var cursor = from
            edits.sortedBy { it.start }.forEach { edit ->
                if (edit.start < cursor || edit.end > to) return@forEach
                append(ttml, cursor, edit.start)
                append(edit.replacement)
                cursor = edit.end
            }
            append(ttml, cursor, to)
        }

    private fun attribute(attributes: String, pattern: Regex): String? {
        val match = pattern.find(attributes) ?: return null
        return if (match.groups[1] != null) match.groupValues[1] else match.groupValues[2]
    }

    // --- Minimal XML element scanning -------------------------------------

    private data class Edit(val start: Int, val end: Int, val replacement: String)

    private data class TrackEntry(val key: String, val text: String)

    private data class Element(
        val attributes: String,
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val end: Int,
        val selfClosing: Boolean,
    )

    /** The next `name` element at or after [from], or `null` when there is none. */
    private fun nextElement(text: String, name: String, from: Int): Element? {
        var index = from
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
            if (isElement(text, open, name)) return parseElement(text, name, open, end)
            index = end + 1
        }
        return null
    }

    private fun parseElement(text: String, name: String, open: Int, tagEnd: Int): Element? {
        val selfClosing = text[tagEnd - 1] == '/'
        val attributesEnd = if (selfClosing) tagEnd - 1 else tagEnd
        val attributes = text.substring(open + 1 + name.length, attributesEnd)
        if (selfClosing) {
            return Element(attributes, open, tagEnd + 1, tagEnd + 1, tagEnd + 1, true)
        }
        var depth = 1
        var index = tagEnd + 1
        while (index < text.length) {
            val open2 = text.indexOf('<', index)
            if (open2 < 0) return null
            val literal = literalEnd(text, open2)
            if (literal > 0) {
                index = literal
                continue
            }
            val end2 = tagEnd(text, open2)
            if (end2 < 0) return null
            if (isCloseTag(text, open2, name)) {
                depth -= 1
                if (depth == 0) {
                    return Element(attributes, open, tagEnd + 1, open2, end2 + 1, false)
                }
            } else if (isElement(text, open2, name) && text[end2 - 1] != '/') {
                depth += 1
            }
            index = end2 + 1
        }
        return null
    }

    /** Direct child elements of [parent], in document order. */
    private fun childElements(text: String, parent: Element): List<Element> {
        val children = mutableListOf<Element>()
        var index = parent.contentStart
        while (index < parent.contentEnd) {
            val open = text.indexOf('<', index)
            if (open < 0 || open >= parent.contentEnd) break
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) break
            val name = elementName(text, open, end)
            val child = name?.let { parseElement(text, it, open, end) }
            if (child == null) {
                index = end + 1
                continue
            }
            children += child
            index = child.end
        }
        return children
    }

    private fun elementName(text: String, open: Int, tagEnd: Int): String? {
        if (text.startsWith("</", open)) return null
        var index = open + 1
        while (index < tagEnd) {
            val character = text[index]
            if (character.isWhitespace() || character == '/' || character == '>') break
            index += 1
        }
        return text.substring(open + 1, index).takeIf(String::isNotEmpty)
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

    private fun isElement(text: String, open: Int, name: String): Boolean {
        if (!text.regionMatches(open + 1, name, 0, name.length, ignoreCase = true)) return false
        val next = text.getOrNull(open + 1 + name.length) ?: return false
        return next == '>' || next == '/' || next.isWhitespace()
    }

    private fun isCloseTag(text: String, open: Int, name: String): Boolean {
        if (!text.startsWith("</", open)) return false
        if (!text.regionMatches(open + 2, name, 0, name.length, ignoreCase = true)) return false
        val next = text.getOrNull(open + 2 + name.length) ?: return false
        return next == '>' || next.isWhitespace()
    }
}
