package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlTimingPolicyTest {
    @Test
    fun `accepts Word root timing with single or double quotes and whitespace`() {
        assertEquals(
            TtmlTimingMode.WORD,
            TtmlTimingPolicy.modeOf(
                "<tt xmlns:itunes=\"urn\" itunes:timing = ' Word '><body/></tt>",
            ),
        )
    }

    @Test
    fun `missing and Line timing are non-word`() {
        assertEquals(TtmlTimingMode.NON_WORD, TtmlTimingPolicy.modeOf("<tt><body/></tt>"))
        assertEquals(
            TtmlTimingMode.NON_WORD,
            TtmlTimingPolicy.modeOf("<tt itunes:timing=\"Line\"><body/></tt>"),
        )
    }

    @Test
    fun `foreign Word metadata without translation needs fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="en-US" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">hello</p></div></body>
            </tt>
            """.trimIndent(),
        )

        assertEquals(TtmlTimingMode.WORD, metadata.timingMode)
        assertEquals("en-US", metadata.language)
        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `translation track suppresses foreign Word fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="ja" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">歌</p></div></body>
              <translations><translation xml:lang="zh">歌</translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertTrue(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertFalse(metadata.needsTranslationFallback)
    }

    @Test
    fun `empty translation and transliteration do not count as translation`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="fr" itunes:timing="Word">
              <transliterations><transliteration>bonjour</transliteration></transliterations>
              <translations><translation>  <span> </span> </translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `Chinese or missing language is not classified as foreign`() {
        val chinese = TtmlTimingPolicy.metadataOf(
            "<tt xml:lang=\"zh-Hans\" itunes:timing=\"Word\"><body/></tt>",
        )
        val missing = TtmlTimingPolicy.metadataOf(
            "<tt itunes:timing=\"Word\"><body/></tt>",
        )

        assertFalse(chinese.isForeign)
        assertFalse(chinese.needsTranslationFallback)
        assertNull(missing.language)
        assertFalse(missing.isForeign)
        assertFalse(missing.needsTranslationFallback)
    }

    @Test
    fun `identity registry does not confuse equal pointers`() {
        val first = String(charArrayOf('p'))
        val equal = String(charArrayOf('p'))
        val registry = TtmlTimingObservationRegistry(maxEntries = 2)

        registry.record(first, TtmlTimingMode.WORD)

        assertEquals(TtmlTimingMode.WORD, registry.modeOf(first))
        assertNull(registry.modeOf(equal))
    }

    @Test
    fun `registry evicts oldest observation`() {
        val registry = TtmlTimingObservationRegistry(maxEntries = 1)
        val first = Any()
        val second = Any()

        registry.record(first, TtmlTimingMode.WORD)
        registry.record(second, TtmlTimingMode.NON_WORD)

        assertNull(registry.modeOf(first))
        assertEquals(TtmlTimingMode.NON_WORD, registry.modeOf(second))
    }
}
