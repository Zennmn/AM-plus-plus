package dev.amenhancer.module.hook

import com.apple.android.music.ttml.javanative.model.SongInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlNativeParserTest {

    private fun parserWith(nativeFixture: Class<*>): TtmlNativeParser? =
        TtmlNativeParser.create(
            parserClass = TtmlParserFixture::class.java,
            parseMethod = TtmlParserFixture::class.java.getDeclaredMethod(
                "songInfoFromTTML",
                String::class.java,
            ),
            ptrClass = SongInfo.SongInfoPtr::class.java,
            nativeClass = nativeFixture,
        )

    @Test
    fun `create builds the wrapper from the resolved surface`() {
        val parser = parserWith(SongInfo.SongInfoNative::class.java)

        assertNotNull(parser)
    }

    @Test
    fun `create returns null when the native surface is incomplete`() {
        val parser = TtmlNativeParser.create(
            parserClass = TtmlParserFixture::class.java,
            parseMethod = TtmlParserFixture::class.java.getDeclaredMethod(
                "songInfoFromTTML",
                String::class.java,
            ),
            ptrClass = SongInfo.SongInfoPtr::class.java,
            nativeClass = IncompleteNativeFixture::class.java,
        )

        assertNull(parser)
    }

    @Test
    fun `create fails closed without a JavaCPP liveness surface`() {
        val parser = TtmlNativeParser.create(
            parserClass = TtmlParserFixture::class.java,
            parseMethod = TtmlParserFixture::class.java.getDeclaredMethod(
                "songInfoFromTTML",
                String::class.java,
            ),
            ptrClass = PointerlessFixture::class.java,
            nativeClass = SongInfo.SongInfoNative::class.java,
        )

        assertNull(parser)
    }

    @Test
    fun `parse produces a pointer and validity requires sections`() {
        val parser = requireNotNull(parserWith(SongInfo.SongInfoNative::class.java))

        val ptr = parser.parse("<tt><body>ok</body></tt>")
        assertNotNull(ptr)
        // Fixture sections are empty (size 0) so the pointer is invalid.
        assertFalse(parser.isValid(ptr))
        // The fixture SongInfoNative reports adam id 0.
        assertEquals(0L, parser.adamIdOf(ptr!!))
    }

    @Test
    fun `null and foreign pointers are never valid or alive`() {
        val parser = requireNotNull(parserWith(SongInfo.SongInfoNative::class.java))

        assertFalse(parser.isValid(null))
        assertFalse(parser.isValid("not a pointer"))
        assertFalse(parser.isAlive(null))
        assertFalse(parser.isAlive("not a pointer"))
    }

    @Test
    fun `bind adam id verifies the value stuck`() {
        val parser = requireNotNull(parserWith(SongInfo.SongInfoNative::class.java))
        val ptr = parser.parse("<tt><body>ok</body></tt>")

        // Fixture always reports 0, so binding to 42 cannot verify.
        assertFalse(parser.bindAdamId(requireNotNull(ptr), 42L))
    }

    @Test
    fun `parse never throws and returns the fixture pointer`() {
        val parser = requireNotNull(parserWith(SongInfo.SongInfoNative::class.java))

        assertNotNull(parser.parse(""))
        assertNotNull(parser.parse("<tt><body>ok</body></tt>"))
    }

    @Test
    fun `a throwing parse invocation returns null`() {
        val parser = TtmlNativeParser.create(
            parserClass = ThrowingParserFixture::class.java,
            parseMethod = ThrowingParserFixture::class.java.getDeclaredMethod(
                "songInfoFromTTML",
                String::class.java,
            ),
            ptrClass = SongInfo.SongInfoPtr::class.java,
            nativeClass = SongInfo.SongInfoNative::class.java,
        )

        assertNull(parser?.parse("<tt>x</tt>"))
    }

    private class TtmlParserFixture {
        @Suppress("UNUSED_PARAMETER")
        fun songInfoFromTTML(ttml: String): SongInfo.SongInfoPtr = SongInfo.SongInfoPtr()
    }

    private class ThrowingParserFixture {
        @Suppress("UNUSED_PARAMETER")
        fun songInfoFromTTML(ttml: String): SongInfo.SongInfoPtr = error("native parse failed")
    }

    private class IncompleteNativeFixture {
        // No getSections / getAdamId / setAdamId surface.
        fun unrelated() = Unit
    }

    private class PointerlessFixture {
        fun get(): SongInfo.SongInfoNative = SongInfo.SongInfoNative()
    }
}
