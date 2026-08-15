package dev.amenhancer.module.font

import dev.amenhancer.module.config.FontManifestPolicy
import dev.amenhancer.module.model.LyricsFontManifest
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
    fun `rejects malformed magic but accepts files over sixteen mib`() {
        assertTrue(FontFilePolicy.inspect(byteArrayOf(1, 2, 3, 4)) is FontInspection.Rejected)

        val large = ByteArray(16 * 1024 * 1024 + 1)
        large[0] = 0
        large[1] = 1
        large[2] = 0
        large[3] = 0
        val result = FontFilePolicy.inspect(large)

        assertTrue(result is FontInspection.Accepted)
        assertEquals(large.size.toLong(), (result as FontInspection.Accepted).sizeBytes)
    }

    @Test
    fun `remote file ids reject dots and path separators`() {
        assertTrue(FontManifestPolicy.isValidFileId("font_abc123"))
        assertTrue(!FontManifestPolicy.isValidFileId("font.with.dot"))
        assertTrue(!FontManifestPolicy.isValidFileId("font/child"))
        assertTrue(!FontManifestPolicy.isValidFileId("font\\child"))
    }

    @Test
    fun `font manifests accept positive sizes above sixteen mib`() {
        val manifest = FontManifestPolicy.sanitize(
            LyricsFontManifest(
                enabled = true,
                fileId = "font_large",
                displayName = "Large font",
                sizeBytes = 16L * 1024L * 1024L + 1L,
                sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            ),
        )

        assertTrue(manifest.enabled)
        assertEquals(16L * 1024L * 1024L + 1L, manifest.sizeBytes)
    }
}
