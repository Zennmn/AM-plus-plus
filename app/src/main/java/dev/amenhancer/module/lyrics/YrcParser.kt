package dev.amenhancer.module.lyrics

/**
 * Parses NetEase YRC word-level lyric text into the unified [LyricDocument].
 *
 * Real YRC line shape (verified against amll-ttml-db `.yrc` files and the
 * NetEase `/api/song/lyric/v1` response):
 *
 *   `[lineStartMs,lineDurationMs](wordStartMs,wordDurationMs,flag)text...`
 *
 * - The bracketed header carries the absolute line start and line duration
 *   in milliseconds.
 * - Each parenthesised word marker carries the ABSOLUTE word start (ms), the
 *   explicit word duration (ms), and a flag that is ignored here.
 * - A word's text runs from its closing `)` to the next `(` (or line end) and
 *   is kept verbatim, including its own trailing space, as one span.
 *
 * Contract:
 * - Returns `null` when the text carries no usable word timing at all, so the
 *   caller can never fabricate word timing from line-level content alone.
 * - Lines without timed word markers are skipped; malformed lines are
 *   isolated and never fail the document.
 * - Words outside the line range are dropped (never clamped — clamping would
 *   fabricate timing).
 * - Zero-duration markers such as `(0,0,0) ` are NetEase's untimed space
 *   placeholders: their text merges into the next word (leading) or the
 *   previous word (trailing), preserving the original text without inventing
 *   timing for it.
 */
object YrcParser {

    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)\]""")
    private val WORD_MARKER = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")

    fun parse(raw: String): LyricDocument? {
        if (raw.isBlank()) return null
        val lines = raw.lineSequence()
            .map(::parseLine)
            .filterNotNull()
            .toList()
        if (lines.isEmpty()) return null

        val timedLines = mutableListOf<LyricLine>()
        lines.forEach { (startMs, endMs, words) ->
            if (words.isEmpty()) return@forEach
            timedLines += LyricLine(startMs = startMs, endMs = endMs, words = words)
        }
        if (timedLines.isEmpty()) return null
        return LyricDocument(lines = timedLines)
    }

    private fun parseLine(raw: String): ParsedLine? {
        val header = LINE_HEADER.find(raw) ?: return null
        val lineStartMs = header.groupValues[1].toLongOrNull() ?: return null
        val lineDurationMs = header.groupValues[2].toLongOrNull() ?: return null
        if (lineStartMs < 0 || lineDurationMs <= 0) return null
        val lineEndMs = lineStartMs + lineDurationMs

        val body = raw.substring(header.range.last + 1)
        val markers = WORD_MARKER.findAll(body).toList()
        if (markers.isEmpty()) return null

        data class Marker(
            val startMs: Long,
            val durationMs: Long,
            val text: String,
            val timeless: Boolean,
        )

        val parsedMarkers = markers.mapIndexed { index, marker ->
            val startMs = marker.groupValues[1].toLongOrNull() ?: -1L
            val durationMs = marker.groupValues[2].toLongOrNull() ?: -1L
            val textStart = marker.range.last + 1
            val textEnd = markers.getOrNull(index + 1)?.range?.first ?: body.length
            Marker(
                startMs = startMs,
                durationMs = durationMs,
                text = body.substring(textStart, textEnd),
                timeless = durationMs <= 0,
            )
        }

        // Zero-duration markers are untimed placeholders (typically spaces):
        // their text merges as leading text into the next timed word, or as
        // trailing text into the last timed word when nothing follows.
        val timed = mutableListOf<Marker>()
        var pendingLeadingText = ""
        for (marker in parsedMarkers) {
            if (marker.timeless) {
                if (marker.text.isNotEmpty()) pendingLeadingText += marker.text
                continue
            }
            timed += marker.copy(text = pendingLeadingText + marker.text)
            pendingLeadingText = ""
        }
        if (pendingLeadingText.isNotEmpty() && timed.isNotEmpty()) {
            val last = timed[timed.lastIndex]
            timed[timed.lastIndex] = last.copy(text = last.text + pendingLeadingText)
        }

        val words = mutableListOf<LyricWord>()
        timed.forEach { marker ->
            if (marker.text.isEmpty()) return@forEach
            val wordStartMs = marker.startMs
            val wordEndMs = wordStartMs + marker.durationMs
            if (wordStartMs < lineStartMs || wordEndMs > lineEndMs) return@forEach
            words += LyricWord(
                text = marker.text,
                startMs = wordStartMs,
                endMs = wordEndMs,
            )
        }
        if (words.isEmpty()) return null
        return ParsedLine(
            startMs = lineStartMs,
            endMs = lineEndMs,
            words = words,
        )
    }

    private data class ParsedLine(
        val startMs: Long,
        val endMs: Long,
        val words: List<LyricWord>,
    )
}
