package dev.amenhancer.module.config

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources

/** Validates the cross-process index without trusting remote preferences. */
object CustomLyricsManifestPolicy {
    /** Legacy read bound; older releases accepted a single combined 8 MiB index. */
    const val MAX_INDEX_BYTES = 8 * 1024 * 1024

    /** New manual and source indexes are independently bounded to 2 MiB. */
    const val MAX_CURRENT_INDEX_BYTES = 2 * 1024 * 1024

    private val fileIdPattern = Regex("[A-Za-z0-9_-]{1,96}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val allowedSources = setOf(
        CustomLyricsSources.MANUAL,
        CustomLyricsSources.AUTO_CACHE,
        CustomLyricsSources.AMLL,
        CustomLyricsSources.AM_LYRICS,
        CustomLyricsSources.LUNABEAT,
    )

    fun sanitize(manifest: CustomLyricsManifest): CustomLyricsManifest {
        val entries = linkedMapOf<Long, CustomLyricsEntry>()
        manifest.entries.forEach { raw ->
            val entry = sanitizeEntry(raw) ?: return@forEach
            if (entry.appleMusicId !in entries) {
                entries[entry.appleMusicId] = entry
            }
        }
        return CustomLyricsManifest(entries.values.toList())
    }

    fun isValidSha256(sha256: String): Boolean = sha256Pattern.matches(sha256)

    fun sanitizeDisplayName(displayName: String): String = displayName
        .filterNot(Char::isISOControl)
        .trim()
        .take(120)
        .ifBlank { ModuleText.CUSTOM_LYRICS.text() }

    fun isValidFileId(fileId: String): Boolean =
        fileIdPattern.matches(fileId) && fileId.none { it == '.' || it == '/' || it == '\\' }

    /** Unknown providers are conservatively manual, as are entries without a source. */
    fun canonicalSource(source: String): String =
        source.takeIf(allowedSources::contains) ?: CustomLyricsSources.MANUAL

    fun isThirdPartySource(source: String): Boolean =
        source in allowedSources && source != CustomLyricsSources.MANUAL


    fun isUsableSourceId(source: String): Boolean =
        source.isNotBlank() && source.length <= 96 && source.none(Char::isISOControl)

    private fun sanitizeEntry(raw: CustomLyricsEntry): CustomLyricsEntry? {
        if (raw.appleMusicId <= 0L) return null
        if (!isValidFileId(raw.fileId)) return null
        if (raw.sizeBytes !in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong()) return null
        if (!isValidSha256(raw.sha256)) return null
        return raw.copy(
            displayName = sanitizeDisplayName(raw.displayName),
            sha256 = raw.sha256.lowercase(),
            // Entries created by removed/unknown providers remain usable as
            // manually managed TTML instead of disappearing on read.
            source = canonicalSource(raw.source),
        )
    }
}
