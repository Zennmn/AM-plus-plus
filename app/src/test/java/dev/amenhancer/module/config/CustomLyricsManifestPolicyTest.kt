package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomLyricsManifestPolicyTest {
    private val sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"

    @Test
    fun `sanitize keeps over a thousand entries without truncation`() {
        val manifest = CustomLyricsManifest(
            (1..1100).map { index ->
                CustomLyricsEntry(
                    appleMusicId = 100000L + index,
                    displayName = "Song $index",
                    fileId = "lyrics_%06d".format(index),
                    sizeBytes = 42L,
                    sha256 = sha256,
                    source = CustomLyricsSources.MANUAL,
                    enabled = true,
                )
            },
        )

        assertEquals(1100, CustomLyricsManifestPolicy.sanitize(manifest).entries.size)
    }

    @Test
    fun `sanitize still deduplicates ids and drops invalid entries`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(42L, "lyrics_one"),
                entry(42L, "lyrics_two"),
                entry(84L, "bad..id"),
            ),
        )

        val sanitized = CustomLyricsManifestPolicy.sanitize(manifest)
        assertEquals(listOf(42L), sanitized.entries.map(CustomLyricsEntry::appleMusicId))
    }

    @Test
    fun `sha256 validation accepts only hex digests`() {
        assertEquals(true, CustomLyricsManifestPolicy.isValidSha256(sha256))
        assertEquals(false, CustomLyricsManifestPolicy.isValidSha256("not-a-hash"))
        assertEquals(false, CustomLyricsManifestPolicy.isValidSha256(""))
    }

    @Test
    fun `sanitize keeps the am lyrics source`() {
        val sanitized = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(listOf(entry(42L, "lyrics_am", CustomLyricsSources.AM_LYRICS))),
        )

        assertEquals(CustomLyricsSources.AM_LYRICS, sanitized.entries.single().source)
    }

    @Test
    fun `removed provider entries remain usable as manual lyrics`() {
        val sanitized = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(listOf(entry(42L, "lyrics_removed", "removed-provider"))),
        )

        assertEquals(CustomLyricsSources.MANUAL, sanitized.entries.single().source)
    }

    private fun entry(
        appleMusicId: Long,
        fileId: String,
        source: String = CustomLyricsSources.MANUAL,
    ) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = "Song $appleMusicId",
        fileId = fileId,
        sizeBytes = 42L,
        sha256 = sha256,
        source = source,
    )
}
