package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalMetadataCacheGenerationTest {
    @Test
    fun storefrontArtistLocalizationCachesUseASeparateGeneration() {
        assertEquals(
            1,
            AppleOriginalMetadataCache.currentDatabaseVersionForTest(),
        )
        assertEquals(
            "hyperlyricsenhanced_apple_original_metadata_original_hyper_v1.db",
            AppleOriginalMetadataCache.currentDatabaseNameForTest(),
        )
        assertEquals(
            "hyperlyricsenhanced_apple_original_artist_regions_original_hyper_v1",
            AppleOriginalMetadataCache.currentArtistRegionPreferencesNameForTest(),
        )
    }
}
