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

/**
 * Private request/reply contract for the user-triggered Apple Music library poll.
 *
 * The request action and its extras are the stable wire contract; the cancel
 * action is a separate broadcast scoped by the same request token, so a caller
 * compiled against the first refresh contract never sees it and a cancel can
 * only ever stop the refresh that owns the token.
 */
internal object LibraryRefreshProtocol {
    const val REQUEST_ACTION = "dev.amenhancer.module.action.REQUEST_LIBRARY_REFRESH"
    const val CANCEL_ACTION = "dev.amenhancer.module.action.CANCEL_LIBRARY_REFRESH"
    const val REQUEST_PERMISSION = "dev.amenhancer.module.permission.REQUEST_LIBRARY_REFRESH"
    const val EXTRA_REQUEST_TOKEN = "dev.amenhancer.module.extra.LIBRARY_REFRESH_REQUEST_TOKEN"
    const val EXTRA_RESULT_RECEIVER = "dev.amenhancer.module.extra.LIBRARY_REFRESH_RESULT_RECEIVER"
    const val EXTRA_RESULT_MESSAGE = "dev.amenhancer.module.extra.LIBRARY_REFRESH_RESULT_MESSAGE"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_STARTED = 1
    const val RESULT_FAILED = 2
    const val RESULT_COMPLETED = 3

    /** A CANCEL_ACTION arrived for this token and the refresh stopped cooperatively. */
    const val RESULT_CANCELLED = 4

    /** Kept for callers compiled against the first refresh contract. */
    const val RESULT_TRIGGERED = RESULT_STARTED
}

internal data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
)
