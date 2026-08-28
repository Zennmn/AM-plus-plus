package io.github.proify.lyricon.amprovider.xposed

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetadataCacheProfileTest {
    @After
    fun restoreDefaultProfile() {
        MediaMetadataCache.setProfile("account")
        MediaMetadataCache.clearProfile()
    }

    @Test
    fun `metadata is namespaced by the active profile`() {
        val metadata = MediaMetadataCache.Metadata(
            id = "42",
            title = "Song",
            artist = "Artist",
            genre = null,
            duration = 0L,
            queueId = 0L,
        )

        MediaMetadataCache.setProfile("cn_v1")
        MediaMetadataCache.put(metadata)
        assertEquals("Song", MediaMetadataCache.getMetadataById("42")?.title)

        MediaMetadataCache.setProfile("jp_v1")
        assertNull(MediaMetadataCache.getMetadataById("42"))
        MediaMetadataCache.put(metadata.copy(title = "曲"))
        assertEquals("曲", MediaMetadataCache.getMetadataById("42")?.title)
    }
}
