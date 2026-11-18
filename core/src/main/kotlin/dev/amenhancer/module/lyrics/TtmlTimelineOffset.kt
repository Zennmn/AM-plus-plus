package dev.amenhancer.module.lyrics

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Shifts explicit Apple TTML timeline values by one integer millisecond offset.
 *
 * In Apple's lyric dialect, hh:mm:ss[.fraction] and mm:ss[.fraction] clocks on
 * nested lines, words and background voices are document-absolute. Those
 * explicit clocks are shifted together, including clocks in auxiliary tracks.
 * AMLL also uses bare seconds as document-absolute timestamps. Outside that
 * dialect, bare seconds and unit offsets are ambiguous under timed ancestors
 * or descendants. Unit offsets remain ambiguous in AMLL too.
 *
 * Negative results are clipped to zero, but an explicit end at/before zero or
 * a collapsed interval makes the entire operation fail. A duration-anchored
 * start that would be clipped is also refused to preserve its interval length.
 * Callers must apply the desired offset to the original document each time.
 * Zero offset returns that original verbatim, without validating it; nonzero
 * offsets return null for unsupported, unsafe or untimed documents.
 */
public object TtmlTimelineOffset {

    public fun apply(ttml: String, offsetMs: Int, amllTiming: Boolean = false): String? {
        if (offsetMs == 0) return ttml
        if (!TtmlInputPolicy.isAcceptable(ttml)) return null
        val lower = ttml.lowercase(Locale.ROOT)
        if (lower.contains("<!doctype") || lower.contains("<!entity")) return null

        val edits = TtmlTimelineScanner(
            text = ttml,
            offsetSeconds = BigDecimal.valueOf(offsetMs.toLong(), 3),
            amllTiming = amllTiming,
        ).scan() ?: return null
        if (edits.isEmpty()) return ttml
        val shifted = applyEdits(ttml, edits) ?: return null
        return shifted.takeIf(TtmlInputPolicy::isAcceptable)
    }

    private fun applyEdits(ttml: String, edits: List<TimeEdit>): String? {
        val ordered = edits.sortedBy(TimeEdit::start)
        val output = StringBuilder(ttml.length + ordered.size * 8)
        var cursor = 0
        for (edit in ordered) {
            if (edit.start < cursor || edit.end < edit.start || edit.end > ttml.length) return null
            output.append(ttml, cursor, edit.start)
            output.append(edit.replacement)
            cursor = edit.end
        }
        output.append(ttml, cursor, ttml.length)
        return output.toString()
    }
}

private class TtmlTimelineScanner(
    private val text: String,
    private val offsetSeconds: BigDecimal,
    private val amllTiming: Boolean,
) {
    private val stack = mutableListOf<Frame>()
    private val edits = mutableListOf<TimeEdit>()
    private var rootSeen = false
    private var rootClosed = false
    private var headCount = 0
    private var bodyCount = 0
    private var timingElements = 0
    private var amllBodyDuration: Pair<XmlAttribute, ParsedTime>? = null
    private var latestEnd = BigDecimal.ZERO

    fun scan(): List<TimeEdit>? {
        var index = 0
        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) {
                if ((!rootSeen || rootClosed) && !isIgnorable(index, text.length)) return null
                index = text.length
                break
            }
            if ((!rootSeen || rootClosed) && !isIgnorable(index, open)) return null
            index = open
            when {
                text.startsWith("<!--", open) || text.startsWith("<![CDATA[", open) ||
                    text.startsWith("<?", open) -> {
                    index = TtmlScanner.literalEnd(text, open, strict = true)
                    if (index < 0) return null
                }
                text.startsWith("<!", open) -> return null
                text.startsWith("</", open) -> {
                    val close = TtmlScanner.tagEnd(text, open)
                    if (close < 0) return null
                    val tag = TtmlScanner.tag(text, open, close)
                    val name = tag.name.takeIf {
                        tag.attributes.isBlank() && validName(it)
                    } ?: return null
                    if (stack.isEmpty()) return null
                    val frame = stack.removeLast()
                    if (frame.name != name) return null
                    if (frame.isRoot) {
                        if (stack.isNotEmpty()) return null
                        rootClosed = true
                    }
                    index = close + 1
                }
                else -> {
                    if (rootClosed) return null
                    val close = TtmlScanner.tagEnd(text, open)
                    if (close < 0) return null
                    val tag = startTag(open, close) ?: return null
                    if (!visit(tag)) return null
                    index = close + 1
                }
            }
        }
        if (!rootSeen || !rootClosed || stack.isNotEmpty() || bodyCount != 1) return null
        if (timingElements == 0) return null
        amllBodyDuration?.let { (attribute, parsed) ->
            // AMLL's body dur is a document end bound, not a per-element interval.
            if (offsetSeconds.signum() > 0 && latestEnd > parsed.seconds) {
                addEdit(attribute, parsed, latestEnd)
            }
        }
        return edits
    }

    private fun visit(tag: StartTag): Boolean {
        val parent = stack.lastOrNull()
        val isRoot = parent == null
        if (isRoot) {
            if (rootSeen || tag.localName != "tt") return false
            val namespace = tag.attributes.firstOrNull { it.name == "xmlns" }
            if (namespace != null && value(namespace) != OFFSET_TTML_NAMESPACE) return false
            rootSeen = true
        } else if (tag.localName == "tt") {
            return false
        }

        if (tag.localName == "head") {
            if (parent?.localName != "tt" || bodyCount > 0) return false
            headCount += 1
            if (headCount > 1) return false
        }

        if (tag.localName == "body") {
            if (parent?.localName != "tt") return false
            bodyCount += 1
            if (bodyCount > 1) return false
        }

        val inBody = parent?.inBody == true || (parent?.localName == "tt" && tag.localName == "body")
        val inHead = parent?.inHead == true || (parent?.localName == "tt" && tag.localName == "head")
        val auxiliaryContainer = tag.localName == "translations" || tag.localName == "transliterations"
        if (auxiliaryContainer && !inHead) return false
        val inAuxiliary = parent?.inAuxiliary == true || (inHead && auxiliaryContainer)

        var duration: BigDecimal? = null
        for (attribute in tag.attributes) {
            val local = localName(attribute.name)
            when {
                local == "timeBase" -> if (value(attribute).trim() != "media") return false
                local in OFFSET_UNSUPPORTED_TIME_DECLARATIONS -> return false
                local == "dur" -> {
                    if (attribute.name != local) return false
                    val parsed = parseTime(value(attribute)) ?: return false
                    if (parsed.seconds.signum() <= 0) return false
                    duration = parsed.seconds
                    if (amllTiming && tag.localName == "body") amllBodyDuration = attribute to parsed
                }
                local == "begin" || local == "end" -> if (attribute.name != local) return false
            }
        }

        val beginAttribute = tag.attributes.firstOrNull { it.name == "begin" }
        val endAttribute = tag.attributes.firstOrNull { it.name == "end" }
        val hasTiming = beginAttribute != null || endAttribute != null
        if (hasTiming && !inBody && !inAuxiliary) return false

        val begin = beginAttribute?.let { parseTime(value(it)) ?: return false }
        val end = endAttribute?.let { parseTime(value(it)) ?: return false }
        val hasRelativeTime = isRelative(begin) || isRelative(end)
        // An untimed wrapper cannot reset the time base of its descendants.
        // Reject both offset children of clocks and clock children of offsets.
        if (hasTiming && parent?.hasExplicitTiming == true &&
            (hasRelativeTime || parent.hasRelativeTiming)
        ) {
            return false
        }

        val clippedBegin = begin != null && begin.seconds.add(offsetSeconds).signum() < 0
        if (duration != null && (clippedBegin || parent?.hasClippedBegin == true)) return false
        if (hasTiming) {
            // Also covers end-only lines/voices whose start is implicit.
            if (end != null) {
                val shiftedEnd = shiftedSeconds(end)
                if (shiftedEnd.signum() == 0 ||
                    (begin != null && (begin.seconds >= end.seconds || shiftedSeconds(begin) >= shiftedEnd))
                ) return false
                if (amllTiming) latestEnd = latestEnd.max(shiftedEnd)
                endAttribute?.let { addEdit(it, end, shiftedEnd) }
            }
            beginAttribute?.let { addEdit(it, requireNotNull(begin)) }
            timingElements += 1
        }

        if (!tag.selfClosing) {
            stack += Frame(
                name = tag.name,
                localName = tag.localName,
                inBody = inBody,
                inHead = inHead,
                inAuxiliary = inAuxiliary,
                isRoot = isRoot,
                hasExplicitTiming = hasTiming || parent?.hasExplicitTiming == true,
                hasRelativeTiming = hasRelativeTime || parent?.hasRelativeTiming == true,
                hasClippedBegin = clippedBegin || parent?.hasClippedBegin == true,
            )
        }
        return true
    }

    private fun isRelative(time: ParsedTime?): Boolean =
        time?.style == TimeStyle.OFFSET || (!amllTiming && time?.style == TimeStyle.SECONDS)

    private fun addEdit(attribute: XmlAttribute, parsed: ParsedTime, seconds: BigDecimal = shiftedSeconds(parsed)) {
        val raw = value(attribute)
        val leading = raw.takeWhile { it.isWhitespace() }
        val trailing = raw.drop(leading.length).takeLastWhile { it.isWhitespace() }
        val replacement = leading + formatTime(seconds, parsed) + trailing
        if (replacement != raw) {
            edits += TimeEdit(attribute.valueStart, attribute.valueEnd, replacement)
        }
    }

    private fun shiftedSeconds(parsed: ParsedTime): BigDecimal =
        parsed.seconds.add(offsetSeconds).max(BigDecimal.ZERO)

    private fun value(attribute: XmlAttribute): String =
        text.substring(attribute.valueStart, attribute.valueEnd)

    private fun isIgnorable(from: Int, to: Int): Boolean {
        for (index in from until to) {
            val character = text[index]
            if (!character.isWhitespace() && !(index == 0 && character == '\uFEFF')) return false
        }
        return true
    }

    private fun startTag(open: Int, close: Int): StartTag? {
        var index = open + 1
        if (index >= close) return null
        val nameStart = index
        while (index < close && !text[index].isWhitespace() &&
            text[index] != '/' && text[index] != '>'
        ) {
            index += 1
        }
        val name = text.substring(nameStart, index)
        if (!validName(name)) return null

        val attributes = mutableListOf<XmlAttribute>()
        var selfClosing = false
        while (true) {
            while (index < close && text[index].isWhitespace()) index += 1
            if (index >= close) break
            if (text[index] == '/') {
                var rest = index + 1
                while (rest < close && text[rest].isWhitespace()) rest += 1
                if (rest != close) return null
                selfClosing = true
                break
            }

            val attributeStart = index
            while (index < close && !text[index].isWhitespace() &&
                text[index] != '=' && text[index] != '/' && text[index] != '>'
            ) {
                index += 1
            }
            val attributeName = text.substring(attributeStart, index)
            if (!validName(attributeName)) return null
            while (index < close && text[index].isWhitespace()) index += 1
            if (index >= close || text[index] != '=') return null
            index += 1
            while (index < close && text[index].isWhitespace()) index += 1
            if (index >= close) return null
            val quote = text[index]
            if (quote != '"' && quote != '\'') return null
            index += 1
            val valueStart = index
            while (index < close && text[index] != quote) {
                if (text[index] == '<') return null
                index += 1
            }
            if (index >= close) return null
            val valueEnd = index
            index += 1
            attributes += XmlAttribute(attributeName, valueStart, valueEnd)
        }
        if (attributes.map(XmlAttribute::name).distinct().size != attributes.size) return null
        return StartTag(name, localName(name), attributes, selfClosing)
    }
}

private fun parseTime(raw: String): ParsedTime? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > OFFSET_MAX_TIME_LITERAL_LENGTH) return null
    if (value.any { it.isWhitespace() }) return null

    val offset = OFFSET_TIME_PATTERN.matchEntire(value)
    if (offset != null) {
        val number = parseDecimal(offset.groupValues[1]) ?: return null
        val unit = offset.groupValues[2]
        val multiplier = when (unit) {
            "ms" -> OFFSET_MILLISECOND
            "s" -> BigDecimal.ONE
            "m" -> OFFSET_SIXTY
            "h" -> OFFSET_HOUR
            else -> return null
        }
        return ParsedTime(
            seconds = number.multiply(multiplier),
            style = TimeStyle.OFFSET,
            unit = unit,
            widths = emptyList(),
            fractionScale = fractionDigits(offset.groupValues[1]),
        )
    }

    if (':' in value) {
        val parts = value.split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() }) return null
        val secondsPart = parts.last()
        val seconds = parseDecimal(secondsPart) ?: return null
        if (seconds >= OFFSET_SIXTY) return null

        val integerParts = parts.dropLast(1)
        if (integerParts.any { !OFFSET_DIGITS.matches(it) }) return null
        val hours: BigDecimal?
        val minutes: BigDecimal
        if (parts.size == 3) {
            val minuteValue = BigDecimal(integerParts[1])
            if (minuteValue >= OFFSET_SIXTY) return null
            hours = BigDecimal(integerParts[0])
            minutes = minuteValue
        } else {
            hours = null
            minutes = BigDecimal(integerParts[0])
        }
        val total = (hours?.multiply(OFFSET_HOUR) ?: BigDecimal.ZERO)
            .add(minutes.multiply(OFFSET_SIXTY))
            .add(seconds)
        return ParsedTime(
            seconds = total,
            style = if (parts.size == 3) TimeStyle.CLOCK_3 else TimeStyle.CLOCK_2,
            unit = null,
            widths = parts.map { it.substringBefore('.').length },
            fractionScale = fractionDigits(secondsPart),
        )
    }

    val seconds = parseDecimal(value) ?: return null
    return ParsedTime(
        seconds = seconds,
        style = TimeStyle.SECONDS,
        unit = null,
        widths = emptyList(),
        fractionScale = fractionDigits(value),
    )
}

private fun formatTime(seconds: BigDecimal, parsed: ParsedTime): String = when (parsed.style) {
    TimeStyle.CLOCK_3 -> formatClock(seconds, parsed, 3)
    TimeStyle.CLOCK_2 -> formatClock(seconds, parsed, 2)
    TimeStyle.OFFSET -> formatOffset(seconds, parsed)
    TimeStyle.SECONDS -> formatDecimal(seconds, parsed.fractionScale)
}

private fun formatClock(seconds: BigDecimal, parsed: ParsedTime, parts: Int): String {
    val total = if (seconds.signum() < 0) BigDecimal.ZERO else seconds
    val hours = if (parts == 3) total.divideToIntegralValue(OFFSET_HOUR).toBigIntegerExact() else null
    val afterHours = if (hours == null) {
        total
    } else {
        total.subtract(BigDecimal(hours).multiply(OFFSET_HOUR))
    }
    val minutes = afterHours.divideToIntegralValue(OFFSET_SIXTY).toBigIntegerExact()
    val remainder = afterHours.subtract(BigDecimal(minutes).multiply(OFFSET_SIXTY))
    val secondText = formatDecimal(remainder, parsed.fractionScale)
    val dot = secondText.indexOf('.')
    val secondInteger = if (dot < 0) secondText else secondText.substring(0, dot)
    val secondFraction = if (dot < 0) "" else secondText.substring(dot)
    val paddedSeconds = secondInteger.padStart(parsed.widths.last(), '0') + secondFraction

    return if (parts == 3) {
        val hourText = (hours?.toString() ?: "0").padStart(parsed.widths[0], '0')
        val minuteText = minutes.toString().padStart(parsed.widths[1], '0')
        "$hourText:$minuteText:$paddedSeconds"
    } else {
        val minuteText = minutes.toString().padStart(parsed.widths[0], '0')
        "$minuteText:$paddedSeconds"
    }
}

private fun formatOffset(seconds: BigDecimal, parsed: ParsedTime): String {
    val unit = parsed.unit ?: return formatDecimal(seconds, parsed.fractionScale) + "s"
    val exact = when (unit) {
        "ms" -> seconds.movePointRight(3)
        "s" -> seconds
        "m" -> try {
            seconds.divide(OFFSET_SIXTY)
        } catch (_: ArithmeticException) {
            null
        }
        "h" -> try {
            seconds.divide(OFFSET_HOUR)
        } catch (_: ArithmeticException) {
            null
        }
        else -> null
    }
    return if (exact != null) {
        formatDecimal(exact, parsed.fractionScale) + unit
    } else {
        // A media offset in minutes or hours cannot represent every integer
        // millisecond as a finite decimal. Milliseconds keep the shift exact.
        formatDecimal(seconds.movePointRight(3), 0) + "ms"
    }
}

private fun formatDecimal(value: BigDecimal, minimumScale: Int): String {
    val naturalScale = value.stripTrailingZeros().scale().coerceAtLeast(0)
    val scale = maxOf(minimumScale, naturalScale)
    return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString()
}

private fun parseDecimal(value: String): BigDecimal? {
    if (!OFFSET_DECIMAL.matches(value)) return null
    return try {
        BigDecimal(value)
    } catch (_: NumberFormatException) {
        null
    }
}

private fun fractionDigits(value: String): Int = value.substringAfter('.', "").length

private fun localName(name: String): String = name.substringAfterLast(':')

private fun validName(name: String): Boolean =
    name.isNotEmpty() && name.none {
        it.isWhitespace() || it == '<' || it == '>' || it == '"' ||
            it == '\'' || it == '=' || it == '/'
    }

private data class Frame(
    val name: String,
    val localName: String,
    val inBody: Boolean,
    val inHead: Boolean,
    val inAuxiliary: Boolean,
    val isRoot: Boolean,
    val hasExplicitTiming: Boolean,
    val hasRelativeTiming: Boolean,
    val hasClippedBegin: Boolean,
)

private data class StartTag(
    val name: String,
    val localName: String,
    val attributes: List<XmlAttribute>,
    val selfClosing: Boolean,
)

private data class XmlAttribute(
    val name: String,
    val valueStart: Int,
    val valueEnd: Int,
)

private data class TimeEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
)

private data class ParsedTime(
    val seconds: BigDecimal,
    val style: TimeStyle,
    val unit: String?,
    val widths: List<Int>,
    val fractionScale: Int,
)

private enum class TimeStyle {
    CLOCK_3,
    CLOCK_2,
    OFFSET,
    SECONDS,
}

private const val OFFSET_MAX_TIME_LITERAL_LENGTH = 128
private const val OFFSET_TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
private val OFFSET_DECIMAL = Regex("[0-9]+(?:\\.[0-9]+)?")
private val OFFSET_DIGITS = Regex("[0-9]+")
private val OFFSET_TIME_PATTERN = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)")
private val OFFSET_MILLISECOND = BigDecimal("0.001")
private val OFFSET_SIXTY = BigDecimal.valueOf(60)
private val OFFSET_HOUR = BigDecimal.valueOf(3600)
private val OFFSET_UNSUPPORTED_TIME_DECLARATIONS = setOf(
    "frameRate",
    "subFrameRate",
    "tickRate",
    "frameRateMultiplier",
    "timeContainer",
    "repeatCount",
    "repeatDur",
    "speed",
    "clockMode",
    "markerMode",
    "dropMode",
)
