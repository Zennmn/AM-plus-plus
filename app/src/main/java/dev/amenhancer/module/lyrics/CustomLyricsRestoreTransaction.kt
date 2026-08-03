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
 * same Apple Music ID, current-only IDs are kept. The backup's files arrive
 * one at a time through the caller-supplied stream; every one is written to a
 * fresh remote fileId as it arrives, the merged manifest is published once
 * after the whole backup scans, then old files of overwritten IDs are
 * deleted. Any scan, write, or publish failure rolls back only the new files
 * and leaves the old manifest and old files untouched. An empty backup is a
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
        streamBackup: (onFile: (fileId: String, bytes: ByteArray) -> Unit) -> CustomLyricsBackupDecodeResult,
    ): CustomLyricsRestoreResult {
        val allocatedFileIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        val newFileIds = linkedMapOf<String, String>()
        val written = mutableListOf<String>()
        var writeError: String? = null
        val scan = streamBackup { backupFileId, bytes ->
            if (writeError != null) return@streamBackup
            val newFileId = newFileIds[backupFileId]
            if (newFileId == null) {
                val generated = runCatching(fileIdFactory).getOrNull()
                    ?.takeIf(CustomLyricsManifestPolicy::isValidFileId)
                if (generated == null) {
                    writeError = "无法生成歌词文件 ID"
                    return@streamBackup
                }
                if (generated in allocatedFileIds) {
                    writeError = "无法生成唯一歌词文件 ID"
                    return@streamBackup
                }
                allocatedFileIds += generated
                newFileIds[backupFileId] = generated
            }
            val target = newFileIds.getValue(backupFileId)
            if (!runCatching { writeRemoteFile(target, bytes) }.getOrDefault(false)) {
                runCatching { deleteRemoteFile(target) }
                writeError = "无法写入共享歌词文件"
                return@streamBackup
            }
            written += target
        }
        return when (scan) {
            is CustomLyricsBackupDecodeResult.Rejected -> {
                written.forEach { runCatching { deleteRemoteFile(it) } }
                CustomLyricsRestoreResult.Failed(scan.message)
            }
            is CustomLyricsBackupDecodeResult.Decoded -> {
                val backup = scan.backup
                if (backup.manifest.entries.isEmpty()) {
                    return CustomLyricsRestoreResult.Restored(oldManifest)
                }
                val error = writeError
                if (error != null) {
                    written.forEach { runCatching { deleteRemoteFile(it) } }
                    return CustomLyricsRestoreResult.Failed(error)
                }
                val rebuilt = mutableListOf<CustomLyricsEntry>()
                for (incoming in backup.manifest.entries) {
                    val fileId = newFileIds[incoming.fileId]
                    if (fileId == null) {
                        written.forEach { runCatching { deleteRemoteFile(it) } }
                        return CustomLyricsRestoreResult.Failed("备份内容缺失")
                    }
                    rebuilt += incoming.copy(fileId = fileId)
                }
                val incomingById = rebuilt.associateBy(CustomLyricsEntry::appleMusicId)
                val currentIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
                val mergedEntries = mutableListOf<CustomLyricsEntry>()
                val retiredFileIds = mutableListOf<String>()
                oldManifest.entries.forEach { current ->
                    val replacement = incomingById[current.appleMusicId]
                    if (replacement != null) {
                        mergedEntries += replacement
                        retiredFileIds += current.fileId
                    } else {
                        mergedEntries += current
                    }
                }
                rebuilt.forEach { entry ->
                    if (entry.appleMusicId !in currentIds) mergedEntries += entry
                }
                val merged = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(mergedEntries))
                if (!runCatching { publishManifest(merged) }.getOrDefault(false)) {
                    written.forEach { runCatching { deleteRemoteFile(it) } }
                    return CustomLyricsRestoreResult.Failed("无法发布歌词映射")
                }
                retiredFileIds.forEach { runCatching { deleteRemoteFile(it) } }
                CustomLyricsRestoreResult.Restored(merged)
            }
        }
    }
}
