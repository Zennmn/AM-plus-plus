package dev.amenhancer.module.font

import dev.amenhancer.module.config.FontManifestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontFilePolicyTest {
    @Test
    fun `accepts ttf and otto sfnt signatures and calculates sha256`() {
        val ttf = byteArrayOf(0, 1, 0, 0, 1, 2, 3)
        val otto = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte())

        val ttfResult = FontFilePolicy.inspect(ttf)
        val ottoResult = FontFilePolicy.inspect(otto)

        assertEquals(
            FontInspection.Accepted(
                sizeBytes = 7L,
                sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            ),
            ttfResult,
        )
        assertTrue(ottoResult is FontInspection.Accepted)
    }

    @Test
    fun `rejects ttc explicitly rather than pretending to support font collections`() {
        val result = FontFilePolicy.inspect(byteArrayOf('t'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte()))

        assertTrue(result is FontInspection.Rejected)
        assertTrue((result as FontInspection.Rejected).message.contains("TTC"))
    }

    @Test
    fun `rejects malformed magic and files over sixteen mib`() {
        assertTrue(FontFilePolicy.inspect(byteArrayOf(1, 2, 3, 4)) is FontInspection.Rejected)

        val tooLarge = ByteArray(FontFilePolicy.MAX_FONT_SIZE_BYTES.toInt() + 1)
        tooLarge[0] = 0
        tooLarge[1] = 1
        tooLarge[2] = 0
        tooLarge[3] = 0
        val result = FontFilePolicy.inspect(tooLarge)

        assertTrue(result is FontInspection.Rejected)
        assertTrue((result as FontInspection.Rejected).message.contains("16 MiB"))
    }

    @Test
    fun `remote file ids reject dots and path separators`() {
        assertTrue(FontManifestPolicy.isValidFileId("font_abc123"))
        assertTrue(!FontManifestPolicy.isValidFileId("font.with.dot"))
        assertTrue(!FontManifestPolicy.isValidFileId("font/child"))
        assertTrue(!FontManifestPolicy.isValidFileId("font\\child"))
    }
}
