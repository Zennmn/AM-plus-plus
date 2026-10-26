package dev.amenhancer.module.lyrics

import java.math.BigDecimal

data class TtmlFormatConversion(
    val ttml: String,
    val converted: Boolean,
)

/**
 * Rewrites the AMLL TTML format into the Apple Music format.
 *
 * The formats differ in where the translation and romanization tracks live.
 * AMLL keeps them inline in every lyric line; Apple declares them once in the
 * head, linked back through `itunes:key`, and leaves the body carrying lyrics:
 *
 * ```
 * <p itunes:key="L1"> …word spans… <span ttm:role="x-translation">…</span></p>
 *
 * <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
 *   <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">…</text></translation></translations>
 *   <transliterations><transliteration xml:lang="ko-Latn"><text for="L1">…</text></transliteration></transliterations>
 * </iTunesMetadata>
 * ```
 *
 * A `<text>` carries whatever the source span held, so a line-by-line track
 * stays plain text and a word-by-word one keeps its timed `<span>`s. Spans
 * nested in a background vocal (`ttm:role="x-bg"`) join the same `<text>`,
 * wrapped in an `x-bg` span and in parentheses, which is how Apple sets a
 * background translation apart from the line it accompanies.
 *
 * A track that is written lists every keyed line: Android Apple Music walks the
 * entries alongside the lines instead of resolving `for`, so a line skipped
 * there shifts every later entry onto the wrong lyric — and a track with holes
 * is rejected outright. An untranslated line therefore holds a single space, and
 * a line translated only in its background opens with that same space ahead of
 * the `x-bg` span. A track no line contributed to is left out altogether.
 *
 * The tracks join the `<iTunesMetadata>` AMLL writes for `<songwriters>`, ahead
 * of what it holds, rather than opening a second one beside it. A file can also
 * arrive half in each format — an Apple transliteration in the head beside
 * translations still inline — so each kind is judged on its own: a kind the head
 * declares keeps its inline spans, because migrating it again would duplicate
 * the track.
 *
 * The background vocal left in the body loses its `begin` / `end`, because Apple
 * reads the vocal's extent from its own syllables, and moves to the front of the
 * line when it starts ahead of the first lyric syllable, because Apple places
 * the highlight in document order.
 *
 * The root is pinned rather than carried over. Android's TTML parser renders a
 * translation and a transliteration together only when the lyrics are Korean, so
 * the root is marked `ko`, translations `zh-Hans` and transliterations `ko-Latn`;
 * because only one track of each kind survives that constraint, the first
 * translation and first romanization of each line win. The root also gets the
 * `itunes:timing` Apple expects — `Word` when the body carries timed syllables,
 * `Line` otherwise. AMLL's `xmlns=""` on `<metadata>` and `<div>` goes too: it
 * drops the lyric subtree out of the TTML namespace, leaving Apple no lines.
 *
 * Lines are then stably sorted by their numeric begin time, with head entries
 * following their keys. Same-start translations join with newlines in the
 * first entry; subsequent entries keep their space placeholders. Simultaneous
 * lyric voices, romanization, word timing and section ownership are retained.
 * Existing Apple tracks participate in this formatting as well.
 *
 * The rewrite is textual on purpose: whitespace between `<span>` tags carries
 * word separation, and a DOM round-trip would not preserve it. Only markup is
 * examined; lyric text nodes are copied verbatim.
 */
object AmllTtmlFormatConverter {

    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val TTML_METADATA_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private const val ROLE_BACKGROUND = "x-bg"

    /** Pinned so Apple Music on Android renders both auxiliary tracks. */
    private const val LYRIC_LANGUAGE = "ko"
    private const val TRANSLATION_LANGUAGE = "zh-Hans"
    private const val TRANSLITERATION_LANGUAGE = "ko-Latn"

    /** How Apple labels a translation meant to be shown under the lyric. */
    private const val TRANSLATION_TYPE = "subtitle"

    /** Apple's translated copy of a line, including its background words. */
    private const val REPLACEMENT_TYPE = "replacement"

    /** Stands in for a line the track has no text for, keeping the entry there. */
    private const val ABSENT_TEXT = " "

    private val EMPTY_DEFAULT_NAMESPACE = Regex("""\s+xmlns\s*=\s*(?:""|'')""")
    private val DEFAULT_NAMESPACE = attributePattern("xmlns")
    private val TTM_DECLARATION = attributePattern("xmlns:ttm")
    private val ROLE_ATTRIBUTE = attributePattern("ttm:role")
    private val LANGUAGE_ATTRIBUTE = attributePattern("xml:lang")
    private val KEY_ATTRIBUTE = attributePattern("itunes:key")
    private val TYPE_ATTRIBUTE = attributePattern("type")
    private val FOR_ATTRIBUTE = attributePattern("for")
    private val BEGIN_ATTRIBUTE_VALUE = attributePattern("begin")
    private val END_ATTRIBUTE_VALUE = attributePattern("end")
    private val TIMING_ATTRIBUTE_VALUE = attributePattern("itunes:timing")
    private val ITUNES_DECLARATION = attributePattern("xmlns:itunes")
    private val SPAN_TIMING =
        Regex("""\s(?:begin|end)\s*=\s*"[^"]*"|\s(?:begin|end)\s*=\s*'[^']*'""")
    private val PARENTHESIZED = Regex("""^\s*[(（].*[)）]\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val TIMELINE_OFFSET = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)")
    private val TIMELINE_NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")

    private val AUXILIARY_ROLES = setOf(ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND)

    private fun attributePattern(name: String) =
        Regex("""\s${Regex.escape(name)}\s*=\s*"([^"]*)"|\s${Regex.escape(name)}\s*=\s*'([^']*)'""")

    /** Source attribute content already carries entities; choose a safe delimiter. */
    private fun quoteRawAttributeValue(value: String): String =
        if ('"' in value) "'$value'" else "\"$value\""

    fun toAppleFormat(ttml: String): TtmlFormatConversion {
        if (ttml.isEmpty()) return TtmlFormatConversion(ttml, false)
        // Judged per kind, so a head that already declares one track does not
        // hide the other kind still sitting inline. An `<iTunesMetadata>` on its
        // own is no such signal: AMLL writes one to carry `<songwriters>`.
        val declared = declaredTracks(ttml)
        // The head already speaks for every kind the body carries, so this is
        // Apple's own document; only its timeline needs formatting.
        if (declared.any && !hasUndeclaredInlineTrack(ttml, declared)) {
            val formatted = formatTimeline(ttml)
            return TtmlFormatConversion(formatted, formatted != ttml)
        }
        val migrated = migrateAuxiliaryTracks(ttml, declared)
        val body = nextElement(migrated, "body", 0)
        val normalized = normalizeTags(
            migrated,
            wordTimed = body != null && hasTimedSpan(migrated, body),
        )
        val result = formatTimeline(normalized)
        return TtmlFormatConversion(result, result != ttml)
    }

    /**
     * Copies background words from Apple's simplified replacement track into
     * the timed words already present in the body. Only the word text changes;
     * foreground text, tags, attributes, timing and surrounding whitespace stay
     * byte-for-byte intact. Ambiguous or unsupported structure is left alone.
     */
    fun repairSimplifiedReplacementBackgrounds(ttml: String): String {
        if (!ttml.contains(ROLE_BACKGROUND) || !ttml.contains(REPLACEMENT_TYPE) ||
            !TtmlInputPolicy.isAcceptable(ttml)
        ) {
            return ttml
        }
        val root = documentRoot(ttml) ?: return ttml
        if (attribute(root.attributes, DEFAULT_NAMESPACE) != TTML_NAMESPACE ||
            attribute(root.attributes, TTM_DECLARATION) != TTML_METADATA_NAMESPACE ||
            attribute(root.attributes, ITUNES_DECLARATION) != ITUNES_NAMESPACE
        ) {
            return ttml
        }

        val rootChildren = childElements(ttml, root)
        val head = rootChildren.singleOrNull { isElement(ttml, it.start, "head") } ?: return ttml
        val body = rootChildren.singleOrNull { isElement(ttml, it.start, "body") } ?: return ttml
        if (!namespacesAreSupported(head.attributes, TTML_NAMESPACE) ||
            !namespacesAreSupported(body.attributes, TTML_NAMESPACE)
        ) {
            return ttml
        }

        val headChildren = childElements(ttml, head)
        val metadata = headChildren.singleOrNull { isElement(ttml, it.start, "metadata") }
            ?: return ttml
        if (!namespacesAreSupported(metadata.attributes, TTML_NAMESPACE)) return ttml

        val metadataChildren = childElements(ttml, metadata)
        val appleMetadata = metadataChildren.singleOrNull {
            isElement(ttml, it.start, "iTunesMetadata")
        } ?: return ttml
        if (attribute(appleMetadata.attributes, DEFAULT_NAMESPACE) != ITUNES_NAMESPACE ||
            !namespacesAreSupported(appleMetadata.attributes, ITUNES_NAMESPACE)
        ) {
            return ttml
        }

        val appleChildren = childElements(ttml, appleMetadata)
        val translations = appleChildren.singleOrNull {
            isElement(ttml, it.start, "translations")
        } ?: return ttml
        if (!namespacesAreSupported(translations.attributes, ITUNES_NAMESPACE)) return ttml

        val replacements = childElements(ttml, translations).filter { child ->
            isElement(ttml, child.start, "translation") &&
                attribute(child.attributes, TYPE_ATTRIBUTE) == REPLACEMENT_TYPE &&
                attribute(child.attributes, LANGUAGE_ATTRIBUTE) == TRANSLATION_LANGUAGE
        }
        val replacement = replacements.singleOrNull() ?: return ttml
        if (!namespacesAreSupported(replacement.attributes, ITUNES_NAMESPACE)) return ttml

        val replacementByKey = linkedMapOf<String, Element>()
        childElements(ttml, replacement).forEach { text ->
            if (!isElement(ttml, text.start, "text") ||
                !namespacesAreSupported(text.attributes, ITUNES_NAMESPACE)
            ) {
                return ttml
            }
            val key = attribute(text.attributes, FOR_ATTRIBUTE)?.takeIf(String::isNotBlank)
                ?: return ttml
            if (replacementByKey.put(key, text) != null) return ttml
        }
        if (replacementByKey.isEmpty()) return ttml

        val lines = bodyLines(ttml, body) ?: return ttml
        val linesByKey = linkedMapOf<String, Element>()
        lines.forEach { line ->
            val key = attribute(line.attributes, KEY_ATTRIBUTE)?.takeIf(String::isNotBlank)
            if (key != null && linesByKey.put(key, line) != null) return ttml
        }

        val edits = mutableListOf<Edit>()
        linesByKey.forEach { (key, line) ->
            val replacementText = replacementByKey[key] ?: return@forEach
            edits += replacementBackgroundEdits(ttml, line, replacementText) ?: return@forEach
        }
        return applyEdits(ttml, edits)
    }

    /** Skips leading XML literals and accepts a canonical document element. */
    private fun documentRoot(ttml: String): Element? {
        var index = if (ttml.isNotEmpty() && ttml[0] == '\uFEFF') 1 else 0
        while (index < ttml.length) {
            while (index < ttml.length && ttml[index].isWhitespace()) index += 1
            if (index >= ttml.length) return null
            if (ttml.startsWith("<?", index) || ttml.startsWith("<!--", index)) {
                index = literalEnd(ttml, index)
                continue
            }
            if (!ttml.startsWith("<", index) || ttml.startsWith("<!", index) ||
                ttml.startsWith("</", index)
            ) {
                return null
            }
            val end = tagEnd(ttml, index)
            if (end < 0 || elementName(ttml, index, end) != "tt") return null
            return parseElement(ttml, "tt", index, end)
        }
        return null
    }

    /** Finds keyed lyric lines through the body's canonical div nests. */
    private fun bodyLines(ttml: String, body: Element): List<Element>? {
        val lines = mutableListOf<Element>()

        fun collect(parent: Element): Boolean {
            childElements(ttml, parent).forEach { child ->
                when {
                    isElement(ttml, child.start, "div") -> {
                        if (!namespacesAreSupported(child.attributes, TTML_NAMESPACE) ||
                            !collect(child)
                        ) {
                            return false
                        }
                    }
                    isElement(ttml, child.start, "p") -> {
                        if (!namespacesAreSupported(child.attributes, TTML_NAMESPACE)) return false
                        lines += child
                    }
                    else -> return false
                }
            }
            return true
        }

        return if (collect(body)) lines else null
    }

    /**
     * A local namespace may be repeated with its canonical value, but changing
     * the default or Apple/TTML prefixes makes the lexical structure ambiguous.
     */
    private fun namespacesAreSupported(attributes: String, vararg defaults: String): Boolean {
        val defaultNamespace = attribute(attributes, DEFAULT_NAMESPACE)
        if (defaultNamespace != null && defaults.none { it == defaultNamespace }) return false
        val ttm = attribute(attributes, TTM_DECLARATION)
        if (ttm != null && ttm != TTML_METADATA_NAMESPACE) return false
        val itunes = attribute(attributes, ITUNES_DECLARATION)
        return itunes == null || itunes == ITUNES_NAMESPACE
    }

    /** Direct x-bg groups. A renamed or prefixed group is unsupported. */
    private fun backgroundGroups(
        ttml: String,
        parent: Element,
        vararg defaults: String,
    ): List<Element>? {
        val groups = mutableListOf<Element>()
        childElements(ttml, parent).forEach { child ->
            if (attribute(child.attributes, ROLE_ATTRIBUTE) != ROLE_BACKGROUND) return@forEach
            if (!isElement(ttml, child.start, "span") ||
                !namespacesAreSupported(child.attributes, *defaults)
            ) {
                return null
            }
            groups += child
        }
        return groups
    }

    /**
     * Timed leaf spans in one background group. Their begin/end pair must be
     * readable and unique so a replacement word cannot be attached twice.
     */
    private fun timedWords(
        ttml: String,
        background: Element,
        vararg defaults: String,
    ): List<Element>? {
        val words = mutableListOf<Element>()
        val ranges = mutableSetOf<Pair<BigDecimal, BigDecimal>>()

        fun hasTimingBelow(element: Element): Boolean =
            childElements(ttml, element).any { child ->
                attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE) != null ||
                    attribute(child.attributes, END_ATTRIBUTE_VALUE) != null ||
                    hasTimingBelow(child)
            }

        fun collect(parent: Element): Boolean {
            childElements(ttml, parent).forEach { child ->
                if (!isElement(ttml, child.start, "span") ||
                    !namespacesAreSupported(child.attributes, *defaults) ||
                    attribute(child.attributes, ROLE_ATTRIBUTE) != null
                ) {
                    return false
                }
                val begin = attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE)
                val end = attribute(child.attributes, END_ATTRIBUTE_VALUE)
                if ((begin == null) != (end == null)) return false
                if (begin == null) {
                    if (!collect(child)) return false
                } else {
                    val start = timelineTime(begin) ?: return false
                    val finish = timelineTime(end) ?: return false
                    if (finish <= start || hasTimingBelow(child) || !ranges.add(start to finish)) {
                        return false
                    }
                    words += child
                }
            }
            return true
        }

        if (!collect(background) || words.isEmpty()) return null
        return words
    }

    /** Raw text of a word span, or null when it contains unsupported markup. */
    private fun wordText(ttml: String, word: Element): String? {
        if (word.selfClosing) return null
        return content(ttml, word).takeUnless { '<' in it }
    }

    /**
     * Copies only the source x-bg words whose begin/end pair exists in the
     * matching body group. Both groups must have the same complete time map.
     */
    private fun replacementBackgroundEdits(
        ttml: String,
        line: Element,
        replacementText: Element,
    ): List<Edit>? {
        val targetGroups = backgroundGroups(ttml, line, TTML_NAMESPACE) ?: return null
        if (targetGroups.isEmpty()) return emptyList()
        val sourceGroups = backgroundGroups(
            ttml,
            replacementText,
            TTML_NAMESPACE,
        ) ?: return null
        if (sourceGroups.size != targetGroups.size) return null
        // The metadata parent is Apple XML; each source group must explicitly switch to TTML.
        if (sourceGroups.any { attribute(it.attributes, DEFAULT_NAMESPACE) != TTML_NAMESPACE }) return null

        val edits = mutableListOf<Edit>()
        targetGroups.zip(sourceGroups).forEach { (targetGroup, sourceGroup) ->
            listOf(BEGIN_ATTRIBUTE_VALUE, END_ATTRIBUTE_VALUE).forEach { pattern ->
                val targetTime = attribute(targetGroup.attributes, pattern)
                val sourceTime = attribute(sourceGroup.attributes, pattern)
                if (targetTime != null && sourceTime != null) {
                    val targetValue = timelineTime(targetTime) ?: return null
                    val sourceValue = timelineTime(sourceTime) ?: return null
                    if (targetValue.compareTo(sourceValue) != 0) return null
                }
            }
            val targetWords = timedWords(ttml, targetGroup, TTML_NAMESPACE) ?: return null
            val sourceWords = timedWords(
                ttml,
                sourceGroup,
                TTML_NAMESPACE,
            ) ?: return null
            if (sourceWords.size != targetWords.size) return null

            val sourceByTime = sourceWords.associateBy { word ->
                val start = timelineTime(attribute(word.attributes, BEGIN_ATTRIBUTE_VALUE))
                    ?: return null
                val end = timelineTime(attribute(word.attributes, END_ATTRIBUTE_VALUE))
                    ?: return null
                start to end
            }
            if (sourceByTime.size != sourceWords.size) return null

            targetWords.forEach { target ->
                val start = timelineTime(attribute(target.attributes, BEGIN_ATTRIBUTE_VALUE))
                    ?: return null
                val end = timelineTime(attribute(target.attributes, END_ATTRIBUTE_VALUE))
                    ?: return null
                val source = sourceByTime[start to end] ?: return null
                val replacement = wordText(ttml, source)?.takeIf(String::isNotBlank) ?: return null
                val current = wordText(ttml, target) ?: return null
                if (replacement != current) {
                    edits += Edit(target.contentStart, target.contentEnd, replacement)
                }
            }
        }
        return edits
    }

    /** Sort lines and head entries together, retaining simultaneous voices. */
    private fun formatTimeline(ttml: String): String {
        val body = nextElement(ttml, "body", 0) ?: return ttml
        val root = TimelineSection(body, "", "")
        val lines = mutableListOf<TimelineLine>()
        if (!collectTimeline(ttml, root, emptyList(), lines) || lines.isEmpty()) return ttml
        val ordered = lines.sortedBy(TimelineLine::begin)
        val edits = mutableListOf<Edit>()
        if (ordered != lines) {
            edits += Edit(body.contentStart, body.contentEnd, renderTimeline(ttml, root, ordered))
        }

        // Without unique keys a head entry cannot be associated unambiguously.
        val keys = ordered.map { attribute(it.element.attributes, KEY_ATTRIBUTE)?.takeIf(String::isNotBlank) }
        if ((keys.any { it == null } || keys.distinct().size != keys.size) && declaredTracks(ttml).any) return ttml
        if (keys.all { it != null } && keys.distinct().size == keys.size) {
            val metadata = nextElement(ttml, "metadata", 0)
            if (metadata != null) {
                listOf("translations" to "translation", "transliterations" to "transliteration")
                    .forEach { (containerName, itemName) ->
                        val container = nextElement(ttml, containerName, metadata.contentStart)
                            ?.takeIf { it.end <= metadata.contentEnd } ?: return@forEach
                        childElements(ttml, container).filter { isElement(ttml, it.start, itemName) }
                            .forEach { track ->
                                val formatted = formatTrack(ttml, track, ordered, containerName == "translations")
                                    ?: return ttml
                                formatted.edit?.let(edits::add)
                            }
                    }
            }
        }
        return applyEdits(ttml, edits)
    }

    /** Unknown structure or timestamps are left intact instead of guessed. */
    private fun collectTimeline(
        ttml: String,
        section: TimelineSection,
        ancestors: List<TimelineSection>,
        lines: MutableList<TimelineLine>,
    ): Boolean {
        val children = childElements(ttml, section.element)
        if (children.isEmpty()) return false
        var cursor = section.element.contentStart
        var pending = ""
        children.forEach { child ->
            val prefix = pending + ttml.substring(cursor, child.start)
            pending = ""
            when {
                isElement(ttml, child.start, "p") -> {
                    val begin = timelineTime(attribute(child.attributes, BEGIN_ATTRIBUTE_VALUE)) ?: return false
                    lines += TimelineLine(child, begin, ancestors, prefix)
                }
                isElement(ttml, child.start, "div") -> {
                    if (childElements(ttml, child).isEmpty()) {
                        pending = prefix + ttml.substring(child.start, child.end)
                    } else {
                        val nested = TimelineSection(child, prefix, "")
                        if (!collectTimeline(ttml, nested, ancestors + nested, lines)) return false
                    }
                }
                else -> return false
            }
            cursor = child.end
        }
        section.tail = pending + ttml.substring(cursor, section.element.contentEnd)
        return true
    }

    /** Reopen interleaving sections so each line keeps its original section. */
    private fun renderTimeline(ttml: String, root: TimelineSection, lines: List<TimelineLine>): String = buildString {
        val opened = mutableListOf<TimelineSection>()
        val seen = mutableSetOf<TimelineSection>()
        val last = mutableMapOf<TimelineSection, Int>()
        lines.forEachIndexed { index, line -> line.ancestors.forEach { last[it] = index } }
        fun closeTo(depth: Int, index: Int) {
            while (opened.size > depth) {
                val section = opened.removeAt(opened.lastIndex)
                if (last[section] == index) append(section.tail)
                append(ttml, section.element.contentEnd, section.element.end)
            }
        }
        lines.forEachIndexed { index, line ->
            val common = opened.zip(line.ancestors).takeWhile { (a, b) -> a === b }.size
            closeTo(common, index - 1)
            line.ancestors.drop(common).forEach { section ->
                if (seen.add(section)) append(section.prefix)
                var tag = ttml.substring(section.element.start, section.element.contentStart)
                val run = lines.drop(index).takeWhile { section in it.ancestors }
                tag = replaceAttribute(tag, BEGIN_ATTRIBUTE_VALUE, attribute(line.element.attributes, BEGIN_ATTRIBUTE_VALUE))
                val endLine = run.mapNotNull { candidate ->
                    timelineTime(attribute(candidate.element.attributes, END_ATTRIBUTE_VALUE))?.let { it to candidate }
                }.maxByOrNull { it.first }?.second
                tag = replaceAttribute(tag, END_ATTRIBUTE_VALUE, endLine?.let { attribute(it.element.attributes, END_ATTRIBUTE_VALUE) })
                append(tag)
                opened += section
            }
            append(line.prefix)
            append(ttml, line.element.start, line.element.end)
        }
        closeTo(0, lines.lastIndex)
        append(root.tail)
    }

    private fun replaceAttribute(tag: String, pattern: Regex, value: String?): String {
        if (value == null) return tag
        val match = pattern.find(tag) ?: return tag
        val group = match.groups[1] ?: match.groups[2] ?: return tag
        return tag.replaceRange(group.range, value)
    }

    /** Keep an entry per line even after a group's translation is combined. */
    private fun formatTrack(
        ttml: String,
        track: Element,
        lines: List<TimelineLine>,
        merge: Boolean,
    ): TrackFormatting? {
        val entries = childElements(ttml, track)
        if (entries.isEmpty()) return TrackFormatting(null)
        if (entries.any { !isElement(ttml, it.start, "text") }) return null
        val keys = lines.map { requireNotNull(attribute(it.element.attributes, KEY_ATTRIBUTE)) }
        val byKey = entries.associateBy { attribute(it.attributes, FOR_ATTRIBUTE) }
        if (byKey.size != entries.size || byKey.keys.any { it !in keys }) return null
        val texts = keys.map { key ->
            byKey[key]?.let { content(ttml, it) }?.takeIf(String::isNotBlank) ?: ABSENT_TEXT
        }.toMutableList()
        if (merge) {
            lines.indices.groupBy { lines[it].begin }.values.filter { it.size > 1 }.forEach { group ->
                val combined = group.map { texts[it] }.filter(String::isNotBlank).distinct().joinToString("\n")
                texts[group.first()] = combined.ifBlank { ABSENT_TEXT }
                group.drop(1).forEach { texts[it] = ABSENT_TEXT }
            }
        }
        val prefixes = mutableMapOf<Element, String>()
        var cursor = track.contentStart
        entries.forEach { entry ->
            prefixes[entry] = ttml.substring(cursor, entry.start)
            cursor = entry.end
        }
        val formatted = buildString {
            keys.forEachIndexed { index, key ->
                val entry = byKey[key]
                if (entry == null) {
                    append("<text for=").append(quoteRawAttributeValue(key)).append('>')
                    append(texts[index]).append("</text>")
                } else {
                    append(prefixes.getValue(entry))
                    if (entry.selfClosing) {
                        append("<text${entry.attributes}>${texts[index]}</text>")
                    } else {
                        append(ttml, entry.start, entry.contentStart)
                        append(texts[index])
                        append(ttml, entry.contentEnd, entry.end)
                    }
                }
            }
            append(ttml, cursor, track.contentEnd)
        }
        return TrackFormatting(
            if (formatted == content(ttml, track)) null else Edit(track.contentStart, track.contentEnd, formatted),
        )
    }

    /** Exact decimal comparison makes 01:30.100 and 90.1s the same start. */
    private fun timelineTime(value: String?): BigDecimal? {
        val text = value?.trim() ?: return null
        val offset = TIMELINE_OFFSET.matchEntire(text)
        val time = if (offset != null) {
            val multiplier = when (offset.groupValues[2]) {
                "ms" -> BigDecimal("0.001")
                "m" -> BigDecimal(60)
                "h" -> BigDecimal(3600)
                else -> BigDecimal.ONE
            }
            offset.groupValues[1].toBigDecimal().multiply(multiplier)
        } else {
            val parts = text.split(':').takeIf { it.size in 1..3 } ?: return null
            var seconds = BigDecimal.ZERO
            parts.forEach { part ->
                if (!TIMELINE_NUMBER.matches(part)) return null
                seconds = seconds.multiply(BigDecimal(60)).add(part.toBigDecimal())
            }
            seconds
        }
        return time.stripTrailingZeros()
    }

    private class TimelineSection(val element: Element, val prefix: String, var tail: String)
    private data class TrackFormatting(val edit: Edit?)
    private data class TimelineLine(
        val element: Element,
        val begin: BigDecimal,
        val ancestors: List<TimelineSection>,
        val prefix: String,
    )

    private fun declaredTracks(ttml: String) = DeclaredTracks(
        translations = nextElement(ttml, "translations", 0) != null,
        transliterations = nextElement(ttml, "transliterations", 0) != null,
    )

    /** True when the body still carries a kind the head leaves undeclared. */
    private fun hasUndeclaredInlineTrack(ttml: String, declared: DeclaredTracks): Boolean {
        val body = nextElement(ttml, "body", 0) ?: return false
        var index = body.contentStart
        while (index < body.contentEnd) {
            val open = ttml.indexOf('<', index)
            if (open < 0 || open >= body.contentEnd) return false
            val literal = literalEnd(ttml, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(ttml, open)
            if (end < 0) return false
            when (attribute(ttml.substring(open, end + 1), ROLE_ATTRIBUTE)) {
                ROLE_TRANSLATION -> if (!declared.translations) return true
                ROLE_ROMAN -> if (!declared.transliterations) return true
            }
            index = end + 1
        }
        return false
    }

    /** Moves inline `x-translation` / `x-roman` spans into a head `<iTunesMetadata>`. */
    private fun migrateAuxiliaryTracks(ttml: String, declared: DeclaredTracks): String {
        val metadata = nextElement(ttml, "metadata", 0) ?: return ttml

        val translations = mutableListOf<TrackEntry>()
        val transliterations = mutableListOf<TrackEntry>()
        val edits = mutableListOf<Edit>()

        var cursor = metadata.end
        while (true) {
            val line = nextElement(ttml, "p", cursor) ?: break
            cursor = line.end
            val key = attribute(line.attributes, KEY_ATTRIBUTE) ?: continue
            collectLine(ttml, line, key, declared, translations, transliterations, edits)
        }

        // Every keyed line contributes an entry, so a line the source left
        // untranslated still holds its place; whether any entry carries text
        // decides if the track is written at all. The edits are applied either
        // way, because a line may carry a background vocal that needs its body
        // rewrite even when no track follows.
        if (translations.none(TrackEntry::hasText) && transliterations.none(TrackEntry::hasText)) {
            return applyEdits(ttml, edits)
        }

        edits += declareTracks(
            ttml,
            metadata,
            buildTracks(translations, transliterations),
            declared,
        )
        return applyEdits(ttml, edits)
    }

    /**
     * Places the tracks in the head, joining the `<iTunesMetadata>` AMLL already
     * wrote for `<songwriters>` — ahead of what it holds, the order Apple uses —
     * rather than opening a second one beside it.
     */
    private fun declareTracks(
        ttml: String,
        metadata: Element,
        tracks: String,
        declared: DeclaredTracks,
    ): Edit {
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
            else -> {
                // A newly migrated transliteration belongs after a translations
                // track that was already present. Every other new track starts
                // at the front, where buildTracks keeps Apple's kind order.
                val insertAt = if (declared.translations && !declared.transliterations) {
                    nextElement(ttml, "translations", container.contentStart)
                        ?.takeIf { it.end <= container.contentEnd }
                        ?.end
                        ?: container.contentStart
                } else {
                    container.contentStart
                }
                Edit(insertAt, insertAt, tracks)
            }
        }
    }

    private fun collectLine(
        ttml: String,
        line: Element,
        key: String,
        declared: DeclaredTracks,
        translations: MutableList<TrackEntry>,
        transliterations: MutableList<TrackEntry>,
        edits: MutableList<Edit>,
    ) {
        // Only one track of each kind survives the parser constraint, so the
        // first translation and first romanization of the line are kept. An
        // empty span is no translation, so a later non-empty one still counts.
        var translation: String? = null
        var roman: String? = null
        var backgroundTranslation: String? = null
        var backgroundRoman: String? = null

        val children = childElements(ttml, line)
        children.forEach { child ->
            when (attribute(child.attributes, ROLE_ATTRIBUTE)) {
                ROLE_TRANSLATION -> if (!declared.translations) {
                    if (translation == null) translation = auxiliaryText(ttml, child)
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_ROMAN -> if (!declared.transliterations) {
                    if (roman == null) roman = auxiliaryText(ttml, child)
                    edits += removal(ttml, child, line.contentStart)
                }
                ROLE_BACKGROUND -> {
                    val nested = childElements(ttml, child)
                    val removals = mutableListOf<Edit>()
                    nested.forEach { span ->
                        when (attribute(span.attributes, ROLE_ATTRIBUTE)) {
                            ROLE_TRANSLATION -> if (!declared.translations) {
                                if (backgroundTranslation == null) {
                                    backgroundTranslation = auxiliaryText(ttml, span)
                                }
                                removals += removal(ttml, span, child.contentStart)
                            }
                            ROLE_ROMAN -> if (!declared.transliterations) {
                                if (backgroundRoman == null) {
                                    backgroundRoman = auxiliaryText(ttml, span)
                                }
                                removals += removal(ttml, span, child.contentStart)
                            }
                        }
                    }
                    edits += backgroundEdits(ttml, line, children, child, nested, removals)
                }
            }
        }

        translations += TrackEntry(key, translation, backgroundTranslation)
        transliterations += TrackEntry(key, roman, backgroundRoman)
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

    /** Seconds in `3:02.550` or AMLL's offset form `1.5s`; null when unreadable. */
    private fun clockSeconds(value: String?): Double? {
        val clock = value?.let { if (':' in it) it else it.removeSuffix("s") } ?: return null
        val parts = clock.split(':').takeIf { it.size in 1..3 } ?: return null
        var seconds = 0.0
        parts.forEach { part ->
            seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        }
        return seconds
    }

    /**
     * The body of one `<text>`: the line's own text, then its background one
     * wrapped in an `x-bg` span. A line without text of its own still opens
     * with [ABSENT_TEXT], both to keep an entry per line and because Apple
     * reads what precedes the `x-bg` span as the main translation.
     */
    private fun StringBuilder.appendEntryText(entry: TrackEntry) {
        append(entry.main ?: ABSENT_TEXT)
        val background = entry.background ?: return
        append("<span xmlns=\"").append(TTML_NAMESPACE)
        append("\" xmlns:ttm=\"").append(TTML_METADATA_NAMESPACE)
        append("\" ttm:role=\"").append(ROLE_BACKGROUND).append("\">")
        append(parenthesized(background))
        append("</span>")
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
        appendTrack(translations, "translations", "translation", TRANSLATION_LANGUAGE, TRANSLATION_TYPE)
        appendTrack(transliterations, "transliterations", "transliteration", TRANSLITERATION_LANGUAGE)
    }

    private fun StringBuilder.appendTrack(
        entries: List<TrackEntry>,
        container: String,
        item: String,
        language: String,
        type: String? = null,
    ) {
        // Nothing to say for this kind at all, so the track is not opened —
        // rather than opened over a column of placeholders.
        if (entries.none(TrackEntry::hasText)) return
        append('<').append(container).append('>')
        append('<').append(item)
        if (type != null) append(" type=\"").append(type).append('"')
        append(" xml:lang=\"").append(language).append("\">")
        entries.forEach { entry ->
            append("<text for=").append(quoteRawAttributeValue(entry.key)).append('>')
            appendEntryText(entry)
            append("</text>")
        }
        append("</").append(item).append('>')
        append("</").append(container).append('>')
    }

    /** A span's content, or `null` when it holds nothing worth carrying over. */
    private fun auxiliaryText(ttml: String, element: Element): String? =
        content(ttml, element).takeIf(String::isNotBlank)

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

    private fun hasTimedSpan(text: String, body: Element): Boolean {
        var index = body.contentStart
        while (index < body.contentEnd) {
            val open = text.indexOf('<', index)
            if (open < 0 || open >= body.contentEnd) return false
            val literal = literalEnd(text, open)
            if (literal > 0) {
                index = literal
                continue
            }
            val end = tagEnd(text, open)
            if (end < 0) return false
            if (isElement(text, open, "span") &&
                attribute(text.substring(open, end + 1), BEGIN_ATTRIBUTE_VALUE) != null
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

    /** Which auxiliary kinds the head already carries, so they are not moved twice. */
    private data class DeclaredTracks(
        val translations: Boolean,
        val transliterations: Boolean,
    ) {
        val any get() = translations || transliterations
    }

    /**
     * One line's place in a track. Both texts are `null` when the line said
     * nothing of this kind, which still earns it a placeholder entry as long as
     * some other line spoke up.
     */
    private data class TrackEntry(
        val key: String,
        val main: String?,
        val background: String?,
    ) {
        val hasText get() = main != null || background != null
    }

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
