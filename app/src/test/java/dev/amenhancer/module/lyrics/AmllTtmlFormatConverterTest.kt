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
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
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
                "<text for=\"L1\">T1<span ttm:role=\"x-bg\">(BT1)</span></text>",
            ),
        )
        // The background vocal itself stays in the body, without its translation.
        val body = result.ttml.substringAfter("<body")
        assertTrue(body.contains("<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>"))
        assertFalse(body.contains("BT1"))
    }

    @Test
    fun `a background translation already parenthesized is not wrapped twice`() {
        val parenthesized = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">（已加括号）</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(parenthesized)

        assertTrue(
            result.ttml.contains("<span ttm:role=\"x-bg\">（已加括号）</span></text>"),
        )
    }

    @Test
    fun `a background vocal loses the timing apple reads from its syllables`() {
        val timedBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(
            body.contains("<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>"),
        )
    }

    @Test
    fun `a background vocal starting before the lyrics moves to the front of the line`() {
        val leadingBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="1.0" end="3.0" itunes:key="L1">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(leadingBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(
            body.contains(
                "itunes:key=\"L1\">" +
                    "<span ttm:role=\"x-bg\"><span begin=\"1.0\" end=\"2.0\">(bb)</span></span>",
            ),
        )
        assertTrue(body.indexOf("x-bg") < body.indexOf(">aa<"))
        assertEquals(1, Regex("""x-bg""").findAll(body).count())
    }

    @Test
    fun `a line translated only in its background opens with the placeholder`() {
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

        // The space stands in for the main translation the line never had, so
        // Apple does not read the background one as belonging to the line.
        assertTrue(
            result.ttml.contains("<text for=\"L9\"> <span ttm:role=\"x-bg\">(BT)</span></text>"),
        )
    }

    @Test
    fun `a line the source left untranslated still holds its place in the track`() {
        val gapped = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L3">
            <span begin="2.0" end="3.0">cc</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T3</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(gapped)

        // Without the L2 placeholder Android Apple Music reads T3 onto L2 and
        // gives up on the lyrics entirely.
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text>" +
                    "<text for=\"L2\"> </text>" +
                    "<text for=\"L3\">T3</text>" +
                    "</translation></translations>",
            ),
        )
    }

    @Test
    fun `an empty translation span counts as no translation`() {
        val blank = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN"></span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(blank)

        assertTrue(
            result.ttml.contains("<text for=\"L1\"> </text><text for=\"L2\">T2</text>"),
        )
        // The emptied span leaves the body all the same.
        assertFalse(result.ttml.substringAfter("<body").contains("x-translation"))
    }

    @Test
    fun `a lyric with no translation at all gets no translations track`() {
        val romanOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(romanOnly)

        assertFalse(result.ttml.contains("<translations>"))
        // The kind that was spoken for still lists every line.
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"ko-Latn\">" +
                    "<text for=\"L1\">R1</text><text for=\"L2\"> </text>" +
                    "</transliteration></transliterations>",
            ),
        )
    }

    @Test
    fun `a background translation alone still fills the track for every line`() {
        val backgroundOnlyLine = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="2.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L2">
            <span begin="2.0" end="3.0">cc</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(backgroundOnlyLine)

        assertTrue(
            result.ttml.contains(
                "<text for=\"L1\"> <span ttm:role=\"x-bg\">(BT)</span></text>" +
                    "<text for=\"L2\"> </text>",
            ),
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

        // Matching each whole track proves exactly one text survived per kind;
        // `<text for="L1">` itself appears twice, once per track.
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">ZH1</text>" +
                    "</translation></translations>",
            ),
        )
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration xml:lang=\"ko-Latn\">" +
                    "<text for=\"L1\">ROMA1</text>" +
                    "</transliteration></transliterations>",
            ),
        )
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
    fun `a document already declaring auxiliary tracks is left alone`() {
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

    /** An Apple transliteration in the head, translations still inline as AMLL. */
    private val mixedFormat = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text><text for="L2"><span begin="1.0" end="2.0">ma</span></text></transliteration></transliterations>
        </iTunesMetadata></metadata></head>
        <body><div>
        <p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span></p>
        <p begin="1.0" end="2.0" itunes:key="L2"><span begin="1.0" end="2.0">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `inline translations still migrate beside a declared transliteration track`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)

        assertTrue(result.converted)
        assertTrue(
            result.ttml.contains(
                "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>" +
                    "</translation></translations>",
            ),
        )
        assertFalse(result.ttml.substringAfter("<body").contains("x-translation"))
        assertTrue(result.ttml.substringBefore('>').contains("""xml:lang="ko""""))
    }

    @Test
    fun `the declared transliteration track is neither duplicated nor rewritten`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)

        assertEquals(1, Regex("""<iTunesMetadata""").findAll(result.ttml).count())
        assertEquals(1, Regex("""<transliterations>""").findAll(result.ttml).count())
        // Apple lists translations first, and the track it already had is intact.
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<transliterations>"))
        assertTrue(
            result.ttml.contains(
                "<transliterations><transliteration><text for=\"L1\">" +
                    "<span begin=\"0.0\" end=\"1.0\">ro</span></text>",
            ),
        )
        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }

    @Test
    fun `a declared kind keeps its inline spans rather than migrating twice`() {
        val declaredAndInline = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ro</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            <span ttm:role="x-translation">T1</span></p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(declaredAndInline)

        assertEquals(1, Regex("""<transliterations>""").findAll(result.ttml).count())
        assertFalse(result.ttml.contains("<text for=\"L1\">R1</text>"))
        assertTrue(result.ttml.substringAfter("<body").contains("<span ttm:role=\"x-roman\">R1</span>"))
        assertTrue(result.ttml.contains("<text for=\"L1\">T1</text>"))
    }

    @Test
    fun `the mixed rewrite is idempotent`() {
        val once = AmllTtmlFormatConverter.toAppleFormat(mixedFormat)
        val twice = AmllTtmlFormatConverter.toAppleFormat(once.ttml)

        assertFalse(twice.converted)
        assertEquals(once.ttml, twice.ttml)
    }

    @Test
    fun `an itunes metadata carrying only songwriters is still migrated into`() {
        // AMLL writes this element itself, so it is no sign of the Apple format.
        val songwritersOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <songwriters><songwriter>Someone</songwriter></songwriters>
            </iTunesMetadata></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(songwritersOnly)

        assertTrue(result.converted)
        // One container, with the tracks ahead of the songwriters Apple lists last.
        assertEquals(1, Regex("""<iTunesMetadata""").findAll(result.ttml).count())
        assertTrue(
            result.ttml.contains(
                "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
                    "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
                    "<text for=\"L1\">T1</text></translation></translations>",
            ),
        )
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<songwriters>"))
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
        assertFalse(AmllTtmlFormatConverter.toAppleFormat("<unclosed attr=\"").converted)
        // A truncated document still has its root declared, but no track is
        // invented from lines the scanner could not close.
        listOf(
            "<tt><body>",
            "<tt><head><metadata/></head><body><p itunes:key=\"L1\">",
        ).forEach { malformed ->
            val result = AmllTtmlFormatConverter.toAppleFormat(malformed)

            assertFalse(result.ttml.contains("<iTunesMetadata"))
            assertTrue(result.ttml.startsWith("<tt "))
            assertTrue(result.ttml.contains("""xml:lang="ko""""))
        }
    }

    @Test
    fun `the converted document still satisfies the ttml input policy`() {
        val result = AmllTtmlFormatConverter.toAppleFormat(amllFormat)

        assertTrue(TtmlInputPolicy.isAcceptable(result.ttml))
    }

    @Test
    fun `a single quoted itunes namespace declaration is retained without duplication`() {
        val singleQuotedNamespace = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes='http://music.apple.com/lyric-ttml-internal'>
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleQuotedNamespace)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertEquals(1, Regex("""xmlns:itunes\s*=""").findAll(root).count())
        assertTrue(root.contains("xmlns:itunes='http://music.apple.com/lyric-ttml-internal'"))
    }

    @Test
    fun `a body syllable with a single quoted begin sets word timing`() {
        val singleQuotedBegin = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin='0.0' end='1.0'>aa</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(singleQuotedBegin)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertTrue(root.contains("itunes:timing=\"Word\""))
    }

    @Test
    fun `seconds suffix background timing is stripped and an early background moves first`() {
        val secondsBackground = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0s" end="2s" itunes:key="L1">
            <span begin="1s" end="2s">aa</span>
            <span ttm:role="x-bg" begin="0.5s" end="1s"><span begin="0.5s" end="1s">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(secondsBackground)
        val body = result.ttml.substringAfter("<body")

        assertTrue(body.contains("<span ttm:role=\"x-bg\"><span begin=\"0.5s\" end=\"1s\">(bb)</span></span>"))
        assertTrue(body.indexOf("x-bg") < body.indexOf(">aa<"))
        assertFalse(body.contains("<span ttm:role=\"x-bg\" begin=\"0.5s\" end=\"1s\">"))
        assertEquals(1, Regex("""x-bg""").findAll(body).count())
    }

    @Test
    fun `timed spans only in a migrated head track keep a line timed body`() {
        val timedHeadLineBody = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            aa <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(timedHeadLineBody)
        val root = result.ttml.substringBefore('>')

        assertTrue(result.converted)
        assertTrue(root.contains("itunes:timing=\"Line\""))
    }

    @Test
    fun `existing translations stay before migrated inline romanization`() {
        val translationsHeadRomanBody = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p></div></body></tt>
        """.trimIndent()

        val result = AmllTtmlFormatConverter.toAppleFormat(translationsHeadRomanBody)

        assertTrue(result.converted)
        assertTrue(result.ttml.indexOf("<translations>") < result.ttml.indexOf("<transliterations>"))
        assertTrue(result.ttml.contains("<text for=\"L1\">R1</text>"))
    }
}
