package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackManagerTest {
    @Test
    fun `terminal metadata event reaches the configured listener`() {
        val events = mutableListOf<String>()
        PlaybackManager.setMetadataResolutionFinishedListener { events += it }
        try {
            PlaybackManager.onCatalogMetadataResolutionFinished("42")
            PlaybackManager.onCatalogMetadataResolutionFinished("")
        } finally {
            PlaybackManager.setMetadataResolutionFinishedListener(null)
        }

        assertEquals(listOf("42"), events)
    }
}
