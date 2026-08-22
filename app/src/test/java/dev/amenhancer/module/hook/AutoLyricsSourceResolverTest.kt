package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLyricsSourceResolverTest {
    @Test
    fun `resolver keeps AMLL then Lunabeat then user's repository order`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll") {
                    calls += "amll"
                    LINE_TTML
                },
                AutoLyricsSource("lunabeat") {
                    calls += "lunabeat"
                    LINE_TTML
                },
                AutoLyricsSource("my-repository") {
                    calls += "my-repository"
                    WORD_TTML
                },
                AutoLyricsSource("later") {
                    calls += "later"
                    LINE_TTML
                },
            ),
        )

        assertEquals(AutoLyricsCandidate("my-repository", WORD_TTML), resolver.fetch(42L))
        assertEquals(listOf("amll", "lunabeat", "my-repository"), calls)
    }

    @Test
    fun `resolver fails open for invalid ids`() {
        var called = false
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("source") {
                called = true
                WORD_TTML
            }),
        )

        assertNull(resolver.fetch(0L))
        assertEquals(false, called)
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
