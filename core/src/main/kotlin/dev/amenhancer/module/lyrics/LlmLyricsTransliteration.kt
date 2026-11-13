package dev.amenhancer.module.lyrics

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One context-aware transliteration pass over a Line/Word TTML document.
 *
 * The body remains byte-for-byte unchanged apart from keys that must be
 * generated so the new Apple transliteration track can refer to each line.
 * Existing translations and pronunciation are preserved; a usable
 * pronunciation anywhere in the song means no request is prepared.
 */
public class LlmTransliterationDocument private constructor(
    private val document: TransliterationDocument,
) {
    /** Root language label only. It is a prompt hint and may be wrong for AMLL. */
    public val sourceLanguage: String?
        get() = document.language

    internal fun isWithinInputBounds(
        maxUnits: Int,
        maxUnitChars: Int,
        maxTotalChars: Int,
    ): Boolean {
        var units = 0
        var totalChars = 0
        for (line in document.lines) {
            for (group in line.groups) {
                for (unit in group.units) {
                    units += 1
                    if (units > maxUnits || unit.source.length > maxUnitChars) return false
                    totalChars += unit.source.length
                    if (totalChars > maxTotalChars) return false
                }
            }
        }
        return units > 0
    }

    internal fun prompt(title: String?, artist: String?): String {
        val songContext = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            "Song metadata: title=" + JSONObject.quote(title) + ", artist=" + JSONObject.quote(artist)
        } else {
            "Song metadata: none"
        }
        val sourceHint = document.language?.takeIf(String::isNotBlank) ?: "unknown"
        val lines = JSONArray()
        document.lines.filter { it.groups.isNotEmpty() }.forEach { line ->
            val groups = JSONArray()
            line.groups.forEach { group ->
                val units = JSONArray()
                group.units.forEach { unit ->
                    units.put(
                        JSONObject()
                            .put("source", unit.source)
                            .put("begin", unit.begin)
                            .put("end", unit.end)
                            .put("after", unit.after),
                    )
                }
                groups.put(
                    JSONObject()
                        .put("role", if (group.background) "background" else "main")
                        .put("units", units),
                )
            }
            lines.put(
                JSONObject()
                    .put("id", line.id)
                    .put("key", line.key)
                    .put("language_hint", line.language ?: JSONObject.NULL)
                    .put("context", line.context)
                    .put("groups", groups),
            )
        }
        val input = JSONObject()
            .put("timing", if (document.timing == TransliterationTiming.WORD) "Word" else "Line")
            .put("root_language_hint", sourceHint)
            .put("lines", lines)
        return """### ROLE
You are a lyric transliterator. Convert the lyrics to a consistent Latin-script reading using the
whole sentence and musical context, then project that reading back onto the supplied units.

### CONTEXT
$songContext
Root language hint: $sourceHint
The root hint can be stale or forced by AMLL (for example a fixed "ko"). Never use it to overrule
the actual characters, words, names, pronunciation, or mixed-language context in the lyrics.

### READING RULES
- Decide each kanji/hanzi/word pronunciation in context before assigning readings to units.
- Do not split, merge, reorder, or invent timed units. Return exactly one reading per source unit
  in exactly the same group order.
- Keep main vocals and each background group separate.
- For Word timing, preserve the original unit boundary. Put a space at the end of a non-English
  reading when a word boundary is needed before the next unit. Keep Japanese readings such as
  watashi, kagami, and shin'ya. Write complete long vowels, for example sou, kyou, yuuki, and too.
  A single source character keeps its complete reading: 鏡 becomes kagami, not ka ga mi. Within
  one merged unit, retain only boundaries that are knowable from the source: 鏡よ becomes
  kagami yo and 寂しい becomes sabi shi i. Do not mechanically split one character's reading.
- For Line timing, return one whole-line reading per group. Do not create word timestamps. The
  writer uses the original line or voice timing.
- A non-English reading must contain Latin letters only for source-script letters. Remove source
  punctuation, brackets, and decoration from non-English readings while preserving linguistic
  apostrophes such as the one in shin'ya. Removing punctuation must preserve the word boundary
  it carried: い」も becomes i mo, never imo.
- A standalone non-English punctuation/symbol unit must use exactly one U+200B character.
  Never use an empty string or ordinary whitespace for that unit.
- English units, including English punctuation and mixed-line English fragments, must copy the
  source string exactly: same case, apostrophes, quotes, hyphens, ellipses, symbols, and internal
  whitespace. Mark every English unit with english_units. Do not use U+200B for English.
- Judge English from meaning and context, never from ASCII alone. A Latin word in another language
  is not automatically English. In a unit that mixes languages, preserve its English substring
  verbatim while transliterating the rest; do not mark that whole mixed unit as English.
- Chinese, Japanese, Korean, Cyrillic, and other languages are all eligible. Choose the language
  from the lyrics themselves, not from the root hint.
- Use Hepburn-style Japanese for Japanese, Hanyu Pinyin for Mandarin, and a suitable consistent
  Latin transliteration for other languages. Keep spelling consistent across repeated lines.

### OUTPUT
Return only JSON, with no Markdown or explanation. The lang tag must be an explicit Latin-script
BCP 47 tag such as ja-Latn, ko-Latn, zh-Latn, or ru-Latn.
Preserve the id order and every group. english_units is mandatory; it is an array of zero-based
English unit indexes for each group, using [] for a group with no English.
Example:
{"lang":"ja-Latn","lines":[
  {"id":"L1","groups":[["kimi ","no ","na"],["Boo"]],
   "english_units":[[],[0]]}
]}

### INPUT
$input"""
    }

    /** Parse one model reply and return the complete original document plus its new track. */
    internal fun withResponse(content: String): String? {
        val cleaned = TRAILING_JSON_FENCE
            .replace(LEADING_JSON_FENCE.replace(content.trim(), ""), "")
            .trim()
        val answer = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        val language = normalizeLatinLanguageTag(answer.opt("lang") as? String) ?: return null
        val rows = answer.optJSONArray("lines") ?: return null
        val expected = document.lines.filter { it.groups.isNotEmpty() }.associateBy { it.id }
        if (rows.length() != expected.size) return null

        val parsed = HashMap<String, AnswerLine>(expected.size)
        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONObject(rowIndex) ?: return null
            val id = row.opt("id") as? String ?: return null
            val line = expected[id] ?: return null
            if (id in parsed) return null
            val groups = row.optJSONArray("groups") ?: return null
            val english = row.optJSONArray("english_units") ?: return null
            if (groups.length() != line.groups.size || english.length() != line.groups.size) {
                return null
            }
            val readings = ArrayList<List<String>>(line.groups.size)
            val englishUnits = ArrayList<List<Int>>(line.groups.size)
            line.groups.forEachIndexed { groupIndex, group ->
                val groupReadings = groups.optJSONArray(groupIndex) ?: return null
                val groupEnglish = english.optJSONArray(groupIndex) ?: return null
                if (groupReadings.length() != group.units.size) return null
                val flags = parseEnglishIndexes(groupEnglish, group.units.size) ?: return null
                val values = ArrayList<String>(group.units.size)
                group.units.forEachIndexed { unitIndex, unit ->
                    val reading = groupReadings.opt(unitIndex) as? String ?: return null
                    if (!validReading(unit, reading, unitIndex in flags)) return null
                    values += reading
                }
                readings += values
                englishUnits += flags.sorted()
            }
            parsed[id] = AnswerLine(readings, englishUnits)
        }
        if (parsed.size != expected.size) return null
        val track = renderTrack(document, language, parsed) ?: return null
        return injectTrack(document, track)
            ?.takeIf(TtmlInputPolicy::isAcceptable)
    }

    public companion object {
        /** Inspect once and return the eligible transliteration pass, or null when it is unsafe. */
        public fun prepare(ttml: String): LlmTransliterationDocument? {
            if (!TtmlInputPolicy.isAcceptable(ttml)) return null
            if (hasUsablePronunciation(ttml)) return null
            val document = parseDocument(ttml) ?: return null
            if (document.lines.none { line ->
                    line.groups.any { group -> group.units.isNotEmpty() }
                }
            ) {
                return null
            }
            return LlmTransliterationDocument(document)
        }
    }
}

/** Add a generated pronunciation track without disturbing a translated or original body. */
public object LlmLyricsTrackComposer {
    /**
     * Use [translated] when present and otherwise [original]. A missing
     * [transliterated] document returns that base immediately, so translation
     * remains usable even when transliteration cannot parse the lyrics.
     */
    public fun compose(
        original: String,
        translated: String?,
        transliterated: String?,
    ): String? {
        if (!TtmlInputPolicy.isAcceptable(original)) return null
        val base = translated ?: original
        if (!TtmlInputPolicy.isAcceptable(base)) return null
        if (transliterated.isNullOrBlank()) return base
        if (!TtmlInputPolicy.isAcceptable(transliterated)) return null

        if (hasUsablePronunciation(base)) return base
        val baseDocument = parseDocument(base) ?: return null
        val sourceDocument = parseDocument(transliterated) ?: return null
        if (!sameLyrics(baseDocument, sourceDocument)) return null
        val track = parseTransliterationTrack(sourceDocument) ?: return null
        if (!trackMatchesDocument(track, sourceDocument)) return null
        return injectTrack(baseDocument, track.raw)
            ?.takeIf(TtmlInputPolicy::isAcceptable)
    }
}

private enum class TransliterationTiming {
    WORD,
    LINE,
}

private data class TransliterationDocument(
    val ttml: String,
    val root: TtmlElement,
    val head: TtmlElement?,
    val body: TtmlElement,
    val timing: TransliterationTiming,
    val language: String?,
    val lines: List<TransliterationLine>,
)

private data class TransliterationLine(
    val id: String,
    val key: String,
    val keyInsertion: Int,
    val language: String?,
    val context: String,
    val groups: List<TransliterationGroup>,
)

private data class TransliterationGroup(
    val background: Boolean,
    val begin: String?,
    val end: String?,
    val units: List<TransliterationUnit>,
)

private data class TransliterationUnit(
    val source: String,
    val begin: String,
    val end: String,
    val after: String,
    val afterRaw: String,
)

private data class AnswerLine(
    val groups: List<List<String>>,
    val englishUnits: List<List<Int>>,
)

private data class ParagraphShape(
    val tag: TtmlTag,
    val element: TtmlElement,
    val begin: String,
    val end: String?,
    val declaredKey: String?,
    val decodedKey: String?,
    val keyInsertion: Int,
    val language: String?,
)

private data class RawChild(
    val tag: TtmlTag,
    val element: TtmlElement,
    val tail: String,
)

private data class MutableRawChild(
    val tag: TtmlTag,
    val element: TtmlElement,
    var tail: String = "",
)

private data class ElementLookup(
    val element: TtmlElement? = null,
    val valid: Boolean = true,
)

private data class TrackInsertion(
    val start: Int,
    val end: Int,
    val open: String,
    val close: String,
)

private data class ParsedTrack(
    val raw: String,
    val texts: List<TrackText>,
)

private data class TrackText(
    val key: String,
    val spans: List<TrackSpan>,
)

private data class TrackSpan(
    val begin: String,
    val end: String,
    val background: Boolean,
)

private val END_ATTRIBUTE = attributePattern("end")
private val FOR_ATTRIBUTE = attributePattern("for")
private val DEFAULT_NAMESPACE_ATTRIBUTE = attributePattern("xmlns")
private val WHITESPACE = Regex("\\s+")
private val LATIN_LANGUAGE = Regex(
    "[A-Za-z]{2,8}-Latn(?:-[A-Za-z0-9]{1,8})*",
    RegexOption.IGNORE_CASE,
)
private val LEADING_JSON_FENCE = Regex(
    "^" + 0x60.toChar().toString().repeat(3) + "(?:json)?\\s*",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_JSON_FENCE = Regex(
    "\\s*" + 0x60.toChar().toString().repeat(3) + "$",
    RegexOption.IGNORE_CASE,
)
private const val ZERO_WIDTH_SPACE = '\u200B'

private fun parseDocument(ttml: String): TransliterationDocument? {
    if (!TtmlInputPolicy.isAcceptable(ttml)) return null
    val lower = ttml.lowercase(Locale.ROOT)
    if (lower.contains("<!doctype") || lower.contains("<!entity")) return null

    val root = uniqueElement(ttml, "tt", 0, ttml.length)
        .takeIf(ElementLookup::valid)?.element ?: return null
    val timing = when (
        attribute(root.attributes, TIMING_ATTRIBUTE)?.trim()?.lowercase(Locale.ROOT)
    ) {
        "word" -> TransliterationTiming.WORD
        "line" -> TransliterationTiming.LINE
        else -> return null
    }
    val headLookup = uniqueElement(ttml, "head", root.contentStart, root.contentEnd)
    if (!headLookup.valid) return null
    val body = uniqueElement(ttml, "body", root.contentStart, root.contentEnd)
        .takeIf(ElementLookup::valid)?.element ?: return null
    val paragraphs = paragraphs(ttml, body) ?: return null
    if (paragraphs.isEmpty()) return null
    val lines = resolveLines(ttml, paragraphs, timing) ?: return null
    return TransliterationDocument(
        ttml = ttml,
        root = root,
        head = headLookup.element,
        body = body,
        timing = timing,
        language = languageOf(root.attributes),
        lines = lines,
    )
}

private fun paragraphs(ttml: String, body: TtmlElement): List<ParagraphShape>? {
    val result = mutableListOf<ParagraphShape>()
    val divStack = ArrayDeque<String>()
    var index = body.contentStart

    fun whitespaceOnly(from: Int, to: Int): Boolean =
        from >= to || decodeEntities(ttml.substring(from, to)).all(Char::isWhitespace)

    while (index < body.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= body.contentEnd) {
            if (!whitespaceOnly(index, body.contentEnd)) return null
            break
        }
        if (!whitespaceOnly(index, open)) return null
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val literalEnd = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (literalEnd < 0) return null
                val value = ttml.substring(open + CDATA_OPEN.length, literalEnd)
                if (!value.all(Char::isWhitespace)) return null
            }
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0 || end >= body.contentEnd) return null
        val tag = TtmlScanner.tag(ttml, open, end)
        val name = localName(tag.name).lowercase(Locale.ROOT)
        if (tag.closing) {
            if (name != "div" || divStack.isEmpty() || divStack.last() != name) return null
            divStack.removeLast()
            index = end + 1
            continue
        }
        when (name) {
            "div" -> if (!tag.selfClosing) divStack.addLast(name)
            "p" -> {
                if (divStack.any { it != "div" }) return null
                val element = TtmlScanner.elementAt(ttml, tag) ?: return null
                val begin = attribute(tag.attributes, BEGIN_ATTRIBUTE)?.trim()
                    ?.takeIf(String::isNotEmpty) ?: return null
                if (!isClock(begin)) return null
                val declaredEnd = attribute(tag.attributes, END_ATTRIBUTE)?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (declaredEnd != null && !isClock(declaredEnd)) return null
                val declaredKey = attribute(tag.attributes, KEY_ATTRIBUTE)
                    ?: attribute(tag.attributes, BARE_KEY_ATTRIBUTE)
                val decodedKey = declaredKey?.let(::decodeEntities)
                if (decodedKey != null && decodedKey.isBlank()) return null
                val namespace = attribute(tag.attributes, ITUNES_NAMESPACE_ATTRIBUTE)
                if (declaredKey == null && namespace != null && namespace != ITUNES_NAMESPACE) {
                    return null
                }
                val keyInsertion = if (declaredKey == null) {
                    if (element.selfClosing) {
                        var slash = end - 1
                        while (slash > open && ttml[slash].isWhitespace()) slash -= 1
                        slash
                    } else {
                        end
                    }
                } else {
                    -1
                }
                result += ParagraphShape(
                    tag = tag,
                    element = element,
                    begin = begin,
                    end = declaredEnd,
                    declaredKey = declaredKey,
                    decodedKey = decodedKey,
                    keyInsertion = keyInsertion,
                    language = languageOf(tag.attributes),
                )
                index = element.end
                continue
            }
            else -> return null
        }
        index = end + 1
    }
    if (divStack.isNotEmpty()) return null
    return result
}

private fun resolveLines(
    ttml: String,
    paragraphs: List<ParagraphShape>,
    timing: TransliterationTiming,
): List<TransliterationLine>? {
    val existingKeys = paragraphs.mapNotNull(ParagraphShape::decodedKey)
    if (existingKeys.distinct().size != existingKeys.size) return null
    val usedKeys = existingKeys.toMutableSet()
    listOf(KEY_ATTRIBUTE, BARE_KEY_ATTRIBUTE).forEach { pattern ->
        pattern.findAll(ttml).forEach { match ->
            usedKeys += decodeEntities(match.groups[1]?.value ?: match.groupValues[2])
        }
    }

    var nextKey = 1
    return paragraphs.mapIndexed { index, paragraph ->
        val key = paragraph.decodedKey ?: run {
            var generated: String
            do {
                generated = "ampp-llm-line-$nextKey"
                nextKey += 1
            } while (!usedKeys.add(generated))
            generated
        }
        val groups = when (timing) {
            TransliterationTiming.WORD -> parseWordGroups(ttml, paragraph)
            TransliterationTiming.LINE -> parseLineGroups(ttml, paragraph)
        } ?: return null
        if (timing == TransliterationTiming.LINE && paragraph.end == null) return null
        TransliterationLine(
            id = "L" + (index + 1),
            key = key,
            keyInsertion = paragraph.keyInsertion,
            language = paragraph.language,
            context = contextOf(groups),
            groups = groups,
        )
    }
}

private fun parseWordGroups(
    ttml: String,
    paragraph: ParagraphShape,
): List<TransliterationGroup>? {
    val children = directChildren(ttml, paragraph.element) ?: return null
    val groups = mutableListOf<TransliterationGroup>()
    var main = mutableListOf<TransliterationUnit>()

    fun flushMain() {
        if (main.isNotEmpty()) {
            groups += TransliterationGroup(
                background = false,
                begin = null,
                end = null,
                units = main,
            )
            main = mutableListOf()
        }
    }

    for (child in children) {
        if (!localName(child.tag.name).equals("span", ignoreCase = true)) return null
        when (roleOf(child.tag.attributes)) {
            ROLE_TRANSLATION, ROLE_ROMAN -> Unit
            ROLE_BACKGROUND -> {
                flushMain()
                groups += parseWordBackground(ttml, child) ?: return null
            }
            null -> main += parseWordUnit(ttml, child) ?: return null
            else -> return null
        }
    }
    flushMain()
    return groups
}

private fun parseWordUnit(ttml: String, child: RawChild): TransliterationUnit? {
    val begin = attribute(child.tag.attributes, BEGIN_ATTRIBUTE)?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    val end = attribute(child.tag.attributes, END_ATTRIBUTE)?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    if (!isClock(begin) || !isClock(end) || !timeOrdered(begin, end)) return null
    val source = leafText(ttml, child.element) ?: return null
    if (source.none { !it.isWhitespace() }) return null
    return TransliterationUnit(
        source = source,
        begin = begin,
        end = end,
        after = decodeEntities(child.tail),
        afterRaw = child.tail,
    )
}

private fun parseWordBackground(
    ttml: String,
    child: RawChild,
): TransliterationGroup? {
    if (child.element.selfClosing) return null
    val begin = attribute(child.tag.attributes, BEGIN_ATTRIBUTE)?.trim()?.takeIf(String::isNotEmpty)
    val end = attribute(child.tag.attributes, END_ATTRIBUTE)?.trim()?.takeIf(String::isNotEmpty)
    if (begin != null && (!isClock(begin) || end == null || !timeOrdered(begin, end))) return null
    if (begin == null && end != null) return null
    val nested = directChildren(ttml, child.element) ?: return null
    val units = mutableListOf<TransliterationUnit>()
    for (vocal in nested) {
        if (!localName(vocal.tag.name).equals("span", ignoreCase = true)) return null
        when (roleOf(vocal.tag.attributes)) {
            ROLE_TRANSLATION, ROLE_ROMAN -> Unit
            null -> units += parseWordUnit(ttml, vocal) ?: return null
            else -> return null
        }
    }
    if (units.isEmpty()) return null
    return TransliterationGroup(true, begin, end, units)
}

private fun parseLineGroups(
    ttml: String,
    paragraph: ParagraphShape,
): List<TransliterationGroup>? {
    val paragraphEnd = paragraph.end ?: return null
    val groups = mutableListOf<TransliterationGroup>()
    val main = StringBuilder()
    var index = paragraph.element.contentStart

    fun flushMain() {
        val source = normalizeLineSource(main.toString())
        main.setLength(0)
        if (source.isEmpty()) return
        groups += TransliterationGroup(
            background = false,
            begin = paragraph.begin,
            end = paragraphEnd,
            units = listOf(TransliterationUnit(source, paragraph.begin, paragraphEnd, "", "")),
        )
    }

    while (index < paragraph.element.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= paragraph.element.contentEnd) {
            main.append(decodeEntities(ttml.substring(index, paragraph.element.contentEnd)))
            break
        }
        if (open > index) main.append(decodeEntities(ttml.substring(index, open)))
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val close = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (close < 0) return null
                main.append(ttml.substring(open + CDATA_OPEN.length, close))
            }
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0 || end >= paragraph.element.contentEnd) return null
        val tag = TtmlScanner.tag(ttml, open, end)
        if (tag.closing) return null
        val element = TtmlScanner.elementAt(ttml, tag) ?: return null
        val name = localName(tag.name).lowercase(Locale.ROOT)
        val role = roleOf(tag.attributes)
        when {
            name == "span" && (role == ROLE_TRANSLATION || role == ROLE_ROMAN) -> Unit
            name == "span" && role == ROLE_BACKGROUND -> {
                flushMain()
                if (element.selfClosing) return null
                val begin = attribute(tag.attributes, BEGIN_ATTRIBUTE)?.trim()
                    ?.takeIf(String::isNotEmpty) ?: paragraph.begin
                val endValue = attribute(tag.attributes, END_ATTRIBUTE)?.trim()
                    ?.takeIf(String::isNotEmpty) ?: paragraphEnd
                if (!isClock(begin) || !isClock(endValue) || !timeOrdered(begin, endValue)) {
                    return null
                }
                val source = normalizeLineSource(collectStyledText(ttml, element) ?: return null)
                if (source.isNotEmpty()) {
                    groups += TransliterationGroup(
                        background = true,
                        begin = begin,
                        end = endValue,
                        units = listOf(TransliterationUnit(source, begin, endValue, "", "")),
                    )
                }
            }
            name == "span" && role == null -> {
                main.append(collectStyledText(ttml, element) ?: return null)
            }
            name == "br" -> main.append(' ')
            else -> return null
        }
        index = element.end
    }
    flushMain()
    return groups
}

private fun collectStyledText(ttml: String, parent: TtmlElement): String? {
    if (parent.selfClosing) return ""
    val value = StringBuilder()
    var index = parent.contentStart
    while (index < parent.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= parent.contentEnd) {
            value.append(decodeEntities(ttml.substring(index, parent.contentEnd)))
            break
        }
        if (open > index) value.append(decodeEntities(ttml.substring(index, open)))
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val close = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (close < 0) return null
                value.append(ttml.substring(open + CDATA_OPEN.length, close))
            }
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0 || end >= parent.contentEnd) return null
        val tag = TtmlScanner.tag(ttml, open, end)
        if (tag.closing) return null
        val element = TtmlScanner.elementAt(ttml, tag) ?: return null
        val name = localName(tag.name).lowercase(Locale.ROOT)
        val role = roleOf(tag.attributes)
        when {
            name == "span" && (role == ROLE_TRANSLATION || role == ROLE_ROMAN) -> Unit
            name == "span" && role == ROLE_BACKGROUND -> return null
            name == "span" -> value.append(collectStyledText(ttml, element) ?: return null)
            name == "br" -> value.append(' ')
            else -> return null
        }
        index = element.end
    }
    return value.toString()
}

private fun directChildren(ttml: String, parent: TtmlElement): List<RawChild>? {
    if (parent.selfClosing) return emptyList()
    val children = mutableListOf<MutableRawChild>()
    val pending = StringBuilder()
    var index = parent.contentStart

    fun appendPending() {
        if (pending.isEmpty()) return
        if (children.isNotEmpty()) children.last().tail += pending.toString()
        pending.setLength(0)
    }

    while (index < parent.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= parent.contentEnd) {
            val raw = ttml.substring(index, parent.contentEnd)
            if (!decodeEntities(raw).all(Char::isWhitespace)) return null
            pending.append(raw)
            break
        }
        if (open > index) {
            val raw = ttml.substring(index, open)
            if (!decodeEntities(raw).all(Char::isWhitespace)) return null
            pending.append(raw)
        }
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val close = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (close < 0) return null
                val value = ttml.substring(open + CDATA_OPEN.length, close)
                if (!value.all(Char::isWhitespace)) return null
                pending.append(value)
            }
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0 || end >= parent.contentEnd) return null
        val tag = TtmlScanner.tag(ttml, open, end)
        if (tag.closing) return null
        val element = TtmlScanner.elementAt(ttml, tag) ?: return null
        appendPending()
        children += MutableRawChild(tag, element)
        index = element.end
    }
    appendPending()
    return children.map { RawChild(it.tag, it.element, it.tail) }
}

private fun leafText(ttml: String, element: TtmlElement): String? {
    if (element.selfClosing) return ""
    val value = StringBuilder()
    var index = element.contentStart
    while (index < element.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= element.contentEnd) {
            value.append(decodeEntities(ttml.substring(index, element.contentEnd)))
            break
        }
        if (open > index) value.append(decodeEntities(ttml.substring(index, open)))
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val close = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (close < 0) return null
                value.append(ttml.substring(open + CDATA_OPEN.length, close))
            }
            index = literal
            continue
        }
        return null
    }
    return value.toString()
}

private fun renderTrack(
    document: TransliterationDocument,
    language: String,
    answers: Map<String, AnswerLine>,
): String? = buildString {
    append("<transliteration xml:lang=\"").append(escapeAttribute(language)).append("\">")
    for (line in document.lines) {
        if (line.groups.isEmpty()) continue
        val answer = answers[line.id] ?: return null
        if (answer.groups.size != line.groups.size || answer.englishUnits.size != line.groups.size) {
            return null
        }
        append("<text for=\"").append(escapeAttribute(line.key)).append("\">")
        line.groups.forEachIndexed { groupIndex, group ->
            val readings = answer.groups[groupIndex]
            val english = answer.englishUnits[groupIndex].toHashSet()
            if (readings.size != group.units.size) return null
            if (group.background) {
                append("<span xmlns=\"").append(TTML_NAMESPACE)
                    .append("\" xmlns:ttm=\"").append(TTML_METADATA_NAMESPACE)
                    .append("\" ttm:role=\"").append(ROLE_BACKGROUND).append('"')
                group.begin?.let {
                    append(" begin=\"").append(escapeAttribute(it)).append('"')
                }
                group.end?.let {
                    append(" end=\"").append(escapeAttribute(it)).append('"')
                }
                append('>')
            }
            group.units.forEachIndexed { unitIndex, unit ->
                append("<span xmlns=\"").append(TTML_NAMESPACE)
                    .append("\" begin=\"").append(escapeAttribute(unit.begin))
                    .append("\" end=\"").append(escapeAttribute(unit.end)).append("\">")
                val reading = readings[unitIndex]
                if (unitIndex in english) {
                    append(escapeText(reading)).append("</span>").append(unit.afterRaw)
                } else {
                    val visible = reading.trimEnd()
                    append(escapeText(visible)).append("</span>")
                    append(reading.substring(visible.length))
                }
            }
            if (group.background) append("</span>")
        }
        append("</text>")
    }
    append("</transliteration>")
}

private fun parseEnglishIndexes(values: JSONArray, unitCount: Int): List<Int>? {
    val result = ArrayList<Int>(values.length())
    val seen = HashSet<Int>()
    for (index in 0 until values.length()) {
        val value = values.opt(index) as? Number ?: return null
        val unitIndex = value.toInt()
        if (unitIndex.toDouble() != value.toDouble()) return null
        if (unitIndex !in 0 until unitCount || !seen.add(unitIndex)) return null
        result += unitIndex
    }
    return result
}

private fun validReading(
    unit: TransliterationUnit,
    reading: String,
    english: Boolean,
): Boolean {
    if (english) return reading == unit.source && ZERO_WIDTH_SPACE !in reading
    if (reading.none { !it.isWhitespace() }) return false
    if (reading.any { it == '\r' || it == '\n' || it == '\t' }) return false
    if (ZERO_WIDTH_SPACE in reading) {
        return reading.length == 1 && isStandalonePunctuation(unit.source)
    }
    if (isStandalonePunctuation(unit.source)) return false
    return reading.none(::isNonLatinLetter)
}

private fun isNonLatinLetter(character: Char): Boolean {
    if (!character.isLetter()) return false
    return Character.UnicodeScript.of(character.code) != Character.UnicodeScript.LATIN
}

private fun isStandalonePunctuation(source: String): Boolean {
    var punctuation = false
    for (character in source) {
        when {
            character.isWhitespace() -> Unit
            Character.getType(character) in setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt(),
            ) -> punctuation = true
            else -> return false
        }
    }
    return punctuation
}

private fun normalizeLatinLanguageTag(value: String?): String? {
    val tag = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (LATIN_LANGUAGE.matchEntire(tag) == null) return null
    val parts = tag.split('-').toMutableList()
    parts[0] = parts[0].lowercase(Locale.ROOT)
    parts[1] = "Latn"
    return parts.joinToString("-")
}

private fun contextOf(groups: List<TransliterationGroup>): String =
    WHITESPACE.replace(
        groups.joinToString(" ") { group ->
            group.units.joinToString(" ") { it.source + it.after }
        },
        " ",
    ).trim()

private fun normalizeLineSource(value: String): String =
    WHITESPACE.replace(value.trim(), " ").trim()

private fun timeOrdered(begin: String, end: String): Boolean {
    val start = clockMilliseconds(begin) ?: return false
    val finish = clockMilliseconds(end) ?: return false
    return start <= finish
}

private fun clockMilliseconds(raw: String): Double? {
    val value = raw.trim()
    val suffixed = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").matchEntire(value)
    if (suffixed != null) {
        val number = suffixed.groupValues[1].toDoubleOrNull() ?: return null
        val scale = when (suffixed.groupValues[2]) {
            "ms" -> 1.0
            "s" -> 1000.0
            "m" -> 60_000.0
            else -> 3_600_000.0
        }
        return (number * scale).takeIf { it.isFinite() && it >= 0.0 }
    }
    val parts = value.split(':')
    if (parts.size !in 1..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    var total = seconds * 1000.0
    if (parts.size >= 2) {
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        total += minutes * 60_000.0
    }
    if (parts.size == 3) {
        val hours = parts[0].toLongOrNull() ?: return null
        total += hours * 3_600_000.0
    }
    return total.takeIf { it.isFinite() && it >= 0.0 }
}

private fun hasUsablePronunciation(document: TransliterationDocument): Boolean =
    hasUsablePronunciation(document.ttml)

private fun hasUsablePronunciation(ttml: String): Boolean {
    val head = TtmlScanner.element(ttml, "head", 0)
        ?: return inlineRomanizationExists(ttml)
    val tracks = scanElements(
        ttml,
        "transliteration",
        head.contentStart,
        head.contentEnd,
    ) ?: return true
    if (tracks.any { meaningfulElementText(ttml, it) }) return true
    return inlineRomanizationExists(ttml)
}

private fun inlineRomanizationExists(ttml: String): Boolean {
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
        if (end < 0) return true
        val tag = TtmlScanner.tag(ttml, open, end)
        if (!tag.closing && localName(tag.name).equals("span", ignoreCase = true) &&
            roleOf(tag.attributes) == ROLE_ROMAN
        ) {
            val element = TtmlScanner.elementAt(ttml, tag) ?: return true
            if (meaningfulElementText(ttml, element)) return true
            index = element.end
            continue
        }
        index = end + 1
    }
    return false
}

private fun meaningfulElementText(ttml: String, element: TtmlElement): Boolean {
    if (element.selfClosing) return false
    var index = element.contentStart
    while (index < element.contentEnd) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= element.contentEnd) {
            if (meaningfulText(ttml.substring(index, element.contentEnd))) return true
            break
        }
        if (open > index && meaningfulText(ttml.substring(index, open))) return true
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            if (ttml.startsWith(CDATA_OPEN, open)) {
                val close = ttml.indexOf(CDATA_CLOSE, open + CDATA_OPEN.length)
                if (close < 0) return true
                if (meaningfulText(ttml.substring(open + CDATA_OPEN.length, close))) return true
            }
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0) return true
        index = end + 1
    }
    return false
}

private fun meaningfulText(raw: String): Boolean =
    decodeEntities(raw).any { it != ZERO_WIDTH_SPACE && !it.isWhitespace() }

private fun parseTransliterationTrack(document: TransliterationDocument): ParsedTrack? {
    val head = document.head ?: return null
    val container = uniqueElement(
        document.ttml,
        "transliterations",
        head.contentStart,
        head.contentEnd,
    ).takeIf(ElementLookup::valid)?.element ?: return null
    if (container.selfClosing) return null
    val children = directChildren(document.ttml, container) ?: return null
    val tracks = children.filter {
        localName(it.tag.name).equals("transliteration", ignoreCase = true)
    }
    if (tracks.size != 1 || tracks.size != children.size) return null
    val track = tracks.single()
    if (track.element.selfClosing) return null
    val language = attribute(track.tag.attributes, LANG_ATTRIBUTE)
        ?: attribute(track.tag.attributes, BARE_LANG_ATTRIBUTE)
    if (language.isNullOrBlank()) return null
    val texts = mutableListOf<TrackText>()
    val nested = directChildren(document.ttml, track.element) ?: return null
    for (child in nested) {
        if (!localName(child.tag.name).equals("text", ignoreCase = true)) return null
        val key = attribute(child.tag.attributes, FOR_ATTRIBUTE)
            ?.let(::decodeEntities)?.takeIf(String::isNotBlank) ?: return null
        val spans = parseTrackSpans(document.ttml, child.element) ?: return null
        texts += TrackText(key, spans)
    }
    return ParsedTrack(
        raw = document.ttml.substring(track.element.start, track.element.end),
        texts = texts,
    )
}

private fun parseTrackSpans(ttml: String, text: TtmlElement): List<TrackSpan>? {
    if (text.selfClosing) return emptyList()
    val children = directChildren(ttml, text) ?: return null
    val result = mutableListOf<TrackSpan>()
    for (child in children) {
        if (!localName(child.tag.name).equals("span", ignoreCase = true)) return null
        when (roleOf(child.tag.attributes)) {
            ROLE_BACKGROUND -> {
                if (child.element.selfClosing) return null
                val nested = directChildren(ttml, child.element) ?: return null
                if (nested.isEmpty()) return null
                for (vocal in nested) {
                    if (!localName(vocal.tag.name).equals("span", ignoreCase = true)) return null
                    if (roleOf(vocal.tag.attributes) != null) return null
                    result += trackSpan(ttml, vocal, true) ?: return null
                }
            }
            null -> result += trackSpan(ttml, child, false) ?: return null
            else -> return null
        }
    }
    return result
}

private fun trackSpan(ttml: String, child: RawChild, background: Boolean): TrackSpan? {
    val begin = attribute(child.tag.attributes, BEGIN_ATTRIBUTE)?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    val end = attribute(child.tag.attributes, END_ATTRIBUTE)?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    if (!isClock(begin) || !isClock(end) || !timeOrdered(begin, end)) return null
    val reading = leafText(ttml, child.element) ?: return null
    if (reading.none { !it.isWhitespace() }) return null
    return TrackSpan(begin, end, background)
}

private fun trackMatchesDocument(
    track: ParsedTrack,
    document: TransliterationDocument,
): Boolean {
    val expectedLines = document.lines.filter { line ->
        line.groups.any { it.units.isNotEmpty() }
    }
    if (track.texts.size != expectedLines.size) return false
    if (track.texts.map(TrackText::key) != expectedLines.map(TransliterationLine::key)) {
        return false
    }
    expectedLines.forEachIndexed { index, line ->
        val expected = line.groups.flatMap { group ->
            group.units.map { TrackSpan(it.begin, it.end, group.background) }
        }
        if (track.texts[index].spans != expected) return false
    }
    return true
}

private fun sameLyrics(
    left: TransliterationDocument,
    right: TransliterationDocument,
): Boolean {
    if (left.timing != right.timing || left.lines.size != right.lines.size) return false
    for (index in left.lines.indices) {
        val a = left.lines[index]
        val b = right.lines[index]
        if (a.key != b.key || a.groups.size != b.groups.size) return false
        for (groupIndex in a.groups.indices) {
            val ga = a.groups[groupIndex]
            val gb = b.groups[groupIndex]
            if (ga.background != gb.background || ga.begin != gb.begin || ga.end != gb.end) {
                return false
            }
            if (ga.units.size != gb.units.size) return false
            for (unitIndex in ga.units.indices) {
                if (ga.units[unitIndex] != gb.units[unitIndex]) return false
            }
        }
    }
    return true
}

private fun injectTrack(
    document: TransliterationDocument,
    track: String,
): String? {
    if (hasUsablePronunciation(document)) return null
    val insertion = trackInsertion(document) ?: return null
    val output = buildString {
        append(document.ttml, 0, insertion.start)
        append(insertion.open).append(track).append(insertion.close)
        var copiedThrough = insertion.end
        document.lines.forEach { line ->
            if (line.keyInsertion < 0) return@forEach
            if (line.keyInsertion < copiedThrough) return null
            append(document.ttml, copiedThrough, line.keyInsertion)
            val tagStart = document.ttml.lastIndexOf('<', line.keyInsertion)
            val namespace = attribute(
                document.ttml.substring(tagStart, line.keyInsertion),
                ITUNES_NAMESPACE_ATTRIBUTE,
            )
            if (namespace != null && namespace != ITUNES_NAMESPACE) return null
            if (namespace == null) {
                append(" xmlns:itunes=\"").append(ITUNES_NAMESPACE).append('"')
            }
            append(" itunes:key=\"").append(escapeAttribute(line.key)).append('"')
            copiedThrough = line.keyInsertion
        }
        append(document.ttml, copiedThrough, document.ttml.length)
    }
    return output.takeIf(TtmlInputPolicy::isAcceptable)
}

private fun trackInsertion(
    document: TransliterationDocument,
): TrackInsertion? {
    val root = document.root
    val head = document.head
    if (head == null) {
        return TrackInsertion(
            root.contentStart,
            root.contentStart,
            "<head><metadata>" + itunesOpen,
            itunesClose + "</metadata></head>",
        )
    }
    if (head.selfClosing) {
        return insertAtContentEnd(
            head,
            "head",
            "<metadata>" + itunesOpen,
            itunesClose + "</metadata>",
        )
    }

    val metadataLookup = directUniqueChild(document.ttml, head, "metadata")
    if (!metadataLookup.valid) return null
    val metadata = metadataLookup.element ?: return insertAtContentEnd(
        head,
        "head",
        "<metadata>" + itunesOpen,
        itunesClose + "</metadata>",
    )
    if (metadata.selfClosing) {
        return insertAtContentEnd(metadata, "metadata", itunesOpen, itunesClose)
    }

    val itunesLookup = directUniqueChild(document.ttml, metadata, "iTunesMetadata")
    if (!itunesLookup.valid) return null
    val itunes = itunesLookup.element ?: return insertAtContentEnd(
        metadata,
        "metadata",
        itunesOpen,
        itunesClose,
    )
    if (itunes.selfClosing) {
        return insertAtContentEnd(itunes, "iTunesMetadata", transliterationsOpen, transliterationsClose)
    }

    val containerLookup = directUniqueChild(
        document.ttml,
        itunes,
        "transliterations",
    )
    if (!containerLookup.valid) return null
    val container = containerLookup.element ?: return insertAtContentEnd(
        itunes,
        "iTunesMetadata",
        transliterationsOpen,
        transliterationsClose,
    )

    val children = directChildren(document.ttml, container) ?: return null
    if (children.any {
            !localName(it.tag.name).equals("transliteration", ignoreCase = true)
        }
    ) {
        return null
    }
    val defaultNamespace = attribute(container.attributes, DEFAULT_NAMESPACE_ATTRIBUTE)
    if (defaultNamespace != null && defaultNamespace != ITUNES_NAMESPACE) return null
    val attributes = if (defaultNamespace == null) {
        container.attributes + " xmlns=\"" + ITUNES_NAMESPACE + "\""
    } else {
        container.attributes
    }
    return TrackInsertion(
        container.start,
        container.end,
        "<transliterations" + attributes + ">",
        "</transliterations>",
    )
}

private fun insertAtContentEnd(
    element: TtmlElement,
    name: String,
    open: String,
    close: String,
): TrackInsertion {
    if (!element.selfClosing) {
        return TrackInsertion(element.contentEnd, element.contentEnd, open, close)
    }
    return TrackInsertion(
        element.end - 2,
        element.end,
        ">" + open,
        close + "</" + name + ">",
    )
}

private const val transliterationsClose = "</transliterations>"
private val transliterationsOpen = "<transliterations xmlns=\"" + ITUNES_NAMESPACE + "\">"
private val itunesOpen =
    "<iTunesMetadata xmlns=\"" + ITUNES_NAMESPACE + "\">" + transliterationsOpen
private val itunesClose = transliterationsClose + "</iTunesMetadata>"

private fun directUniqueChild(
    ttml: String,
    parent: TtmlElement,
    name: String,
): ElementLookup {
    val children = directChildren(ttml, parent) ?: return ElementLookup(valid = false)
    val matches = children.filter {
        localName(it.tag.name).equals(name, ignoreCase = true)
    }
    return when (matches.size) {
        0 -> ElementLookup()
        1 -> ElementLookup(matches.single().element)
        else -> ElementLookup(valid = false)
    }
}

private fun uniqueElement(
    ttml: String,
    name: String,
    from: Int,
    to: Int,
): ElementLookup {
    val found = scanElements(ttml, name, from, to) ?: return ElementLookup(valid = false)
    return when (found.size) {
        0 -> ElementLookup()
        1 -> ElementLookup(found.single())
        else -> ElementLookup(valid = false)
    }
}

private fun scanElements(
    ttml: String,
    name: String,
    from: Int,
    to: Int,
): List<TtmlElement>? {
    val result = mutableListOf<TtmlElement>()
    var index = from.coerceAtLeast(0)
    val endLimit = to.coerceAtMost(ttml.length)
    while (index < endLimit) {
        val open = ttml.indexOf('<', index)
        if (open < 0 || open >= endLimit) break
        val literal = TtmlScanner.literalEnd(ttml, open)
        if (literal > 0) {
            index = literal
            continue
        }
        val end = TtmlScanner.tagEnd(ttml, open)
        if (end < 0 || end >= endLimit) return null
        val tag = TtmlScanner.tag(ttml, open, end)
        if (!tag.closing && localName(tag.name).equals(name, ignoreCase = true)) {
            val element = TtmlScanner.elementAt(ttml, tag) ?: return null
            result += element
            index = element.end
            continue
        }
        index = end + 1
    }
    return result
}

private fun localName(name: String): String = name.substringAfterLast(':')
