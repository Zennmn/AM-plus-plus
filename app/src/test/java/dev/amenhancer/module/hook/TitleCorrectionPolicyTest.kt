package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AMTool-equivalent title correction policy: cache key layout,
 * locale/schema invalidation, the Catalog title replacement gate and the
 * album key safety rule (a song must never be overwritten by an album entry).
 */
class TitleCorrectionPolicyTest {
    private val zh = mapOf(
        "catalog-title:zh-CN:42" to "爱情故事",
        "catalog-song:zh-CN:42" to "爱情故事",
        "catalog-artist:zh-CN:42" to "泰勒·斯威夫特",
        "catalog-album:zh-CN:42" to "爱情故事专辑",
        "catalog-album-name:zh-CN:42" to "爱情故事专辑",
    )

    @Test
    fun `script classification separates Han Latin and other`() {
        assertEquals(TitleCorrectionPolicy.Script.HAN, TitleCorrectionPolicy.scriptOf("夜曲"))
        assertEquals(TitleCorrectionPolicy.Script.LATIN, TitleCorrectionPolicy.scriptOf("Nocturne"))
        assertEquals(TitleCorrectionPolicy.Script.OTHER, TitleCorrectionPolicy.scriptOf("123 ..."))
        assertEquals(TitleCorrectionPolicy.Script.HAN, TitleCorrectionPolicy.scriptOf("Love 夜曲"))
    }

    @Test
    fun `a differing Catalog title is accepted regardless of script or locale`() {
        assertTrue(TitleCorrectionPolicy.allowReplace("夜曲", "Nocturne", "tr-TR"))
        assertTrue(TitleCorrectionPolicy.allowReplace("夜曲", "Nocturne", ""))
        assertTrue(TitleCorrectionPolicy.allowReplace("夜曲", "Nocturne", "zh-CN"))
    }

    @Test
    fun `a Han candidate upgrades a Latin title regardless of the target`() {
        assertTrue(TitleCorrectionPolicy.allowReplace("Love Story", "爱情故事", "zh-CN"))
        assertTrue(TitleCorrectionPolicy.allowReplace("Love Story", "爱情故事", "tr-TR"))
    }

    @Test
    fun `target locale does not gate a differing Catalog title`() {
        assertTrue(TitleCorrectionPolicy.allowReplace("Love Story", "Love Story 2", "zh-CN"))
        assertTrue(TitleCorrectionPolicy.allowReplace("Love Story", "Love Story 2", "tr-TR"))
    }

    @Test
    fun `Catalog relationship title can replace a Han raw title`() {
        val values = mapOf("catalog-song:tr-TR:42" to "Nocturne")
        assertEquals(
            "Nocturne",
            TitleCorrectionPolicy.correctionCandidate(
                appleMusicId = "42",
                raw = "夜曲",
                kind = TitleCorrectionPolicy.CacheKind.SONG,
                values = values,
                localeTag = "tr-TR",
                entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                schemaCurrent = true,
            ),
        )
    }

    @Test
    fun `blank or equal values never replace`() {
        assertFalse(TitleCorrectionPolicy.allowReplace("", "中文", "zh-CN"))
        assertFalse(TitleCorrectionPolicy.allowReplace("Same", "Same", "tr-TR"))
        assertFalse(TitleCorrectionPolicy.allowReplace("Same", null, "tr-TR"))
    }

    @Test
    fun `cjk locale detection covers zh ja and ko`() {
        assertTrue(TitleCorrectionPolicy.isCjkLocale("zh-CN"))
        assertTrue(TitleCorrectionPolicy.isCjkLocale("ja-JP"))
        assertTrue(TitleCorrectionPolicy.isCjkLocale("ko-KR"))
        assertFalse(TitleCorrectionPolicy.isCjkLocale("tr-TR"))
        assertFalse(TitleCorrectionPolicy.isCjkLocale(""))
    }

    @Test
    fun `a song never consumes the album key even when the id collides`() {
        val albumOnly = mapOf("catalog-album:zh-CN:42" to "专辑名")
        val corrected = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "Song Title",
            kind = TitleCorrectionPolicy.CacheKind.SONG,
            values = albumOnly,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.SONG,
            schemaCurrent = true,
        )
        assertNull(corrected)
    }

    @Test
    fun `album key is only usable for album entities`() {
        val corrected = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "Album Title",
            kind = TitleCorrectionPolicy.CacheKind.ALBUM,
            values = zh,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.ALBUM,
            schemaCurrent = true,
        )
        assertEquals("爱情故事专辑", corrected)

        val songHit = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "Song Title",
            kind = TitleCorrectionPolicy.CacheKind.ALBUM,
            values = zh,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.SONG,
            schemaCurrent = true,
        )
        assertNull(songHit)
    }

    @Test
    fun `legacy schema blocks album keys until migration`() {
        val corrected = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "Album Title",
            kind = TitleCorrectionPolicy.CacheKind.ALBUM,
            values = zh,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.ALBUM,
            schemaCurrent = false,
        )
        assertNull(corrected)
    }

    @Test
    fun `album key remains isolated from song and album name metadata`() {
        val albumOnly = mapOf(
            "catalog-album:zh-CN:42" to "爱情故事专辑",
            "catalog-album-name:zh-CN:42" to "爱情故事专辑",
        )
        assertNull(
            TitleCorrectionPolicy.correctionCandidate(
                appleMusicId = "42",
                raw = "Song Title",
                kind = TitleCorrectionPolicy.CacheKind.SONG,
                values = albumOnly,
                localeTag = "zh-CN",
                entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                schemaCurrent = true,
            ),
        )
        assertEquals(
            "爱情故事专辑",
            TitleCorrectionPolicy.correctionCandidate(
                appleMusicId = "42",
                raw = "Album Name",
                kind = TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                values = albumOnly,
                localeTag = "zh-CN",
                entityKind = TitleCorrectionPolicy.EntityKind.ALBUM,
                schemaCurrent = true,
            ),
        )
    }

    @Test
    fun `artist and album name metadata resolve with the replacement gate`() {
        val artist = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "Taylor Swift",
            kind = TitleCorrectionPolicy.CacheKind.ARTIST,
            values = zh,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.SONG,
            schemaCurrent = true,
        )
        assertEquals("泰勒·斯威夫特", artist)

        val blocked = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "泰勒·斯威夫特",
            kind = TitleCorrectionPolicy.CacheKind.ARTIST,
            values = zh,
            localeTag = "zh-CN",
            entityKind = TitleCorrectionPolicy.EntityKind.SONG,
            schemaCurrent = true,
        )
        assertNull(blocked)
    }

    @Test
    fun `generic title key is the fallback for the title kinds only`() {
        val titleOnly = mapOf("catalog-title:zh-CN:42" to "爱情故事")
        listOf(
            TitleCorrectionPolicy.CacheKind.TITLE,
            TitleCorrectionPolicy.CacheKind.SONG,
        ).forEach { kind ->
            assertEquals(
                "爱情故事",
                TitleCorrectionPolicy.correctionCandidate(
                    appleMusicId = "42",
                    raw = "English",
                    kind = kind,
                    values = titleOnly,
                    localeTag = "zh-CN",
                    entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                    schemaCurrent = true,
                ),
            )
        }
    }

    @Test
    fun `artist and album name never consume the generic title key`() {
        val titleOnly = mapOf("catalog-title:zh-CN:42" to "爱情故事")
        listOf(
            TitleCorrectionPolicy.CacheKind.ARTIST,
            TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
        ).forEach { kind ->
            assertNull(
                TitleCorrectionPolicy.correctionCandidate(
                    appleMusicId = "42",
                    raw = "English",
                    kind = kind,
                    values = titleOnly,
                    localeTag = "zh-CN",
                    entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                    schemaCurrent = true,
                ),
            )
        }
    }

    @Test
    fun `title and song keys fall back to each other only`() {
        val songOnly = mapOf("catalog-song:zh-CN:42" to "爱情故事")
        assertEquals(
            "爱情故事",
            TitleCorrectionPolicy.correctionCandidate(
                appleMusicId = "42",
                raw = "English",
                kind = TitleCorrectionPolicy.CacheKind.TITLE,
                values = songOnly,
                localeTag = "zh-CN",
                entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                schemaCurrent = true,
            ),
        )
        assertNull(
            TitleCorrectionPolicy.correctionCandidate(
                appleMusicId = "42",
                raw = "Taylor Swift",
                kind = TitleCorrectionPolicy.CacheKind.ARTIST,
                values = songOnly,
                localeTag = "zh-CN",
                entityKind = TitleCorrectionPolicy.EntityKind.SONG,
                schemaCurrent = true,
            ),
        )
    }

    @Test
    fun `locale scoping leaves other locale entries untouched`() {
        val otherLocale = TitleCorrectionPolicy.correctionCandidate(
            appleMusicId = "42",
            raw = "English",
            kind = TitleCorrectionPolicy.CacheKind.SONG,
            values = zh,
            localeTag = "zh-Hans",
            entityKind = TitleCorrectionPolicy.EntityKind.SONG,
            schemaCurrent = true,
        )
        assertNull(otherLocale)
    }

    @Test
    fun `entity kind classification follows the binary class name`() {
        assertEquals(
            TitleCorrectionPolicy.EntityKind.ALBUM,
            TitleCorrectionPolicy.entityKindOf("com.apple.android.music.model.AlbumCollectionItem"),
        )
        assertEquals(
            TitleCorrectionPolicy.EntityKind.ALBUM,
            TitleCorrectionPolicy.entityKindOf("com.apple.android.music.mediaapi.models.LibraryAlbum"),
        )
        assertEquals(
            TitleCorrectionPolicy.EntityKind.SONG,
            TitleCorrectionPolicy.entityKindOf("com.apple.android.music.model.Song"),
        )
        assertEquals(
            TitleCorrectionPolicy.EntityKind.UNKNOWN,
            TitleCorrectionPolicy.entityKindOf("com.apple.android.music.mediaapi.models.MediaEntity"),
        )
    }

    @Test
    fun `cache keys are locale and kind scoped with the schema marker`() {
        assertEquals("catalog-title:zh-CN:42", TitleCorrectionPolicy.cacheKey("zh-CN", "42"))
        assertEquals(
            "catalog-song:zh-CN:42",
            TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.SONG, "zh-CN", "42"),
        )
        assertEquals(
            "catalog-album-name:zh-CN:42",
            TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM_NAME, "zh-CN", "42"),
        )
        assertEquals("catalog-schema", TitleCorrectionPolicy.schemaKey())
        assertFalse(TitleCorrectionPolicy.isCurrentSchema(null))
        assertFalse(TitleCorrectionPolicy.isCurrentSchema(TitleCorrectionPolicy.LEGACY_SCHEMA))
        assertTrue(TitleCorrectionPolicy.isCurrentSchema(TitleCorrectionPolicy.SCHEMA_VERSION))
    }

    @Test
    fun `legacy usable precondition is preserved`() {
        assertTrue(TitleCorrectionPolicy.usable("English", "中文"))
        assertFalse(TitleCorrectionPolicy.usable("", "中文"))
        assertFalse(TitleCorrectionPolicy.usable("Same", "Same"))
    }
}
