package dev.amenhancer.module.config

import dev.amenhancer.module.model.LyricsFontManifest

/** Pure validation for the small manifest shared through remote preferences. */
internal object FontManifestPolicy {
    const val MAX_FONT_SIZE_BYTES = 16L * 1024L * 1024L

    private val fileIdPattern = Regex("[A-Za-z0-9_-]{1,96}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun isValidFileId(fileId: String): Boolean =
        fileIdPattern.matches(fileId) &&
            fileId.none { character -> character == '.' || character == '/' || character == '\\' }

    fun isValidSha256(sha256: String): Boolean = sha256Pattern.matches(sha256)

    fun sanitize(manifest: LyricsFontManifest): LyricsFontManifest {
        if (!manifest.enabled) return LyricsFontManifest.disabled()
        if (!isValidFileId(manifest.fileId)) return LyricsFontManifest.disabled()
        if (manifest.sizeBytes !in 1L..MAX_FONT_SIZE_BYTES) {
            return LyricsFontManifest.disabled()
        }
        if (!isValidSha256(manifest.sha256)) return LyricsFontManifest.disabled()

        return manifest.copy(
            displayName = sanitizeDisplayName(manifest.displayName),
            sha256 = manifest.sha256.lowercase(),
        )
    }

    fun sanitizeDisplayName(displayName: String): String = displayName
        .filterNot(Char::isISOControl)
        .trim()
        .take(120)
        .ifBlank { "导入字体" }
}
