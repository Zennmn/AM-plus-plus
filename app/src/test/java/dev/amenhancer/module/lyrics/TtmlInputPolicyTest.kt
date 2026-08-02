package dev.amenhancer.module.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlInputPolicyTest {

    private val valid = "<tt xmlns=\"http://www.w3.org/ns/ttml\">" +
        "<body><div><p begin=\"0.000\" end=\"1.000\">" +
        "<span begin=\"0.000\" end=\"1.000\">word</span></p></div></body></tt>"

    @Test
    fun `a well formed small ttml passes`() {
        assertTrue(TtmlInputPolicy.isAcceptable(valid))
    }

    @Test
    fun `empty input is rejected`() {
        assertFalse(TtmlInputPolicy.isAcceptable(""))
    }

    @Test
    fun `input above the byte cap is rejected`() {
        val oversized = valid + "x".repeat(TtmlInputPolicy.MAX_TTML_BYTES)
        assertFalse(TtmlInputPolicy.isAcceptable(oversized))
    }

    @Test
    fun `missing root markers is rejected`() {
        assertFalse(TtmlInputPolicy.isAcceptable("<div>no tt root</div>"))
        assertFalse(TtmlInputPolicy.isAcceptable("<tt><div>no body</div></tt>"))
        assertFalse(TtmlInputPolicy.isAcceptable("<body><div>no tt</div></body>"))
    }

    @Test
    fun `line count above the cap is rejected`() {
        val manyLines = "<tt><body>" + "<p>x</p>".repeat(TtmlInputPolicy.MAX_LINES + 1) + "</body></tt>"
        assertFalse(TtmlInputPolicy.isAcceptable(manyLines))
    }

    @Test
    fun `word count above the cap is rejected`() {
        val manyWords = "<tt><body><p>" +
            "<span>x</span>".repeat(TtmlInputPolicy.MAX_WORDS + 1) +
            "</p></body></tt>"
        assertFalse(TtmlInputPolicy.isAcceptable(manyWords))
    }

    @Test
    fun `tags that merely contain the markers do not count`() {
        val tricky = "<tt><body><paper>not a line</paper><spanish>not a word</spanish></body></tt>"
        assertTrue(TtmlInputPolicy.isAcceptable(tricky))
    }
}
