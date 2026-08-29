package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest

/** Why a remote source could not replace a local mapping. */
internal enum class CustomLyricsUpdateFailureKind {
    NETWORK,
    SOURCE_MISSING,
    INVALID_TTML,
}

internal data class CustomLyricsUpdateIssue(
    val appleMusicId: Long,
    val source: String,
    val kind: CustomLyricsUpdateFailureKind,
    val message: String,
)

/** Progress emitted while the coordinator scans the current manifest. */
internal data class CustomLyricsUpdateProgress(
    val checkedEntries: Int,
    val totalEntries: Int,
    val updatedEntries: Int,
    val unchangedEntries: Int,
    val skippedEntries: Int,
    val failedEntries: Int,
)

internal data class CustomLyricsUpdateSummary(
    val checked: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

internal sealed interface CustomLyricsUpdateResult {
    val summary: CustomLyricsUpdateSummary

    data class Updated(
        val manifest: CustomLyricsManifest,
        override val summary: CustomLyricsUpdateSummary,
        val issues: List<CustomLyricsUpdateIssue> = emptyList(),
    ) : CustomLyricsUpdateResult {
        val checked: Int get() = summary.checked
        val updated: Int get() = summary.updated
        val unchanged: Int get() = summary.unchanged
        val skipped: Int get() = summary.skipped
        val failed: Int get() = summary.failed
    }

    data class Failed(
        val message: String,
        override val summary: CustomLyricsUpdateSummary = CustomLyricsUpdateSummary(),
        val issues: List<CustomLyricsUpdateIssue> = emptyList(),
    ) : CustomLyricsUpdateResult

    data object Cancelled : CustomLyricsUpdateResult {
        override val summary: CustomLyricsUpdateSummary = CustomLyricsUpdateSummary()
    }
}

/**
 * A source-independent update decision. The coordinator creates one item for
 * every local entry, including entries which are unchanged, skipped, or failed.
 * Only [Changed] items can cause a remote file write.
 */
internal sealed interface CustomLyricsUpdateItem {
    val appleMusicId: Long
    val source: String

    data class Changed(
        override val appleMusicId: Long,
        override val source: String,
        val bytes: ByteArray,
        /** Optional source to promote this entry to after the body is staged. */
        val replacementSource: String? = null,
    ) : CustomLyricsUpdateItem

    data class Unchanged(
        override val appleMusicId: Long,
        override val source: String,
    ) : CustomLyricsUpdateItem

    data class Skipped(
        override val appleMusicId: Long,
        override val source: String,
        val message: String,
    ) : CustomLyricsUpdateItem

    data class Failed(
        override val appleMusicId: Long,
        override val source: String,
        val kind: CustomLyricsUpdateFailureKind,
        val message: String,
    ) : CustomLyricsUpdateItem
}

/**
 * Applies a prepared batch atomically. Network work must happen before this
 * class is entered, so the only storage critical section is the final pointer
 * publication. The [isBaselineCurrent] callback is invoked after every new
 * body has been staged and immediately before publication; a concurrent edit
 * therefore fails closed without replacing the user's newer manifest.
 */
internal class CustomLyricsUpdateTransaction(
    private val fileIdFactory: () -> String,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishManifest: (CustomLyricsManifest) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
    private val isBaselineCurrent: () -> Boolean = { true },
) {
    fun apply(
        oldManifest: CustomLyricsManifest,
        items: List<CustomLyricsUpdateItem>,
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult {
        val safeOld = CustomLyricsManifestPolicy.sanitize(oldManifest)
        if (safeOld.entries.size != oldManifest.entries.size) {
            return CustomLyricsUpdateResult.Failed("本地歌词索引无效，无法更新")
        }
        val oldById = oldManifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
        if (oldById.size != oldManifest.entries.size ||
            items.size != oldManifest.entries.size ||
            items.map(CustomLyricsUpdateItem::appleMusicId).toSet() != oldById.keys
        ) {
            return CustomLyricsUpdateResult.Failed("歌词更新基线已变化，请重试")
        }

        val summary = summarize(items, oldManifest.entries.size)
        report(onProgress, summary, oldManifest.entries.size)
        if (isCancelledSafely(isCancelled)) return CustomLyricsUpdateResult.Cancelled

        val changed = items.filterIsInstance<CustomLyricsUpdateItem.Changed>()
        if (changed.isEmpty()) {
            // A read-only check should not rotate the index pointer.
            return CustomLyricsUpdateResult.Updated(
                manifest = oldManifest,
                summary = summary,
                issues = items.filterIsInstance<CustomLyricsUpdateItem.Failed>()
                    .map { it.issue() },
            )
        }

        val allocatedIds = oldManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        val writtenIds = mutableListOf<String>()
        val replacementEntries = mutableMapOf<Long, CustomLyricsEntry>()

        fun cleanupNewFiles() {
            writtenIds.forEach { fileId -> runCatching { deleteRemoteFile(fileId) } }
        }

        for (item in changed) {
            if (isCancelledSafely(isCancelled)) {
                cleanupNewFiles()
                return CustomLyricsUpdateResult.Cancelled
            }
            val inspected = CustomLyricsFilePolicy.inspect(item.bytes.toString(Charsets.UTF_8))
            val accepted = inspected as? CustomLyricsInspection.Accepted
                ?: run {
                    cleanupNewFiles()
                    return CustomLyricsUpdateResult.Failed(
                        "更新后的歌词无效",
                        summary,
                    )
                }
            val fileId = runCatching(fileIdFactory).getOrNull()
                ?.takeIf(CustomLyricsManifestPolicy::isValidFileId)
            if (fileId == null || !allocatedIds.add(fileId)) {
                cleanupNewFiles()
                return CustomLyricsUpdateResult.Failed("无法生成唯一歌词文件 ID", summary)
            }
            if (!runCatching { writeRemoteFile(fileId, accepted.bytes) }.getOrDefault(false)) {
                runCatching { deleteRemoteFile(fileId) }
                cleanupNewFiles()
                return CustomLyricsUpdateResult.Failed("无法写入共享歌词文件", summary)
            }
            writtenIds += fileId
            val previous = oldById.getValue(item.appleMusicId)
            replacementEntries[item.appleMusicId] = previous.copy(
                fileId = fileId,
                sizeBytes = accepted.bytes.size.toLong(),
                sha256 = accepted.sha256,
                source = item.replacementSource?.takeIf(String::isNotBlank) ?: previous.source,
                // displayName, enabled, ID and source intentionally remain
                // local state; remote catalogs are never allowed to overwrite
                // those fields unless this is an explicit source promotion.
            )
        }

        if (isCancelledSafely(isCancelled)) {
            cleanupNewFiles()
            return CustomLyricsUpdateResult.Cancelled
        }
        if (!runCatching { isBaselineCurrent() }.getOrDefault(false)) {
            cleanupNewFiles()
            return CustomLyricsUpdateResult.Failed(
                "歌词索引在更新期间已被修改，请重试",
                summary,
            )
        }

        val nextManifest = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(
                oldManifest.entries.map { entry ->
                    replacementEntries[entry.appleMusicId] ?: entry
                },
            ),
        )
        if (nextManifest.entries.size != oldManifest.entries.size ||
            nextManifest.entries.map(CustomLyricsEntry::appleMusicId) !=
                oldManifest.entries.map(CustomLyricsEntry::appleMusicId)
        ) {
            cleanupNewFiles()
            return CustomLyricsUpdateResult.Failed("更新后的歌词索引无效", summary)
        }
        if (!runCatching { publishManifest(nextManifest) }.getOrDefault(false)) {
            cleanupNewFiles()
            return CustomLyricsUpdateResult.Failed("无法发布歌词索引", summary)
        }

        val nextFileIds = nextManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        oldManifest.entries.map(CustomLyricsEntry::fileId)
            .distinct()
            .filterNot(nextFileIds::contains)
            .forEach { fileId -> runCatching { deleteRemoteFile(fileId) } }

        return CustomLyricsUpdateResult.Updated(
            manifest = nextManifest,
            summary = summary,
            issues = items.filterIsInstance<CustomLyricsUpdateItem.Failed>()
                .map { it.issue() },
        )
    }

    private fun summarize(
        items: List<CustomLyricsUpdateItem>,
        total: Int,
    ): CustomLyricsUpdateSummary = CustomLyricsUpdateSummary(
        checked = total,
        updated = items.count { it is CustomLyricsUpdateItem.Changed },
        unchanged = items.count { it is CustomLyricsUpdateItem.Unchanged },
        skipped = items.count { it is CustomLyricsUpdateItem.Skipped },
        failed = items.count { it is CustomLyricsUpdateItem.Failed },
    )

    private fun report(
        onProgress: (CustomLyricsUpdateProgress) -> Unit,
        summary: CustomLyricsUpdateSummary,
        total: Int,
    ) {
        runCatching {
            onProgress(
                CustomLyricsUpdateProgress(
                    checkedEntries = summary.checked,
                    totalEntries = total,
                    updatedEntries = summary.updated,
                    unchangedEntries = summary.unchanged,
                    skippedEntries = summary.skipped,
                    failedEntries = summary.failed,
                ),
            )
        }
    }

    private fun CustomLyricsUpdateItem.Failed.issue() = CustomLyricsUpdateIssue(
        appleMusicId = appleMusicId,
        source = source,
        kind = kind,
        message = message,
    )

    private fun isCancelledSafely(isCancelled: () -> Boolean): Boolean =
        runCatching { isCancelled() }.getOrDefault(false)
}
