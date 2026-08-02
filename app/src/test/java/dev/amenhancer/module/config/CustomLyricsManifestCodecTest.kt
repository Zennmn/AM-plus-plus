package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomLyricsManifestCodecTest {
    private val entry = CustomLyricsEntry(
        appleMusicId = 123456789L,
        displayName = "Song",
        fileId = "lyrics_abc123",
        sizeBytes = 42L,
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
        source = CustomLyricsSources.AMLL,
    )

    @Test
    fun `manifest round trips through one remote preference string`() {
        val manifest = CustomLyricsManifest(listOf(entry))

        assertEquals(manifest, CustomLyricsManifestCodec.decode(CustomLyricsManifestCodec.encode(manifest)))
    }

    @Test
    fun `malformed or untrusted entries fail closed`() {
        assertEquals(CustomLyricsManifest.empty(), CustomLyricsManifestCodec.decode("not json"))
        assertEquals(
            CustomLyricsManifest.empty(),
            CustomLyricsManifestCodec.decode(
                """{"version":1,"entries":[{"appleMusicId":0,"fileId":"../bad"}]}""",
            ),
        )
    }

    @Test
    fun `duplicate ids never leave an ambiguous target mapping`() {
        val manifest = CustomLyricsManifest(listOf(entry, entry.copy(fileId = "lyrics_other")))

        assertEquals(listOf(entry), CustomLyricsManifestPolicy.sanitize(manifest).entries)
    }
}
