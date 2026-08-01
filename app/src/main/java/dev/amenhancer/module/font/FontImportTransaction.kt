package dev.amenhancer.module.font

import dev.amenhancer.module.config.FontManifestPolicy
import dev.amenhancer.module.model.LyricsFontManifest

internal sealed interface FontImportResult {
    data class Imported(val manifest: LyricsFontManifest) : FontImportResult
    data class Failed(val message: String) : FontImportResult
}

/** Commits a new remote file first, and publishes its manifest only after the copy succeeds. */
internal class FontImportTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (LyricsFontManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
    private val validateTypeface: (ByteArray) -> Boolean = { true },
) {
    fun import(displayName: String, bytes: ByteArray): FontImportResult {
        val inspection = FontFilePolicy.inspect(bytes)
        if (inspection is FontInspection.Rejected) {
            return FontImportResult.Failed(inspection.message)
        }

        val accepted = inspection as FontInspection.Accepted
        if (!runCatching { validateTypeface(bytes) }.getOrDefault(false)) {
            return FontImportResult.Failed("Android Typeface.Builder could not parse the font")
        }

        val fileId = runCatching { fileIdFactory() }.getOrNull()
            ?: return FontImportResult.Failed("Generated remote file id was unavailable")
        if (!FontManifestPolicy.isValidFileId(fileId)) {
            return FontImportResult.Failed("Generated remote file id was invalid")
        }

        val manifest = LyricsFontManifest(
            enabled = true,
            fileId = fileId,
            displayName = FontManifestPolicy.sanitizeDisplayName(displayName),
            sizeBytes = accepted.sizeBytes,
            sha256 = accepted.sha256,
        )
        if (!runCatching { writeRemoteFile(fileId, bytes) }.getOrDefault(false)) {
            return FontImportResult.Failed("Unable to write shared font file")
        }
        if (!runCatching { publishManifest(manifest) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return FontImportResult.Failed("Unable to publish font configuration")
        }
        return FontImportResult.Imported(manifest)
    }
}
