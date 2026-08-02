package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTtmlSerializerTest {

    private val document = LyricDocument(
        lines = listOf(
            LyricLine(
                startMs = 20_519,
                endMs = 23_530,
                words = listOf(
                    LyricWord("你", 20_519, 20_942),
                    LyricWord("好", 20_942, 23_530),
                ),
            ),
            LyricLine(
                startMs = 24_449,
                endMs = 27_940,
                words = listOf(
                    LyricWord("世", 24_449, 24_634),
                    LyricWord("界", 24_634, 27_940),
                ),
            ),
        ),
    )

    @Test
    fun `serializes lines and words with millisecond precision times`() {
        val ttml = WordTtmlSerializer.serialize(document)

        assertTrue(ttml!!.contains("<p begin=\"20.519\" end=\"23.530\" itunes:key=\"L1\" ttm:agent=\"v1\">"))
        assertTrue(ttml.contains("<span begin=\"20.519\" end=\"20.942\">你</span>"))
        assertTrue(ttml.contains("<span begin=\"24.449\" end=\"24.634\">世</span>"))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.startsWith("<tt xmlns=\"http://www.w3.org/ns/ttml\""))
        assertTrue(ttml.endsWith("</div></body></tt>"))
    }

    @Test
    fun `escapes every xml special character in word text and metadata`() {
        val tricky = LyricDocument(
            lines = listOf(
                LyricLine(
                    startMs = 0,
                    endMs = 1000,
                    words = listOf(
                        LyricWord("a<b>&\"c'", 0, 1000),
                    ),
                ),
            ),
        )

        val ttml = WordTtmlSerializer.serialize(
            tricky,
            title = "T<itle> & \"quoted\" 'one'",
            artist = "A&B",
        )

        assertTrue(ttml!!.contains("<span begin=\"0.000\" end=\"1.000\">a&lt;b&gt;&amp;&quot;c&apos;</span>"))
        assertTrue(ttml.contains("value=\"T&lt;itle&gt; &amp; &quot;quoted&quot; &apos;one&apos;\""))
        assertTrue(ttml.contains("value=\"A&amp;B\""))
        assertTrue(!ttml.contains("<\"") && !ttml.contains("&\"c'<"))
    }

    @Test
    fun `whitespace words are serialized as their own spans`() {
        val spaced = LyricDocument(
            lines = listOf(
                LyricLine(
                    startMs = 0,
                    endMs = 1000,
                    words = listOf(
                        LyricWord(" ", 0, 100),
                        LyricWord("A", 100, 1000),
                    ),
                ),
            ),
        )

        val ttml = WordTtmlSerializer.serialize(spaced)

        assertTrue(ttml!!.contains("<span begin=\"0.000\" end=\"0.100\"> </span>"))
        assertTrue(ttml.contains("<span begin=\"0.100\" end=\"1.000\">A</span>"))
    }

    @Test
    fun `an empty document serializes to null`() {
        assertNull(WordTtmlSerializer.serialize(LyricDocument(lines = emptyList())))
    }

    @Test
    fun `seconds formatting keeps three fraction digits`() {
        assertEquals("0.000", WordTtmlSerializer.seconds(0))
        assertEquals("0.001", WordTtmlSerializer.seconds(1))
        assertEquals("20.519", WordTtmlSerializer.seconds(20_519))
        assertEquals("244.497", WordTtmlSerializer.seconds(244_497))
    }

    @Test
    fun `escape keeps plain text and every special form`() {
        assertEquals("plain", WordTtmlSerializer.escape("plain"))
        assertEquals("&amp;&lt;&gt;&quot;&apos;", WordTtmlSerializer.escape("&<>\"'"))
    }
}
