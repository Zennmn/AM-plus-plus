package dev.amenhancer.module.lyrics

/** One timed word inside a lyric line. Timing is in milliseconds. */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** One lyric line with its word-level timing. Timing is in milliseconds. */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord>,
)

/**
 * Unified, Android-free lyric document produced from every online source.
 * Only the main word track is kept; translations and transliterations are
 * deliberately discarded because they cannot be aligned to words reliably.
 */
data class LyricDocument(
    val lines: List<LyricLine>,
)
