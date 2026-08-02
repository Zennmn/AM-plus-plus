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

internal sealed interface CustomLyricsSaveResult {
    data class Saved(
        val manifest: CustomLyricsManifest,
        val entry: CustomLyricsEntry,
    ) : CustomLyricsSaveResult

    data class Failed(val message: String) : CustomLyricsSaveResult
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
    ): CustomLyricsSaveResult {
        if (draft.appleMusicId <= 0L) {
            return CustomLyricsSaveResult.Failed("Apple Music ID 必须是正整数")
        }
        val inspection = CustomLyricsFilePolicy.inspect(draft.ttml)
        if (inspection is CustomLyricsInspection.Rejected) {
            return CustomLyricsSaveResult.Failed(inspection.message)
        }
        val accepted = inspection as CustomLyricsInspection.Accepted
        val fileId = runCatching(fileIdFactory).getOrNull()
            ?: return CustomLyricsSaveResult.Failed("无法生成歌词文件 ID")
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) {
            return CustomLyricsSaveResult.Failed("生成的歌词文件 ID 无效")
        }
        val entry = CustomLyricsEntry(
            appleMusicId = draft.appleMusicId,
            displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(draft.displayName),
            fileId = fileId,
            sizeBytes = accepted.bytes.size.toLong(),
            sha256 = accepted.sha256,
            source = draft.source,
            enabled = draft.enabled,
        )
        val manifest = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(
                oldManifest.entries.filterNot { it.appleMusicId == draft.appleMusicId } + entry,
            ),
        )
        if (manifest.entries.none { it.appleMusicId == entry.appleMusicId && it.fileId == entry.fileId }) {
            return CustomLyricsSaveResult.Failed("歌词映射无效")
        }
        if (!runCatching { writeRemoteFile(fileId, accepted.bytes) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsSaveResult.Failed("无法写入共享歌词文件")
        }
        if (!runCatching { publishManifest(manifest) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsSaveResult.Failed("无法发布歌词映射")
        }
        oldManifest.entries.firstOrNull { it.appleMusicId == entry.appleMusicId }
            ?.fileId
            ?.takeIf { it != entry.fileId }
            ?.let { oldFileId -> runCatching { deleteRemoteFile(oldFileId) } }
        return CustomLyricsSaveResult.Saved(manifest, entry)
    }
}
