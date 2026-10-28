package dev.amenhancer.module.lyrics

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources

data class CustomLyricsDraft(
    val appleMusicId: Long,
    val displayName: String,
    val ttml: String,
    val source: String = CustomLyricsSources.MANUAL,
    val enabled: Boolean = true,
    val autoManaged: Boolean = false,
)

data class CustomLyricsMultiIdDraft(
    val appleMusicIds: List<Long>,
    val displayName: String,
    val ttml: String,
    val source: String = CustomLyricsSources.MANUAL,
    val enabled: Boolean = true,
    val autoManaged: Boolean = false,
)

sealed interface CustomLyricsSaveResult {
    data class Saved(
        val manifest: CustomLyricsManifest,
        val entry: CustomLyricsEntry,
    ) : CustomLyricsSaveResult

    data class Failed(val message: String) : CustomLyricsSaveResult
}

sealed interface CustomLyricsBatchSaveResult {
    data class Saved(
        val manifest: CustomLyricsManifest,
        val entries: List<CustomLyricsEntry>,
    ) : CustomLyricsBatchSaveResult

    data class Failed(val message: String) : CustomLyricsBatchSaveResult
}

/** Writes a new TTML file before publishing the replacement manifest. */
class CustomLyricsImportTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (CustomLyricsManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
) {
    fun upsert(
        oldManifest: CustomLyricsManifest,
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = when (
        val result = upsertMany(
            oldManifest = oldManifest,
            draft = CustomLyricsMultiIdDraft(
                appleMusicIds = listOf(draft.appleMusicId),
                displayName = draft.displayName,
                ttml = draft.ttml,
                source = draft.source,
                enabled = draft.enabled,
                autoManaged = draft.autoManaged,
            ),
            replacingAppleMusicIds = replacingAppleMusicId?.let(::listOf).orEmpty(),
        )
    ) {
        is CustomLyricsBatchSaveResult.Saved ->
            CustomLyricsSaveResult.Saved(result.manifest, result.entries.single())
        is CustomLyricsBatchSaveResult.Failed -> CustomLyricsSaveResult.Failed(result.message)
    }

    fun upsertMany(
        oldManifest: CustomLyricsManifest,
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): CustomLyricsBatchSaveResult {
        if (draft.appleMusicIds.isEmpty() || draft.appleMusicIds.any { it <= 0L }) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.MUSIC_ID_POSITIVE_REQUIRED.text())
        }
        if (draft.appleMusicIds.distinct().size != draft.appleMusicIds.size) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.MUSIC_IDS_DUPLICATE.text())
        }
        if (replacingAppleMusicIds.any { it <= 0L } ||
            replacingAppleMusicIds.distinct().size != replacingAppleMusicIds.size
        ) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.ORIGINAL_MAPPING_MISSING.text())
        }

        val replacingIds = replacingAppleMusicIds.toSet()
        val replacedEntries = oldManifest.entries.filter { it.appleMusicId in replacingIds }
        if (replacedEntries.size != replacingIds.size) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.ORIGINAL_MAPPING_MISSING.text())
        }
        // This runs under the content transaction's mutation lock. A manual edit or
        // disable made after the automatic publisher's initial read must still win.
        if (draft.autoManaged && replacedEntries.any { !it.autoManaged || !it.enabled }) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.LYRICS_INDEX_CHANGED.text())
        }
        if (oldManifest.entries.any {
                it.appleMusicId in draft.appleMusicIds && it.appleMusicId !in replacingIds
            }
        ) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.TARGET_MUSIC_ID_EXISTS.text())
        }

        val inspection = CustomLyricsFilePolicy.inspect(draft.ttml)
        if (inspection is CustomLyricsInspection.Rejected) {
            return CustomLyricsBatchSaveResult.Failed(inspection.message)
        }
        val accepted = inspection as CustomLyricsInspection.Accepted
        val entries = mutableListOf<CustomLyricsEntry>()
        val generatedFileIds = mutableSetOf<String>()
        val existingFileIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        fun rollbackNewFiles() {
            generatedFileIds.forEach { fileId ->
                runCatching { deleteRemoteFile(fileId) }
            }
        }
        draft.appleMusicIds.forEach { appleMusicId ->
            val fileId = runCatching(fileIdFactory).getOrNull()
                ?: return rollbackAndFail(::rollbackNewFiles, ModuleText.LYRICS_FILE_ID_CREATE_FAILED.text())
            if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) {
                return rollbackAndFail(::rollbackNewFiles, ModuleText.LYRICS_FILE_ID_INVALID.text())
            }
            if (fileId in existingFileIds) {
                return rollbackAndFail(::rollbackNewFiles, ModuleText.LYRICS_FILE_ID_EXISTS.text())
            }
            if (!generatedFileIds.add(fileId)) {
                return rollbackAndFail(::rollbackNewFiles, ModuleText.LYRICS_FILE_ID_DUPLICATE.text())
            }
            entries += CustomLyricsEntry(
                appleMusicId = appleMusicId,
                displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(draft.displayName),
                fileId = fileId,
                sizeBytes = accepted.bytes.size.toLong(),
                sha256 = accepted.sha256,
                source = draft.source,
                enabled = draft.enabled,
                autoManaged = draft.autoManaged,
            )
        }

        val manifest = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(
                oldManifest.entries.filterNot { it.appleMusicId in replacingIds } + entries,
            ),
        )
        if (!entries.all { entry ->
                manifest.entries.any {
                    it.appleMusicId == entry.appleMusicId && it.fileId == entry.fileId
                }
            }
        ) {
            return CustomLyricsBatchSaveResult.Failed(ModuleText.LYRICS_MAPPING_INVALID.text())
        }

        entries.forEach { entry ->
            if (!runCatching { writeRemoteFile(entry.fileId, accepted.bytes) }.getOrDefault(false)) {
                rollbackNewFiles()
                return CustomLyricsBatchSaveResult.Failed(ModuleText.LYRICS_FILE_WRITE_FAILED.text())
            }
        }
        if (!runCatching { publishManifest(manifest) }.getOrDefault(false)) {
            rollbackNewFiles()
            return CustomLyricsBatchSaveResult.Failed(ModuleText.LYRICS_MAPPING_PUBLISH_FAILED.text())
        }

        val nextFileIds = manifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        replacedEntries.map(CustomLyricsEntry::fileId)
            .filterNot(nextFileIds::contains)
            .distinct()
            .forEach { oldFileId -> runCatching { deleteRemoteFile(oldFileId) } }
        return CustomLyricsBatchSaveResult.Saved(manifest, entries)
    }

    private fun rollbackAndFail(
        rollback: () -> Unit,
        message: String,
    ): CustomLyricsBatchSaveResult {
        rollback()
        return CustomLyricsBatchSaveResult.Failed(message)
    }
}
