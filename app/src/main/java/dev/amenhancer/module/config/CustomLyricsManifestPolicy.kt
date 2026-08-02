package dev.amenhancer.module.config

import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources

/** Validates the small cross-process index without trusting remote preferences. */
internal object CustomLyricsManifestPolicy {
    const val MAX_ENTRIES = 32

    private val fileIdPattern = Regex("[A-Za-z0-9_-]{1,96}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val allowedSources = setOf(
        CustomLyricsSources.MANUAL,
        CustomLyricsSources.AMLL,
        CustomLyricsSources.NETEASE,
    )

    fun sanitize(manifest: CustomLyricsManifest): CustomLyricsManifest {
        val entries = linkedMapOf<Long, CustomLyricsEntry>()
        manifest.entries.forEach { raw ->
            val entry = sanitizeEntry(raw) ?: return@forEach
            if (entries.size < MAX_ENTRIES && entry.appleMusicId !in entries) {
                entries[entry.appleMusicId] = entry
            }
        }
        return CustomLyricsManifest(entries.values.toList())
    }

    fun sanitizeDisplayName(displayName: String): String = displayName
        .filterNot(Char::isISOControl)
        .trim()
        .take(120)
        .ifBlank { "自定义歌词" }

    fun isValidFileId(fileId: String): Boolean =
        fileIdPattern.matches(fileId) && fileId.none { it == '.' || it == '/' || it == '\\' }

    private fun sanitizeEntry(raw: CustomLyricsEntry): CustomLyricsEntry? {
        if (raw.appleMusicId <= 0L) return null
        if (!isValidFileId(raw.fileId)) return null
        if (raw.sizeBytes !in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong()) return null
        if (!sha256Pattern.matches(raw.sha256)) return null
        if (raw.source !in allowedSources) return null
        return raw.copy(
            displayName = sanitizeDisplayName(raw.displayName),
            sha256 = raw.sha256.lowercase(),
        )
    }
}
