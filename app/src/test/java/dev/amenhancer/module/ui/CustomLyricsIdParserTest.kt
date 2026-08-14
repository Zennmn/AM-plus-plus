package dev.amenhancer.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomLyricsIdParserTest {
    @Test
    fun `parses trimmed comma separated positive ids`() {
        assertEquals(listOf(100L, 200L, 300L), CustomLyricsIdParser.parse(" 100, 200 ,300 "))
        assertEquals(100L, CustomLyricsIdParser.parsePrimary(" 100, 200 ,300 "))
        assertEquals("100,200,300", CustomLyricsIdParser.format(listOf(100L, 200L, 300L)))
    }

    @Test
    fun `rejects empty invalid and duplicate ids`() {
        assertNull(CustomLyricsIdParser.parse(""))
        assertNull(CustomLyricsIdParser.parse("100,,200"))
        assertNull(CustomLyricsIdParser.parse("100,abc"))
        assertNull(CustomLyricsIdParser.parse("100,0"))
        assertNull(CustomLyricsIdParser.parse("100,100"))
    }
}
