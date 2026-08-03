package dev.amenhancer.module.config

/**
 * Small remote-preference pointer to the remote index file. The pointer is
 * published atomically after the new index file is written; readers trust it
 * only when the file's size and SHA-256 match.
 */
internal data class CustomLyricsIndexPointer(
    val fileId: String,
    val generation: Long,
    val sha256: String,
    val sizeBytes: Long,
)
