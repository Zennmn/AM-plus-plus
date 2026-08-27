package dev.amenhancer.module

/** Private request/reply contract between the AM++ settings and target processes. */
internal object CurrentSongIdentityProtocol {
    const val REQUEST_ACTION = "dev.amenhancer.module.action.REQUEST_CURRENT_SONG_ID"
    const val REQUEST_PERMISSION = "dev.amenhancer.module.permission.REQUEST_CURRENT_SONG_ID"
    const val EXTRA_REQUEST_TOKEN = "dev.amenhancer.module.extra.CURRENT_SONG_ID_REQUEST_TOKEN"
    const val EXTRA_RESULT_RECEIVER = "dev.amenhancer.module.extra.CURRENT_SONG_ID_RESULT_RECEIVER"
    const val EXTRA_APPLE_MUSIC_ID = "dev.amenhancer.module.extra.CURRENT_SONG_ID"
    const val EXTRA_SONG_TITLE = "dev.amenhancer.module.extra.CURRENT_SONG_TITLE"
    const val EXTRA_SONG_ARTIST = "dev.amenhancer.module.extra.CURRENT_SONG_ARTIST"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_AVAILABLE = 1
}

internal data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
)
