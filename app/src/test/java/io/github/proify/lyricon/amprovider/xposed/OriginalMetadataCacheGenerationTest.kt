package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalMetadataCacheGenerationTest {
    @Test
    fun storefrontArtistLocalizationCachesUseASeparateGeneration() {
        assertEquals(
            5,
            AppleOriginalMetadataCache.currentDatabaseVersionForTest(),
        )
        assertEquals(
            "hyperlyricsenhanced_apple_original_metadata_v5.db",
            AppleOriginalMetadataCache.currentDatabaseNameForTest(),
        )
        assertEquals(
            "hyperlyricsenhanced_apple_original_artist_regions_v5",
            AppleOriginalMetadataCache.currentArtistRegionPreferencesNameForTest(),
        )
    }
}
