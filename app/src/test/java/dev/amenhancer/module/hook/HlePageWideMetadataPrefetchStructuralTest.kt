package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HlePageWideMetadataPrefetchStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing $relative")

    @Test
    fun collectionModelsScheduleAllLoadedPageEntitiesBeforeRecyclerRowsBind() {
        val collection = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleCollectionSurfaceHooks.kt",
        )

        assertTrue(collection.contains("schedulePageMetadataResolution"))
        assertTrue(collection.contains("trackMediaIds"))
        assertTrue(collection.contains("pageType = \"album\""))
        assertTrue(collection.contains("pageType = \"playlist\""))
        assertTrue(collection.contains("RequestPriority.ACTIVE_PAGE"))
    }

    @Test
    fun artistTopSongsUseOnePageWideOriginalMetadataRequest() {
        val artist = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )

        assertTrue(artist.contains("requestPageOriginalMetadata"))
        assertTrue(artist.contains("topSongRetryIds.addAll"))
        assertTrue(artist.contains("RequestPriority.ACTIVE_PAGE"))
    }
}
