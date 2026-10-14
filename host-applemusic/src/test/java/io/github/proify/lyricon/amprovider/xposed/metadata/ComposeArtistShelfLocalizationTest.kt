package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeArtistShelfLocalizationTest {
    private fun source(relative: String): String = sequenceOf(File(relative), File("../$relative"))
        .firstOrNull(File::isFile)?.readText() ?: error("Missing $relative")

    private val hooks by lazy {
        source("host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt")
    }

    @Test
    fun republishAlwaysProducesADistinctRevision() {
        assertEquals(7L, composeProfileRevision(7L, bump = false))
        assertEquals(8L, composeProfileRevision(7L, bump = true))
    }

    @Test
    fun shelfEntityKindsCoverSongsAndAlbumsOnly() {
        assertEquals(InAppLibraryEntityKind.SONG, composeShelfEntityKind("songs"))
        assertEquals(InAppLibraryEntityKind.SONG, composeShelfEntityKind("library-songs"))
        assertEquals(InAppLibraryEntityKind.ALBUM, composeShelfEntityKind("albums"))
        assertEquals(InAppLibraryEntityKind.ALBUM, composeShelfEntityKind("library-albums"))
        assertNull(composeShelfEntityKind("music-videos"))
        assertNull(composeShelfEntityKind("playlists"))
        assertNull(composeShelfEntityKind("artists"))
        assertNull(composeShelfEntityKind(null))
    }

    @Test
    fun republishBumpsRevisionAndReappliesShelfAliases() {
        val body = hooks.substringAfter("private fun republishComposeProfile(").substringBefore("private class ComposeShelfRegistration")
        assertTrue(body.contains("applyCachedShelfAliases(members, entity)"))
        assertTrue(body.contains("rebuildState(owner, state, entity, bumpRevision = true)"))
        // Equal Success states were silently dropped; the old early return must not come back.
        assertFalse(body.contains("rebuilt == state"))
    }

    @Test
    fun firstSuccessRegistersEveryShelfAndAppliesCachedAliases() {
        val body = hooks.substringAfter("private fun registerComposeProfileShelfEntities(")
            .substringBefore("private fun applyCachedShelfAliases(")
        assertTrue(body.contains("members.shelfEntities(profileEntity)"))
        assertTrue(body.contains("composeShelfEntityKind(members.entityTypeOf(shelfEntity))"))
        assertTrue(body.contains("host.effectiveAlias(mediaId)"))
        assertTrue(body.contains("installation.sessions.register(session, mediaId)"))
        val associateAt = body.indexOf("associateTopSongWithProfileArtist(owner, mediaId)")
        val enrichAt = body.indexOf("host.enrichLibraryEntity(", associateAt)
        assertTrue(associateAt >= 0 && enrichAt > associateAt)
        val state = hooks.substringAfter("private fun onComposeProfileState(").substringBefore("private fun republishComposeProfile(")
        assertTrue(state.contains("var aliasApplied = shelf.aliasApplied"))
        assertTrue(state.contains("requestPageOriginalMetadata(songIds)"))
    }
}
