package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmllTtmlFormatConverterTest {

    /** The shape AMLL serves: empty default namespace overrides, no root timing. */
    private val amllFormat = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:amll="http://www.example.com/ns/amll">
        <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body dur="00:03.000"><div xmlns="" begin="00:01.000" end="00:03.000">
        <p begin="00:01.000" end="00:03.000" ttm:agent="v1" itunes:key="L1">
        <span begin="00:01.000" end="00:02.000">one</span> <span begin="00:02.000" end="00:03.000">two</span>
        </p></div></body></tt>
    """.trimIndent()

    @Test
    fun `empty default namespace overrides are dropped`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.converted)
        assertFalse(result.ttml.contains("""<metadata xmlns="">"""))
        assertFalse(result.ttml.contains("xmlns=\"\""))
        assertTrue(result.ttml.contains("<metadata>"))
        assertTrue(result.ttml.contains("""<div begin="00:01.000""""))
    }

    @Test
    fun `the ttml namespace on the root survives the rewrite`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.ttml.contains("""xmlns="http://www.w3.org/ns/ttml""""))
        assertTrue(result.ttml.contains("""xmlns:amll="http://www.example.com/ns/amll""""))
    }

    @Test
    fun `a document with timed spans is marked word timed`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertEquals(1, Regex("""itunes:timing="Word"""").findAll(result.ttml).count())
        assertTrue(result.ttml.substringBefore('\n').contains("""itunes:timing="Word""""))
    }

    @Test
    fun `line timed documents are not given a word timing claim`() {
        val lineTimed = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <body><div xmlns=""><p begin="00:01.000" end="00:03.000">a line</p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(lineTimed)

        assertTrue(result.converted)
        assertFalse(result.ttml.contains("itunes:timing"))
        assertFalse(result.ttml.contains("xmlns=\"\""))
    }

    @Test
    fun `the itunes prefix is declared when the root does not carry it`() {
        val noPrefix = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <body><div xmlns=""><p begin="0.0" end="1.0"><span begin="0.0" end="1.0">w</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(noPrefix)

        assertTrue(
            result.ttml.contains("""xmlns:itunes="http://music.apple.com/lyric-ttml-internal""""),
        )
        assertTrue(result.ttml.contains("""itunes:timing="Word""""))
    }

    @Test
    fun `apple formatted input is returned untouched`() {
        val appleFormat = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata/></head>
            <body dur="3.000"><div begin="1.000" end="3.000">
            <p begin="1.000" end="3.000" itunes:key="L1"><span begin="1.000" end="3.000">one</span></p>
            </div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(appleFormat)

        assertFalse(result.converted)
        assertEquals(appleFormat, result.ttml)
    }

    @Test
    fun `an existing timing declaration is never duplicated or overwritten`() {
        val lineDeclared = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line">
            <body><div xmlns=""><p begin="0.0" end="1.0"><span begin="0.0" end="1.0">w</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(lineDeclared)

        assertTrue(result.ttml.contains("""itunes:timing="Line""""))
        assertFalse(result.ttml.contains("""itunes:timing="Word""""))
    }

    @Test
    fun `whitespace between word spans is preserved byte for byte`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(result.ttml.contains("""</span> <span begin="00:02.000""""))
    }

    @Test
    fun `text nodes and attribute values that look like markup are left alone`() {
        val tricky = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <head><metadata xmlns=""><amll:meta key="musicName" value="a &gt; b xmlns=&quot;&quot;"/></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0"><span begin="0.0" end="1.0">2 &lt; 3</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(tricky)

        assertTrue(result.ttml.contains("""value="a &gt; b xmlns=&quot;&quot;""""))
        assertTrue(result.ttml.contains(">2 &lt; 3<"))
        assertFalse(result.ttml.contains("""<div xmlns="""""))
    }

    @Test
    fun `comments cdata and the xml declaration are copied verbatim`() {
        val withLiterals = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- xmlns="" inside a comment -->
            <tt xmlns="http://www.w3.org/ns/ttml">
            <body><div xmlns=""><p begin="0.0" end="1.0"><![CDATA[xmlns="" raw]]></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(withLiterals)

        assertTrue(result.ttml.contains("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(result.ttml.contains("""<!-- xmlns="" inside a comment -->"""))
        assertTrue(result.ttml.contains("""<![CDATA[xmlns="" raw]]>"""))
        assertTrue(result.ttml.contains("<div>"))
    }

    @Test
    fun `a root without lyric spans is left as it is`() {
        val selfClosingRoot = """<tt xmlns="http://www.w3.org/ns/ttml" />"""

        val result = AmllTtmlFormatConverter.toAppleFormat(selfClosingRoot)

        assertFalse(result.converted)
        assertEquals(selfClosingRoot, result.ttml)
    }

    @Test
    fun `non ttml and empty input never throw`() {
        assertEquals("", AmllTtmlFormatConverter.toAppleFormat("").ttml)
        assertEquals("plain text", AmllTtmlFormatConverter.toAppleFormat("plain text").ttml)
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<tt><body>").converted)
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<unclosed attr=\"").converted)
    }

    @Test
    fun `the rewrite is idempotent`() {
        val once = AmllTtmlFormatConverter.toAppleFormat(amllFormat)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
    }

    @Test
    fun `the converted document still satisfies the ttml input policy`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }
}
