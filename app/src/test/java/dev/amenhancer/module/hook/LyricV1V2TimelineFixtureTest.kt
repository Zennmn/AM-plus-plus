package dev.amenhancer.module.hook

import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small, self-contained timeline probe for the two-agent shape seen in
 * Apple Music Word-TTML.  It intentionally does not load a lyric attachment
 * (or any production parser): the fixture and the checks are the regression
 * evidence, so this test remains a pure JVM test.
 */
class LyricV1V2TimelineFixtureTest {

    @Test
    fun `p and span boundaries are explicit and the final span closes each line`() {
        val lines = parse(FIXTURE)

        assertEquals((1..17).map { "L$it" }, lines.map(TimelineLine::id))
        assertTrue(lines.all { it.beginMs < it.endMs })
        assertTrue(lines.all { it.spans.isNotEmpty() })
        lines.forEach { line ->
            assertEquals(line.beginMs, line.spans.first().beginMs)
            assertEquals(line.endMs, line.spans.last().endMs)
            assertTrue(line.spans.all { span -> span.beginMs < span.endMs })
            assertTrue(line.spans.zipWithNext().all { (left, right) -> left.endMs == right.beginMs })
            assertTrue(line.spans.all { span -> span.beginMs >= line.beginMs && span.endMs <= line.endMs })
        }
    }

    @Test
    fun `v1 and v2 windows can both be active at the same instant`() {
        val lines = parse(FIXTURE)

        val activeAtOneSecondTwoHundred = lines.filter { it.activeAt(1_200L) }

        assertEquals(setOf("v1", "v2"), activeAtOneSecondTwoHundred.map { it.agent }.toSet())
        assertEquals(setOf("L1", "L2"), activeAtOneSecondTwoHundred.map { it.id }.toSet())
    }

    @Test
    fun `continuous ids and duplicate v2 text retain both line identities`() {
        val lines = parse(FIXTURE)
        val duplicateV2 = lines.filter { it.agent == "v2" && it.text == "same line" }

        assertEquals(listOf("L2", "L3"), duplicateV2.map { it.id })
        assertEquals(2, duplicateV2.map(TimelineLine::id).toSet().size)
        assertEquals((1..17).map { "L$it" }, lines.map(TimelineLine::id))
    }

    @Test
    fun `same-agent overlap is surfaced as an abnormal diagnostic`() {
        val lines = parse(FIXTURE)
        val diagnostics = audit(lines)

        assertFalse(diagnostics.sameAgentOverlaps.isEmpty())
        val overlap = diagnostics.sameAgentOverlaps.singleOrNull { it.agent == "v2" }
        assertNotNull(overlap)
        assertEquals(setOf("L2", "L3"), overlap!!.lineIds)
        assertEquals("same-agent-overlap", overlap.code)
        assertTrue(overlap.isAbnormal)
    }

    private fun parse(ttml: String): List<TimelineLine> = P_ELEMENT.findAll(ttml).map { match ->
        val attributes = attributes(match.groupValues[1])
        val spans = SPAN_ELEMENT.findAll(match.groupValues[2]).map { spanMatch ->
            val spanAttributes = attributes(spanMatch.groupValues[1])
            TimelineSpan(
                beginMs = parseTime(requireAttribute(spanAttributes, "begin")),
                endMs = parseTime(requireAttribute(spanAttributes, "end")),
                text = spanMatch.groupValues[2].replace(WHITESPACE, " ").trim(),
            )
        }.toList()
        TimelineLine(
            id = requireAttribute(attributes, "itunes:key"),
            agent = requireAttribute(attributes, "ttm:agent"),
            beginMs = parseTime(requireAttribute(attributes, "begin")),
            endMs = parseTime(requireAttribute(attributes, "end")),
            spans = spans,
        )
    }.toList()

    private fun audit(lines: List<TimelineLine>): TimelineAudit {
        val overlaps = lines.groupBy(TimelineLine::agent).values.flatMap { sameAgent ->
            val ordered = sameAgent.sortedBy(TimelineLine::beginMs)
            ordered.flatMapIndexed { index, first ->
                ordered.drop(index + 1).mapNotNull { second ->
                    if (first.endMs > second.beginMs && second.endMs > first.beginMs) {
                        SameAgentOverlap(
                            agent = first.agent,
                            lineIds = setOf(first.id, second.id),
                            code = "same-agent-overlap",
                            isAbnormal = true,
                        )
                    } else {
                        null
                    }
                }
            }
        }
        return TimelineAudit(sameAgentOverlaps = overlaps)
    }

    private fun attributes(raw: String): Map<String, String> = ATTRIBUTE.findAll(raw).associate {
        it.groupValues[1] to it.groupValues[2]
    }

    private fun requireAttribute(attributes: Map<String, String>, name: String): String =
        requireNotNull(attributes[name]) { "missing $name" }

    private fun parseTime(raw: String): Long {
        val value = raw.trim()
        if (value.endsWith("s")) return decimalSeconds(value.dropLast(1))
        val parts = value.split(':')
        return when (parts.size) {
            1 -> decimalSeconds(parts[0])
            2 -> parts[0].toLong() * 60_000L + decimalSeconds(parts[1])
            3 -> parts[0].toLong() * 3_600_000L +
                parts[1].toLong() * 60_000L + decimalSeconds(parts[2])
            else -> error("unsupported TTML time: $raw")
        }
    }

    private fun decimalSeconds(raw: String): Long = (raw.toDouble() * 1_000.0).roundToLong()

    private data class TimelineSpan(
        val beginMs: Long,
        val endMs: Long,
        val text: String,
    )

    private data class TimelineLine(
        val id: String,
        val agent: String,
        val beginMs: Long,
        val endMs: Long,
        val spans: List<TimelineSpan>,
    ) {
        val text: String get() = spans.joinToString(" ", transform = TimelineSpan::text)

        fun activeAt(timeMs: Long): Boolean = timeMs >= beginMs && timeMs < endMs
    }

    private data class SameAgentOverlap(
        val agent: String,
        val lineIds: Set<String>,
        val code: String,
        val isAbnormal: Boolean,
    )

    private data class TimelineAudit(
        val sameAgentOverlaps: List<SameAgentOverlap>,
    )

    private companion object {
        private val P_ELEMENT = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val SPAN_ELEMENT = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val ATTRIBUTE = Regex("""([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*\"([^\"]*)\"""")
        private val WHITESPACE = Regex("\\s+")

        /** Synthetic text only; no lyrics or external attachment is needed. */
        private val FIXTURE = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <head>
                <metadata>
                  <ttm:agent type="person" xml:id="v1"/>
                  <ttm:agent type="person" xml:id="v2"/>
                </metadata>
              </head>
              <body>
                <div itunes:song-part="Verse">
                  <p begin="0.000" end="1.500" itunes:key="L1" ttm:agent="v1">
                    <span begin="0.000" end="0.750">v1-1-a</span>
                    <span begin="0.750" end="1.500">v1-1-b</span>
                  </p>
                  <p begin="1.000" end="2.400" itunes:key="L2" ttm:agent="v2">
                    <span begin="1.000" end="1.700">same</span>
                    <span begin="1.700" end="2.400">line</span>
                  </p>
                  <p begin="2.300" end="3.600" itunes:key="L3" ttm:agent="v2">
                    <span begin="2.300" end="2.900">same</span>
                    <span begin="2.900" end="3.600">line</span>
                  </p>
                  <p begin="1.500" end="3.000" itunes:key="L4" ttm:agent="v1">
                    <span begin="1.500" end="2.250">v1-4-a</span>
                    <span begin="2.250" end="3.000">v1-4-b</span>
                  </p>
                  <p begin="3.000" end="4.500" itunes:key="L5" ttm:agent="v1">
                    <span begin="3.000" end="3.750">v1-5-a</span>
                    <span begin="3.750" end="4.500">v1-5-b</span>
                  </p>
                  <p begin="3.600" end="4.800" itunes:key="L6" ttm:agent="v2">
                    <span begin="3.600" end="4.200">v2-6-a</span>
                    <span begin="4.200" end="4.800">v2-6-b</span>
                  </p>
                  <p begin="4.500" end="5.700" itunes:key="L7" ttm:agent="v1">
                    <span begin="4.500" end="5.100">v1-7-a</span>
                    <span begin="5.100" end="5.700">v1-7-b</span>
                  </p>
                  <p begin="4.800" end="6.000" itunes:key="L8" ttm:agent="v2">
                    <span begin="4.800" end="5.400">v2-8-a</span>
                    <span begin="5.400" end="6.000">v2-8-b</span>
                  </p>
                  <p begin="5.700" end="6.900" itunes:key="L9" ttm:agent="v1">
                    <span begin="5.700" end="6.300">v1-9-a</span>
                    <span begin="6.300" end="6.900">v1-9-b</span>
                  </p>
                  <p begin="6.900" end="8.100" itunes:key="L10" ttm:agent="v2">
                    <span begin="6.900" end="7.500">v2-10-a</span>
                    <span begin="7.500" end="8.100">v2-10-b</span>
                  </p>
                  <p begin="6.900" end="8.100" itunes:key="L11" ttm:agent="v1">
                    <span begin="6.900" end="7.500">v1-11-a</span>
                    <span begin="7.500" end="8.100">v1-11-b</span>
                  </p>
                  <p begin="8.100" end="9.300" itunes:key="L12" ttm:agent="v2">
                    <span begin="8.100" end="8.700">v2-12-a</span>
                    <span begin="8.700" end="9.300">v2-12-b</span>
                  </p>
                  <p begin="8.100" end="9.500" itunes:key="L13" ttm:agent="v1">
                    <span begin="8.100" end="8.800">v1-13-a</span>
                    <span begin="8.800" end="9.500">v1-13-b</span>
                  </p>
                  <p begin="9.300" end="10.400" itunes:key="L14" ttm:agent="v2">
                    <span begin="9.300" end="9.850">v2-14-a</span>
                    <span begin="9.850" end="10.400">v2-14-b</span>
                  </p>
                  <p begin="9.500" end="11.000" itunes:key="L15" ttm:agent="v1">
                    <span begin="9.500" end="10.250">v1-15-a</span>
                    <span begin="10.250" end="11.000">v1-15-b</span>
                  </p>
                  <p begin="10.400" end="11.600" itunes:key="L16" ttm:agent="v2">
                    <span begin="10.400" end="11.000">v2-16-a</span>
                    <span begin="11.000" end="11.600">v2-16-b</span>
                  </p>
                  <p begin="11.000" end="12.500" itunes:key="L17" ttm:agent="v1">
                    <span begin="11.000" end="11.750">v1-17-a</span>
                    <span begin="11.750" end="12.500">v1-17-b</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
    }
}
