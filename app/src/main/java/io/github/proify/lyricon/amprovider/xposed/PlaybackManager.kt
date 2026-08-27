package io.github.proify.lyricon.amprovider.xposed

/** AM++ refresh seam replacing HLE's lyric playback manager dependency. */
internal object PlaybackManager {
    fun onSongChanged(@Suppress("UNUSED_PARAMETER") mediaId: String) = Unit
    fun onCatalogMetadataResolved(@Suppress("UNUSED_PARAMETER") mediaId: String) = Unit
}
