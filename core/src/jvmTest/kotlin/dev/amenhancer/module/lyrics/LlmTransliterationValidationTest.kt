package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTransliterationValidationTest {
    private val source = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line" xml:lang="ja"><head/><body><div><p begin="00:00:01.000" end="00:00:02.000">鏡よ</p></div></body></tt>"""

    @Test
    fun `English flag cannot accept non Latin text as a Latin pronunciation`() {
        for (text in listOf("鏡よ", "かがみ", "안녕", "Привет")) {
            val original = source.replace("鏡よ", text)
            val prepared = requireNotNull(LlmTransliterationDocument.prepare(original))
            val answer = """{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["$text"]],"english_units":[[0]]}]}"""
            assertNull(text, prepared.withResponse(answer))
        }
    }

    @Test
    fun `valid Latin pronunciation of the original remains accepted`() {
        val document = requireNotNull(LlmTransliterationDocument.prepare(source))
        assertNotNull(document.withResponse("""{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["kagami yo"]],"english_units":[[]]}]}"""))
    }

    @Test
    fun `timed English units keep their exact text and cannot be rewritten by the model`() {
        val original = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja"><head/><body><div><p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:02.000">鏡</span> <span begin="00:00:02.000" end="00:00:03.000">Hello!</span></p></div></body></tt>"""
        val document = requireNotNull(LlmTransliterationDocument.prepare(original))
        val answer = """{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["kagami ","Hello!"]],"english_units":[[1]]}]}"""
        val result = requireNotNull(document.withResponse(answer))
        assertTrue(result.contains("begin=\"00:00:02.000\" end=\"00:00:03.000\">Hello!</span>"))
        assertNull(document.withResponse(answer.replace("Hello!", "Hello")))
    }

    @Test
    fun `mislabeling a valid timed reading does not discard the track`() {
        val original = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja"><head/><body><div><p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:02.000">鏡</span> <span begin="00:00:02.000" end="00:00:03.000">Hello!</span></p></div></body></tt>"""
        val document = requireNotNull(LlmTransliterationDocument.prepare(original))
        val answer = """{"lang":"ja-Latn","lines":[{"id":"L1","groups":[["kagami  ","Hello!"]],"english_units":[[1]]}]}"""
        val baseline = requireNotNull(document.withResponse(answer))
        assertEquals(baseline, document.withResponse(answer.replace("[[1]]", "[[0,1]]")))
    }
}
