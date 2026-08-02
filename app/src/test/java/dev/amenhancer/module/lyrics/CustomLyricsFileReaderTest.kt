package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomLyricsFileReaderTest {
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"
    private val bytes = ttml.toByteArray()
    private val entry = CustomLyricsEntry(
        appleMusicId = 42L,
        displayName = "Song",
        fileId = "lyrics_abc123",
        sizeBytes = bytes.size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(bytes),
        source = CustomLyricsSources.MANUAL,
    )

    @Test
    fun `reader accepts only a file that matches its published size hash and ttml policy`() {
        assertEquals(ttml, CustomLyricsFileReader { bytes }.read(entry))
        assertNull(CustomLyricsFileReader { bytes + byteArrayOf(0) }.read(entry))
        assertNull(CustomLyricsFileReader { "not ttml".toByteArray() }.read(entry.copy(
            sizeBytes = "not ttml".toByteArray().size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256("not ttml".toByteArray()),
        )))
    }
}
