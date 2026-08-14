package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources

/** One enabled remote entry and the local Apple Music IDs it represents. */
internal data class CustomLyricsSyncPlanEntry(
    val key: String,
    val appleMusicIds: List<Long>,
    val displayName: String,
    val source: String = CustomLyricsSources.AM_LYRICS,
)

internal sealed interface CustomLyricsSyncLoadResult {
    data class Loaded(val ttml: String) : CustomLyricsSyncLoadResult
    data class Failed(val message: String) : CustomLyricsSyncLoadResult
    data object Cancelled : CustomLyricsSyncLoadResult
}

internal data class CustomLyricsSyncProgress(
    val processedEntries: Int,
    val totalEntries: Int,
    val importedIds: Int,
    val overwrittenIds: Int,
)

internal sealed interface CustomLyricsSyncResult {
    data class Synced(
        val manifest: CustomLyricsManifest,
        val sourceEntryCount: Int,
        val importedIds: Int,
        val overwrittenIds: Int,
        val preservedIds: Int,
    ) : CustomLyricsSyncResult

    data object Cancelled : CustomLyricsSyncResult
    data class Failed(val message: String) : CustomLyricsSyncResult
}

/**
 * Atomically merges a complete remote source into the local lyrics index.
 * Remote IDs are authoritative when they are present; local-only IDs remain
 * untouched. All new TTML files are written before the single index publish,
 * and every failure or cancellation removes only files created by this run.
 */
internal class CustomLyricsSyncTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (CustomLyricsManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
) {
    fun sync(
        oldManifest: CustomLyricsManifest,
        plan: List<CustomLyricsSyncPlanEntry>,
        loadTtml: (CustomLyricsSyncPlanEntry) -> CustomLyricsSyncLoadResult,
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsSyncProgress) -> Unit = {},
    ): CustomLyricsSyncResult {
        val safeOld = CustomLyricsManifestPolicy.sanitize(oldManifest)
        if (safeOld.entries.size != oldManifest.entries.size) {
            return CustomLyricsSyncResult.Failed("本地歌词索引无效，无法同步")
        }

        val validationFailure = validatePlan(plan)
        if (validationFailure != null) return CustomLyricsSyncResult.Failed(validationFailure)
        if (plan.isEmpty()) {
            return CustomLyricsSyncResult.Synced(
                manifest = oldManifest,
                sourceEntryCount = 0,
                importedIds = 0,
                overwrittenIds = 0,
                preservedIds = oldManifest.entries.size,
            )
        }

        val remoteIds = plan.flatMapTo(mutableSetOf()) { it.appleMusicIds }
        val oldIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        val nextEntries = oldManifest.entries
            .filterNot { it.appleMusicId in remoteIds }
            .toMutableList()
        val allocatedFileIds = oldManifest.entries.mapTo(
            mutableSetOf(),
            CustomLyricsEntry::fileId,
        )
        val writtenFileIds = mutableListOf<String>()
        var importedIds = 0
        var overwrittenIds = 0

        fun cleanupNewFiles() {
            writtenFileIds.forEach { fileId -> runCatching { deleteRemoteFile(fileId) } }
        }

        for ((index, source) in plan.withIndex()) {
            if (isCancelledSafely(isCancelled)) {
                cleanupNewFiles()
                return CustomLyricsSyncResult.Cancelled
            }
            val loaded = runCatching { loadTtml(source) }.getOrElse {
                CustomLyricsSyncLoadResult.Failed("读取 GitHub 歌词失败：${source.displayName}")
            }
            val ttml = when (loaded) {
                is CustomLyricsSyncLoadResult.Loaded -> loaded.ttml
                is CustomLyricsSyncLoadResult.Cancelled -> {
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Cancelled
                }
                is CustomLyricsSyncLoadResult.Failed -> {
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Failed(loaded.message)
                }
            }
            val inspection = CustomLyricsFilePolicy.inspect(ttml)
            if (inspection is CustomLyricsInspection.Rejected) {
                cleanupNewFiles()
                return CustomLyricsSyncResult.Failed(
                    "GitHub 歌词无效：${source.displayName}（${inspection.message}）",
                )
            }
            val accepted = inspection as CustomLyricsInspection.Accepted
            source.appleMusicIds.forEach { appleMusicId ->
                if (isCancelledSafely(isCancelled)) {
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Cancelled
                }
                val fileId = runCatching { fileIdFactory() }.getOrNull()
                    ?.takeIf(CustomLyricsManifestPolicy::isValidFileId)
                if (fileId == null) {
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Failed("无法生成歌词文件 ID")
                }
                if (!allocatedFileIds.add(fileId)) {
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Failed("无法生成唯一歌词文件 ID")
                }
                if (!runCatching { writeRemoteFile(fileId, accepted.bytes) }.getOrDefault(false)) {
                    runCatching { deleteRemoteFile(fileId) }
                    cleanupNewFiles()
                    return CustomLyricsSyncResult.Failed("无法写入共享歌词文件")
                }
                writtenFileIds += fileId
                nextEntries += CustomLyricsEntry(
                    appleMusicId = appleMusicId,
                    displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(
                        source.displayName,
                    ),
                    fileId = fileId,
                    sizeBytes = accepted.bytes.size.toLong(),
                    sha256 = accepted.sha256,
                    source = source.source,
                    enabled = true,
                )
                if (appleMusicId in oldIds) overwrittenIds += 1 else importedIds += 1
            }
            reportProgressSafely(
                onProgress,
                CustomLyricsSyncProgress(
                    processedEntries = index + 1,
                    totalEntries = plan.size,
                    importedIds = importedIds,
                    overwrittenIds = overwrittenIds,
                ),
            )
        }

        if (isCancelledSafely(isCancelled)) {
            cleanupNewFiles()
            return CustomLyricsSyncResult.Cancelled
        }

        val nextManifest = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(nextEntries))
        if (nextManifest.entries.size != nextEntries.size ||
            nextManifest.entries.map(CustomLyricsEntry::appleMusicId).toSet() !=
            (oldManifest.entries.map(CustomLyricsEntry::appleMusicId).toSet() - remoteIds) +
                remoteIds
        ) {
            cleanupNewFiles()
            return CustomLyricsSyncResult.Failed("同步后的歌词索引无效")
        }
        if (!runCatching { publishManifest(nextManifest) }.getOrDefault(false)) {
            cleanupNewFiles()
            return CustomLyricsSyncResult.Failed("无法发布歌词索引")
        }

        val nextFileIds = nextManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        oldManifest.entries.map(CustomLyricsEntry::fileId)
            .toSet()
            .filterNot(nextFileIds::contains)
            .forEach { fileId -> runCatching { deleteRemoteFile(fileId) } }

        return CustomLyricsSyncResult.Synced(
            manifest = nextManifest,
            sourceEntryCount = plan.size,
            importedIds = importedIds,
            overwrittenIds = overwrittenIds,
            preservedIds = oldManifest.entries.count { it.appleMusicId !in remoteIds },
        )
    }

    private fun validatePlan(plan: List<CustomLyricsSyncPlanEntry>): String? {
        val ids = mutableSetOf<Long>()
        val keys = mutableSetOf<String>()
        plan.forEach { entry ->
            if (entry.key.isBlank()) return "GitHub 索引包含空路径"
            if (!keys.add(entry.key)) return "GitHub 索引包含重复路径"
            if (entry.appleMusicIds.isEmpty()) return "GitHub 索引包含空 Apple Music ID 映射"
            entry.appleMusicIds.forEach { appleMusicId ->
                if (appleMusicId <= 0L) return "GitHub 索引包含无效 Apple Music ID"
                if (!ids.add(appleMusicId)) return "GitHub 索引包含重复 Apple Music ID"
            }
        }
        return null
    }

    private fun isCancelledSafely(isCancelled: () -> Boolean): Boolean =
        runCatching { isCancelled() }.getOrDefault(false)

    private fun reportProgressSafely(
        onProgress: (CustomLyricsSyncProgress) -> Unit,
        progress: CustomLyricsSyncProgress,
    ) {
        runCatching { onProgress(progress) }
    }
}
