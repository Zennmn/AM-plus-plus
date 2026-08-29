package io.github.proify.lyricon.amprovider.xposed

/** AM++ refresh seam replacing HLE's lyric playback manager dependency. */
internal object PlaybackManager {
    @Volatile
    private var metadataResolutionFinished: ((String) -> Unit)? = null

    fun setMetadataResolutionFinishedListener(listener: ((String) -> Unit)?) {
        metadataResolutionFinished = listener
    }

    fun onSongChanged(@Suppress("UNUSED_PARAMETER") mediaId: String) = Unit
    fun onCatalogMetadataResolved(@Suppress("UNUSED_PARAMETER") mediaId: String) = Unit

    fun onCatalogMetadataResolutionFinished(mediaId: String) {
        if (mediaId.isNotBlank()) metadataResolutionFinished?.invoke(mediaId)
    }
}
