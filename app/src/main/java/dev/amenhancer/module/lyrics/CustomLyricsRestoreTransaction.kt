package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest

internal sealed interface CustomLyricsRestoreResult {
    data class Restored(val manifest: CustomLyricsManifest) : CustomLyricsRestoreResult
    data class Failed(val message: String) : CustomLyricsRestoreResult
}

/**
 * Merge-restore semantics: backup entries overwrite current entries with the
 * same Apple Music ID, current-only IDs are kept. Every backup entry is
 * rebuilt with a fresh remote fileId; the merged manifest is published once
 * after all new files are written, then old files of overwritten IDs are
 * deleted. Any write or publish failure rolls back only the new files and
 * leaves the old manifest and old files untouched. An empty backup is a
 * successful no-op.
 */
internal class CustomLyricsRestoreTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (CustomLyricsManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
) {
    fun merge(
        oldManifest: CustomLyricsManifest,
        backup: CustomLyricsBackup,
    ): CustomLyricsRestoreResult {
        if (backup.manifest.entries.isEmpty()) {
            return CustomLyricsRestoreResult.Restored(oldManifest)
        }
        val rebuilt = mutableListOf<Pair<CustomLyricsEntry, ByteArray>>()
        val allocatedFileIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        for (incoming in backup.manifest.entries) {
            val bytes = backup.files[incoming.fileId]
                ?: return CustomLyricsRestoreResult.Failed("备份内容缺失")
            val fileId = runCatching(fileIdFactory).getOrNull()
                ?.takeIf(CustomLyricsManifestPolicy::isValidFileId)
                ?: return CustomLyricsRestoreResult.Failed("无法生成歌词文件 ID")
            if (!allocatedFileIds.add(fileId)) {
                return CustomLyricsRestoreResult.Failed("无法生成唯一歌词文件 ID")
            }
            rebuilt += incoming.copy(fileId = fileId) to bytes
        }
        val incomingById = rebuilt.associate { it.first.appleMusicId to it }
        val currentIds = oldManifest.entries.map { it.appleMusicId }.toSet()
        val mergedEntries = mutableListOf<CustomLyricsEntry>()
        val retiredFileIds = mutableListOf<String>()
        oldManifest.entries.forEach { current ->
            val replacement = incomingById[current.appleMusicId]
            if (replacement != null) {
                mergedEntries += replacement.first
                retiredFileIds += current.fileId
            } else {
                mergedEntries += current
            }
        }
        rebuilt.forEach { (entry, _) ->
            if (entry.appleMusicId !in currentIds) mergedEntries += entry
        }
        if (mergedEntries.size > CustomLyricsManifestPolicy.MAX_ENTRIES) {
            return CustomLyricsRestoreResult.Failed("合并后歌词映射数量超过上限")
        }
        val merged = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(mergedEntries))
        val written = mutableListOf<String>()
        rebuilt.forEach { (entry, bytes) ->
            if (!runCatching { writeRemoteFile(entry.fileId, bytes) }.getOrDefault(false)) {
                runCatching { deleteRemoteFile(entry.fileId) }
                written.forEach { runCatching { deleteRemoteFile(it) } }
                return CustomLyricsRestoreResult.Failed("无法写入共享歌词文件")
            }
            written += entry.fileId
        }
        if (!runCatching { publishManifest(merged) }.getOrDefault(false)) {
            written.forEach { runCatching { deleteRemoteFile(it) } }
            return CustomLyricsRestoreResult.Failed("无法发布歌词映射")
        }
        retiredFileIds.forEach { runCatching { deleteRemoteFile(it) } }
        return CustomLyricsRestoreResult.Restored(merged)
    }
}
