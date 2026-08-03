package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `legacy v1 payloads still decode`() {
        val v1 = """{"version":1,"entries":[{"appleMusicId":42,"displayName":"Old","fileId":"lyrics_old","sizeBytes":42,"sha256":"0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53","source":"manual","enabled":true}]}"""

        val decoded = CustomLyricsManifestCodec.decode(v1)

        assertEquals(listOf(42L), decoded.entries.map(CustomLyricsEntry::appleMusicId))
        assertEquals("lyrics_old", decoded.entries.single().fileId)
    }

    @Test
    fun `v2 payloads round trip with over a thousand entries`() {
        val manifest = CustomLyricsManifest(
            (1..1100).map { index ->
                entry.copy(appleMusicId = 100000L + index, fileId = "lyrics_%06d".format(index))
            },
        )

        val roundTripped = CustomLyricsManifestCodec.decode(CustomLyricsManifestCodec.encode(manifest))

        assertEquals(1100, roundTripped.entries.size)
        assertEquals(manifest, roundTripped)
    }

    @Test
    fun `decodeStrict accepts legacy v1 and current v2 payloads but nothing else`() {
        val v1 = """{"version":1,"entries":[{"appleMusicId":42,"displayName":"Old","fileId":"lyrics_old","sizeBytes":42,"sha256":"0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53","source":"manual","enabled":true}]}"""

        assertNotNull(CustomLyricsManifestCodec.decodeStrict(v1))
        assertNotNull(CustomLyricsManifestCodec.decodeStrict(CustomLyricsManifestCodec.encode(entryManifest())))
        assertNull(CustomLyricsManifestCodec.decodeStrict("""{"version":3,"entries":[]}"""))
        assertNull(CustomLyricsManifestCodec.decodeStrict("not json"))
    }

    @Test
    fun `decodeIndexFile accepts only current v2 index documents`() {
        val manifest = entryManifest()

        assertEquals(
            manifest,
            CustomLyricsManifestCodec.decodeIndexFile(CustomLyricsManifestCodec.encode(manifest)),
        )
        assertEquals(
            CustomLyricsManifest.empty(),
            CustomLyricsManifestCodec.decodeIndexFile("""{"version":2,"entries":[]}"""),
        )
        assertNull(CustomLyricsManifestCodec.decodeIndexFile("""{"version":1,"entries":[]}"""))
        assertNull(CustomLyricsManifestCodec.decodeIndexFile("not json"))
        assertNull(CustomLyricsManifestCodec.decodeIndexFile("""{"version":2}"""))
    }

    private fun entryManifest(): CustomLyricsManifest = CustomLyricsManifest(listOf(entry))
}
