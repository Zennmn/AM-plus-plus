package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HleArtistLanguageStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing $relative")

    @Test
    fun resolverNeverTreatsStorefrontLocalizedArtistNamesAsOriginalRegionEvidence() {
        val resolver = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        assertFalse(resolver.contains("probeOriginalArtistLanguage"))
        assertFalse(resolver.contains("artistLanguages = listOfNotNull(cachedArtistLanguage)"))
        assertFalse(resolver.contains("originKnownFromArtist"))
        assertFalse(resolver.contains("trustedArtistOnlyLanguages"))
        assertTrue(resolver.contains("originKnown = isrc != null"))
        assertTrue(resolver.contains("isConfidentOriginalSongAlias"))
    }

    @Test
    fun confirmedArtistRegionRequeuesAssociatedSongs() {
        val coordinator = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataResolutionCoordinator.kt",
        )
        assertTrue(coordinator.contains("resetOriginalResolutionState"))
        assertTrue(coordinator.contains("associatedMediaIds"))
        assertTrue(coordinator.contains("RequestPriority.VISIBLE"))
    }
}
