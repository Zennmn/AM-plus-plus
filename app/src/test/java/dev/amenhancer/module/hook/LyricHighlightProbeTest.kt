package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightProbeTest {
    @Test
    fun `default gate keeps sink a no-op on the JVM`() {
        val lines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { lines += it },
            enabled = { false },
        )

        // Local JVM Android stubs do not report the explicit device property,
        // so the production-default gate must fail closed without throwing.
        probe.recordNative(1L, setOf(2), 3L)

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `format is deterministic bounded and contains no lyric body`() {
        val ids = (100 downTo 0).toList()
        val line = LyricHighlightProbe.format(
            source = LyricHighlightProbe.Source.NATIVE,
            nativeFirst = 11L,
            lineIds = ids,
            rawLineIds = setOf(3, 2),
            nativeLast = null,
            blurActive = setOf(9, 3),
            blurEffective = setOf(8),
            blurVisible = setOf(10, 2),
            uptimeMillis = 42L,
        )

        assertTrue(line.startsWith("[AMPP-LYRIC-PROBE] source=native"))
        assertTrue(line.contains("native0=11"))
        assertTrue(line.contains("native2=null"))
        assertTrue(line.contains("lineCount=101"))
        assertTrue(line.contains("rawLineIds=[2, 3]"))
        assertTrue(line.contains("effectiveLineIds=[0, 1, 2"))
        assertTrue(line.contains("lineIds=[0, 1, 2"))
        assertFalse(line.contains("the lyric body"))
        assertTrue(line.contains("uptime=42"))
        // 32 is the probe's hard cap, independent of callback vector size.
        assertEquals(32, Regex("\\d+").findAll(line.substringAfter("lineIds=[").substringBefore("]"))
            .count())
    }

    @Test
    fun `sink failures are swallowed`() {
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { error("diagnostic sink failure") },
            enabled = { true },
            uptimeMillis = { 7L },
        )

        probe.recordVm4(12)
        probe.recordVm1(13)
        probe.recordSyntheticInstall()
    }

    @Test
    fun `session probe records opaque identity and process position without dereferencing token`() {
        val lines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { lines += it },
            enabled = { true },
            uptimeMillis = { 8L },
        )
        val token = Any()

        probe.recordSession(token, processPosition = 123L)

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("source=session"))
        assertTrue(lines.single().contains("sessionId=${System.identityHashCode(token)}"))
        assertTrue(lines.single().contains("processPosition=123"))
    }

    @Test
    fun `blur probe records state changes without consuming one event per frame`() {
        val lines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { lines += it },
            enabled = { true },
            uptimeMillis = { 9L },
        )

        probe.recordBlurFrame(emptySet(), emptySet(), emptySet())
        probe.recordBlurFrame(emptySet(), emptySet(), emptySet())
        probe.recordBlurFrame(setOf(7), setOf(7), setOf(6, 7, 8))
        probe.recordBlurFrame(setOf(7), setOf(7), setOf(6, 7, 8))

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("blurActive={ids=[],count=0}"))
        assertTrue(lines[1].contains("blurActive={ids=[7],count=1}"))
    }

    @Test
    fun `session update probe keeps incoming and post-session snapshots distinct`() {
        val lines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { lines += it },
            enabled = { true },
            uptimeMillis = { 10L },
        )

        probe.recordSessionUpdate(
            incomingIds = setOf(18),
            activeIds = setOf(17, 18),
            gap = false,
            opening = false,
        )

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("source=session-update"))
        assertTrue(lines.single().contains("lineIds=[18]"))
        assertTrue(lines.single().contains("blurActive={ids=[17, 18],count=2}"))
        assertTrue(lines.single().contains("sessionGap=false"))
        assertTrue(lines.single().contains("sessionOpening=false"))
    }

    @Test
    fun `word probe records line and word IDs without text`() {
        val lines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { lines += it },
            enabled = { true },
            uptimeMillis = { 11L },
        )

        probe.recordWord(
            source = LyricHighlightProbe.Source.WORD,
            firstNative = 105_033L,
            wordKeys = setOf("18:4", "18:5"),
            lastNative = 922L,
        )

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("source=word"))
        assertTrue(lines.single().contains("native0=105033"))
        assertTrue(lines.single().contains("wordKeys=[18:4, 18:5]"))
        assertTrue(lines.single().contains("wordCount=2"))
    }
}
