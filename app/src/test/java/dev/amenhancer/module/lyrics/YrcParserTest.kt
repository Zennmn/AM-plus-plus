package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YrcParserTest {

    @Test
    fun `parses the real netease yrc sample with absolute millisecond words`() {
        // Verified real YRC line (absolute ms word starts and explicit
        // durations; each word keeps its own trailing space).
        val document = YrcParser.parse(
            "[190871,1984](190871,361,0)For (191232,172,0)the (191404,134,0)longest (191538,301,0)time",
        )

        assertNotNull(document)
        val line = document!!.lines.single()
        assertEquals(190_871L, line.startMs)
        assertEquals(192_855L, line.endMs)
        assertEquals(
            listOf("For ", "the ", "longest ", "time"),
            line.words.map { it.text },
        )
        assertEquals(
            listOf(190_871L, 191_232L, 191_404L, 191_538L),
            line.words.map { it.startMs },
        )
        assertEquals(
            listOf(191_232L, 191_404L, 191_538L, 191_839L),
            line.words.map { it.endMs },
        )
    }

    @Test
    fun `parses a multi line document with exact line bounds`() {
        val document = YrcParser.parse(
            "[20519,3011](20519,423,0)ż(20942,2588,0)词\n" +
                "[24449,3491](24449,185,0)词(24634,158,0)词(24792,203,0)词",
        )

        assertEquals(2, document!!.lines.size)
        assertEquals(listOf(20_519L, 24_449L), document.lines.map { it.startMs })
        assertEquals(listOf(23_530L, 27_940L), document.lines.map { it.endMs })
        assertEquals(2, document.lines[0].words.size)
        assertEquals(3, document.lines[1].words.size)
    }

    @Test
    fun `zero duration placeholders merge into the next word as leading text`() {
        val document = YrcParser.parse(
            "[30258,2239](30258,363,0)A(30621,421,0)B(31042,370,0)C(0,0,0) (31412,427,0)D",
        )

        val line = document!!.lines.single()
        assertEquals(listOf("A", "B", "C", " D"), line.words.map { it.text })
        assertEquals(listOf(31_412L), line.words.drop(3).map { it.startMs })
        assertEquals(31_839L, line.words.last().endMs)
    }

    @Test
    fun `a trailing zero duration placeholder merges into the previous word`() {
        val document = YrcParser.parse("[0,1000](0,300,0)word(0,0,0) !")

        val line = document!!.lines.single()
        assertEquals(listOf("word !"), line.words.map { it.text })
        assertEquals(listOf(0L, 300L), listOf(line.words.single().startMs, line.words.single().endMs))
    }

    @Test
    fun `lines without timed word markers are skipped`() {
        val document = YrcParser.parse(
            "[1000,500]plain line without markers\n" +
                "[2000,500](0,0,0) only timeless\n" +
                "[3000,500](3000,200,0)real",
        )

        assertEquals(1, document!!.lines.size)
        assertEquals(3000L, document!!.lines.single().startMs)
        assertEquals("real", document.lines.single().words.single().text)
    }

    @Test
    fun `a document without any timed word marker returns null`() {
        assertNull(YrcParser.parse("[1000,500]line one\n[2000,500]line two"))
        assertNull(YrcParser.parse("[1000,500](0,0,0) "))
    }

    @Test
    fun `words outside the line range are dropped never clamped`() {
        val document = YrcParser.parse("[1000,500](1000,300,0)ok(1500,600,0)too late")

        val line = document!!.lines.single()
        assertEquals(listOf("ok"), line.words.map { it.text })
        assertEquals(1300L, line.words.single().endMs)
    }

    @Test
    fun `empty word markers are dropped`() {
        val document = YrcParser.parse("[0,1000](0,200,0)(200,300,0)A(500,200,0)(700,300,0)B")

        val words = document!!.lines.single().words
        assertEquals(listOf("A", "B"), words.map { it.text })
        assertEquals(listOf(200L, 700L), words.map { it.startMs })
    }

    @Test
    fun `malformed lines are isolated without failing the document`() {
        val document = YrcParser.parse(
            "[12345]header without duration\n" +
                "[abc,123]garbage header\n" +
                "(0,0,0)no header at all\n" +
                "[5000,1000](5000,400,0)good",
        )

        assertEquals(1, document!!.lines.size)
        assertEquals("good", document!!.lines.single().words.single().text)
    }

    @Test
    fun `blank input returns null`() {
        assertNull(YrcParser.parse(""))
        assertNull(YrcParser.parse("   \n  "))
    }
}
