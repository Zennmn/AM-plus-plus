package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalArtistLanguageEvidenceTest {
    @Test
    fun japaneseArtistNameProvidesJapaneseScriptEvidence() {
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("當山 みれい", "ja-JP"),
        )
        assertFalse(
            AppleInternalCatalogResolver.hasCjkArtistScript("MIREI", "ja-JP"),
        )
    }

    @Test
    fun koreanAndChineseScriptEvidenceStaysLanguageSpecific() {
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("방탄소년단", "ko-KR"),
        )
        assertTrue(
            AppleInternalCatalogResolver.hasCjkArtistScript("周杰伦", "zh-Hans-CN"),
        )
        assertFalse(
            AppleInternalCatalogResolver.hasCjkArtistScript("宇多田ヒカル", "zh-Hans-CN"),
        )
    }

    @Test
    fun artistRegionIsUsedOnlyAfterGenreAndIsrcSignalsAreAbsent() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = emptyList(),
                isrc = null,
                artistLanguages = listOf("ja-JP"),
            ),
        )
        assertEquals(
            listOf("ko-KR"),
            AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = emptyList(),
                isrc = "KRABC0000000",
                artistLanguages = listOf("ja-JP"),
            ),
        )
    }

    @Test
    fun sameEnglishTitleDoesNotApplyJapaneseArtistOnlyAlias() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Let Me Know",
            artist = "當山 みれい",
            language = "ja-JP",
        )

        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Let Me Know",
                localizedArtist = "MIREI",
            ),
        )
    }

    @Test
    fun sameEnglishTitleDoesNotAcceptRomanizedArtistOnlyAlias() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "Let Me Know",
            artist = "Mirei",
            language = "ja-JP",
        )

        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "Let Me Know",
                localizedArtist = "MIREI",
            ),
        )
    }

    @Test
    fun oneRepublicJapaneseStorefrontArtistAliasIsNotAnOriginalSongAlias() {
        val alias = AppleInternalCatalogResolver.Alias(
            title = "I Ain't Worried",
            artist = "ワンリパブリック",
            language = "ja-JP",
        )

        assertFalse(
            AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
                alias = alias,
                localizedTitle = "I Ain't Worried",
                localizedArtist = "OneRepublic",
            ),
        )
    }

    @Test
    fun japaneseIsrcProvidesTheActualArtistOnlyTrustLanguage() {
        assertEquals(
            listOf("ja-JP"),
            AppleInternalCatalogResolver.languageTagsForIsrc("JPU901901790"),
        )
    }

}
