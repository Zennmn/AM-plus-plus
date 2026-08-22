package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLyricsPolicyTest {
    @Test
    fun `null native pointer is eligible for automatic lookup`() {
        assertTrue(shouldTryAutoLyrics(null, null))
    }

    @Test
    fun `only an observed non Word pointer is eligible`() {
        val pointer = Any()
        assertTrue(
            shouldTryAutoLyrics(
                pointer,
                TtmlDocumentMetadata(TtmlTimingMode.NON_WORD, language = "zh", hasTranslation = false),
            ),
        )
        assertFalse(
            shouldTryAutoLyrics(
                pointer,
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "zh", hasTranslation = false),
            ),
        )
        assertFalse(shouldTryAutoLyrics(pointer, null))
    }

    @Test
    fun `foreign non Word lyric is eligible even without translation`() {
        assertTrue(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.NON_WORD, language = "de", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `foreign Word lyric without translation is eligible`() {
        assertTrue(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "en-US", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `foreign Word lyric with translation is not eligible`() {
        assertFalse(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "ja", hasTranslation = true),
            ),
        )
    }
}
