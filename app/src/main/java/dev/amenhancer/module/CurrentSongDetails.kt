package dev.amenhancer.module

/** Verified current-item metadata shared by target hooks and embedded settings. */
internal data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
)
