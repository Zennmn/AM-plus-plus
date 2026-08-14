package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources

internal data class CustomLyricsDraft(
    val appleMusicId: Long,
    val displayName: String,
    val ttml: String,
    val source: String = CustomLyricsSources.MANUAL,
    val enabled: Boolean = true,
)

internal data class CustomLyricsMultiIdDraft(
    val appleMusicIds: List<Long>,
    val displayName: String,
    val ttml: String,
    val source: String = CustomLyricsSources.MANUAL,
    val enabled: Boolean = true,
)

internal sealed interface CustomLyricsSaveResult {
    data class Saved(
        val manifest: CustomLyricsManifest,
        val entry: CustomLyricsEntry,
    ) : CustomLyricsSaveResult

    data class Failed(val message: String) : CustomLyricsSaveResult
}

internal sealed interface CustomLyricsBatchSaveResult {
    data class Saved(
        val manifest: CustomLyricsManifest,
        val entries: List<CustomLyricsEntry>,
    ) : CustomLyricsBatchSaveResult

    data class Failed(val message: String) : CustomLyricsBatchSaveResult
}

/** Writes a new TTML file before publishing the replacement manifest. */
internal class CustomLyricsImportTransaction(
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
            return CustomLyricsBatchSaveResult.Failed("Apple Music ID 必须是正整数")
        }
        if (draft.appleMusicIds.distinct().size != draft.appleMusicIds.size) {
            return CustomLyricsBatchSaveResult.Failed("Apple Music ID 不能重复")
        }
        if (replacingAppleMusicIds.any { it <= 0L } ||
            replacingAppleMusicIds.distinct().size != replacingAppleMusicIds.size
        ) {
            return CustomLyricsBatchSaveResult.Failed("原歌词映射不存在")
        }

        val replacingIds = replacingAppleMusicIds.toSet()
        val replacedEntries = oldManifest.entries.filter { it.appleMusicId in replacingIds }
        if (replacedEntries.size != replacingIds.size) {
            return CustomLyricsBatchSaveResult.Failed("原歌词映射不存在")
        }
        if (oldManifest.entries.any {
                it.appleMusicId in draft.appleMusicIds && it.appleMusicId !in replacingIds
            }
        ) {
            return CustomLyricsBatchSaveResult.Failed("目标 Apple Music ID 已存在")
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
                ?: return rollbackAndFail(::rollbackNewFiles, "无法生成歌词文件 ID")
            if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) {
                return rollbackAndFail(::rollbackNewFiles, "生成的歌词文件 ID 无效")
            }
            if (fileId in existingFileIds) {
                return rollbackAndFail(::rollbackNewFiles, "生成的歌词文件 ID 已存在")
            }
            if (!generatedFileIds.add(fileId)) {
                return rollbackAndFail(::rollbackNewFiles, "生成的歌词文件 ID 重复")
            }
            entries += CustomLyricsEntry(
                appleMusicId = appleMusicId,
                displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(draft.displayName),
                fileId = fileId,
                sizeBytes = accepted.bytes.size.toLong(),
                sha256 = accepted.sha256,
                source = draft.source,
                enabled = draft.enabled,
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
            return CustomLyricsBatchSaveResult.Failed("歌词映射无效")
        }

        entries.forEach { entry ->
            if (!runCatching { writeRemoteFile(entry.fileId, accepted.bytes) }.getOrDefault(false)) {
                rollbackNewFiles()
                return CustomLyricsBatchSaveResult.Failed("无法写入共享歌词文件")
            }
        }
        if (!runCatching { publishManifest(manifest) }.getOrDefault(false)) {
            rollbackNewFiles()
            return CustomLyricsBatchSaveResult.Failed("无法发布歌词映射")
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
