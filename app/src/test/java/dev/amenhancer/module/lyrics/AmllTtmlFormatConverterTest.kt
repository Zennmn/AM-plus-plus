package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlFormatConverterTest {

    /** The AMLL shape: empty namespace overrides plus inline auxiliary spans. */
    private val amllFormat = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:amll="http://www.example.com/ns/amll">
        <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body dur="00:04.000"><div xmlns="" begin="00:01.000" end="00:04.000">
        <p begin="00:01.000" end="00:02.000" ttm:agent="v1" itunes:key="L1">
        <span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
        <span ttm:role="x-roman">R1</span>
        </p>
        <p begin="00:03.000" end="00:04.000" ttm:agent="v1" itunes:key="L2">
        <span begin="00:03.000" end="00:04.000">cc</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
        <span ttm:role="x-roman">R2</span>
        </p></div></body></tt>
    """.trimIndent()

    @Test
    fun `inline translation spans move into a head translations track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.converted)
        assertTrue(
            result.ttml.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">",
            ),
        )
        assertTrue(
            result.ttml.contains(
                "<translations><translation xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>" +
                    "</translation></translations>",
            ),
        )
    }

    @Test
    fun `inline romanization spans move into a head transliterations track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"ko-Latn\">" +
                    "<text for=\"L1\">R1</text><text for=\"L2\">R2</text>" +
                    "</transliteration></transliterations>",
            ),
        )
    }

    @Test
    fun `the body keeps only lyric spans`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val body = result.ttml.substringAfter("<body")

        assertFalse(body.contains("x-translation"))
        assertFalse(body.contains("x-roman"))
        assertTrue(body.contains("<span begin=\"00:01.000\" end=\"00:01.500\">aa</span>"))
        assertTrue(body.contains("<span begin=\"00:03.000\" end=\"00:04.000\">cc</span>"))
    }

    @Test
    fun `the root is marked word timed with the pinned lyric language`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val root = result.ttml.substringBefore('>')

        assertTrue(root.contains("""itunes:timing="Word""""))
        assertTrue(root.contains("""xml:lang="ko""""))
    }

    @Test
    fun `a document without timed spans is marked line timed`() {
        val lineTimed = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            a line
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(lineTimed)
        val root = result.ttml.substringBefore('>')

        assertTrue(root.contains("""itunes:timing="Line""""))
        assertTrue(root.contains("""xml:lang="ko""""))
    }

    @Test
    fun `a preexisting timing or language declaration is replaced not duplicated`() {
        val declared = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Line" xml:lang="ja">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(declared)
        val root = result.ttml.substringBefore('>')

        assertEquals(1, Regex("""itunes:timing""").findAll(root).count())
        assertEquals(1, Regex("""xml:lang""").findAll(root).count())
        assertTrue(root.contains("""itunes:timing="Word""""))
        assertTrue(root.contains("""xml:lang="ko""""))
    }

    @Test
    fun `word timing inside an auxiliary span is carried over`() {
        val timedAux = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman"><span begin="0.0" end="0.5">ro</span><span begin="0.5" end="1.0">ma</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedAux)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\">" +
                    "<span begin=\"0.0\" end=\"0.5\">ro</span>" +
                    "<span begin=\"0.5\" end=\"1.0\">ma</span>" +
                    "</text>",
            ),
        )
    }

    @Test
    fun `background auxiliary spans become x-bg spans inside the text`() {
        val withBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">BT1</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(withBackground)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\">T1<span ttm:role=\"x-bg\">BT1</span></text>",
            ),
        )
        // The background vocal itself stays in the body, without its translation.
        val body = result.ttml.substringAfter("<body")
        assertTrue(body.contains("<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>"))
        assertFalse(body.contains("BT1"))
    }

    @Test
    fun `a background only auxiliary track yields a text with just the x-bg span`() {
        val backgroundOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L9">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(backgroundOnly)

        assertTrue(
            result.ttml.contains("<text for=\"L9\"><span ttm:role=\"x-bg\">BT</span></text>"),
        )
    }

    @Test
    fun `the first auxiliary span of each kind wins per line`() {
        val several = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">ZH1</span>
            <span ttm:role="x-translation" xml:lang="en">EN1</span>
            <span ttm:role="x-roman">ROMA1</span>
            <span ttm:role="x-roman">ROMA2</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(several)

        assertEquals(1, Regex("<text for=\"L1\">").findAll(result.ttml).count())
        assertTrue(result.ttml.contains("<text for=\"L1\">ZH1</text>"))
        assertTrue(result.ttml.contains("<text for=\"L1\">ROMA1</text>"))
        assertFalse(result.ttml.contains("EN1"))
        assertFalse(result.ttml.contains("ROMA2"))
    }

    @Test
    fun `empty default namespace overrides are dropped`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertFalse(result.ttml.contains("xmlns=\"\""))
        assertTrue(result.ttml.contains("<metadata>"))
        assertTrue(result.ttml.contains("<div begin=\"00:01.000\""))
        assertTrue(result.ttml.contains("xmlns=\"http://www.w3.org/ns/ttml\""))
    }

    @Test
    fun `whitespace between lyric spans is preserved`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.ttml.contains("</span> <span begin=\"00:01.500\""))
    }

    @Test
    fun `a document already declaring itunes metadata is left alone`() {
        val appleFormat = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation xml:lang="zh-CN"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(appleFormat)

        assertFalse(result.converted)
        assertEquals(appleFormat, result.ttml)
    }

    @Test
    fun `lines without a key keep their auxiliary spans rather than losing them`() {
        val noKey = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(noKey)

        assertFalse(result.ttml.contains("<iTunesMetadata"))
        assertTrue(result.ttml.contains("<span ttm:role=\"x-translation\" xml:lang=\"zh-CN\">T1</span>"))
    }

    @Test
    fun `a document with no auxiliary spans still gets the root attributes`() {
        val plain = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(plain)

        assertTrue(result.converted)
        assertFalse(result.ttml.contains("<iTunesMetadata"))
        assertFalse(result.ttml.contains("xmlns=\"\""))
        assertTrue(result.ttml.substringBefore('>').contains("""itunes:timing="Word""""))
        assertTrue(result.ttml.substringBefore('>').contains("""xml:lang="ko""""))
    }

    @Test
    fun `text nodes comments and cdata are copied verbatim`() {
        val tricky = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- xmlns="" in a comment -->
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><amll:meta key="musicName" value="a &gt; b xmlns=&quot;&quot;"/></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">2 &lt; 3</span>
            <span ttm:role="x-translation">&lt;kept&gt;</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(tricky)

        assertTrue(result.ttml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(result.ttml.contains("<!-- xmlns=\"\" in a comment -->"))
        assertTrue(result.ttml.contains("value=\"a &gt; b xmlns=&quot;&quot;\""))
        assertTrue(result.ttml.contains(">2 &lt; 3<"))
        assertTrue(result.ttml.contains("<text for=\"L1\">&lt;kept&gt;</text>"))
    }

    @Test
    fun `the rewrite is idempotent`() {
        val once = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
    }

    @Test
    fun `malformed and empty input never throws`() {
        assertEquals("", AmllTtmlFormatConverter.toAppleFormat("").ttml)
        assertEquals("plain text", AmllTtmlFormatConverter.toAppleFormat("plain text").ttml)
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<tt><body>").converted)
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<unclosed attr=\"").converted)
        assertFalse(
            AmllTtmlFormatConverter.toAppleFormat(
                "<tt><head><metadata/></head><body><p itunes:key=\"L1\">",
            ).converted,
        )
    }

    @Test
    fun `the converted document still satisfies the ttml input policy`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }
}
