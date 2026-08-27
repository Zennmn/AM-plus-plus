package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.AmLyricsClient
import dev.amenhancer.module.hook.AmLyricsIndex
import dev.amenhancer.module.hook.AmLyricsIndexEntry
import dev.amenhancer.module.hook.LunabeatCatalog
import dev.amenhancer.module.hook.LunabeatClient
import dev.amenhancer.module.hook.LunabeatSong
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Network seams used by [CustomLyricsUpdateCoordinator]. */
internal data class CustomLyricsUpdateSources(
    val fetchAmll: (Long) -> String?,
    val loadAmLyricsIndex: () -> AmLyricsIndex?,
    val fetchAmLyricsTtml: (AmLyricsIndexEntry) -> String?,
    val loadLunabeatCatalog: () -> LunabeatCatalog?,
    val fetchLunabeatTtml: (LunabeatSong) -> String?,
)

/**
 * Resolves authoritative source metadata and prepares a source-independent
 * batch for [CustomLyricsUpdateTransaction]. No Android APIs are used here;
 * callers provide all network and storage seams explicitly.
 */
internal class CustomLyricsUpdateCoordinator(
    private val sources: CustomLyricsUpdateSources,
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
) {
    init {
        require(maxConcurrency in MIN_CONCURRENCY..MAX_CONCURRENCY)
    }

    fun update(
        oldManifest: CustomLyricsManifest,
        fileIdFactory: () -> String,
        writeRemoteFile: (String, ByteArray) -> Boolean,
        publishManifest: (CustomLyricsManifest) -> Boolean,
        deleteRemoteFile: (String) -> Unit,
        isBaselineCurrent: () -> Boolean = { true },
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult {
        val safeOld = dev.amenhancer.module.config.CustomLyricsManifestPolicy.sanitize(oldManifest)
        if (safeOld.entries.size != oldManifest.entries.size) {
            return CustomLyricsUpdateResult.Failed("本地歌词索引无效，无法更新")
        }
        if (oldManifest.entries.isEmpty()) {
            return CustomLyricsUpdateResult.Updated(
                manifest = oldManifest,
                summary = CustomLyricsUpdateSummary(),
            )
        }

        val decisions = linkedMapOf<Long, CustomLyricsUpdateItem>()
        fun report() {
            val summary = summarize(decisions.values, oldManifest.entries.size)
            runCatching {
                onProgress(
                    CustomLyricsUpdateProgress(
                        checkedEntries = decisions.size,
                        totalEntries = oldManifest.entries.size,
                        updatedEntries = summary.updated,
                        unchangedEntries = summary.unchanged,
                        skippedEntries = summary.skipped,
                        failedEntries = summary.failed,
                    ),
                )
            }
        }
        fun record(entry: CustomLyricsEntry, item: CustomLyricsUpdateItem) {
            decisions[entry.appleMusicId] = item
            report()
        }
        fun cancelled(): Boolean = runCatching { isCancelled() }.getOrDefault(false)

        // Manual TTML and AUTO_CACHE have no authoritative remote source.
        oldManifest.entries.forEach { entry ->
            if (entry.source == CustomLyricsSources.MANUAL ||
                entry.source == CustomLyricsSources.AUTO_CACHE
            ) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Skipped(
                        appleMusicId = entry.appleMusicId,
                        source = entry.source,
                        message = "没有可验证的远程来源",
                    ),
                )
            }
        }
        if (cancelled()) return CustomLyricsUpdateResult.Cancelled

        updateAmll(oldManifest, decisions, ::record, ::cancelled)
            ?.let { return it }
        if (cancelled()) return CustomLyricsUpdateResult.Cancelled

        updateAmLyrics(oldManifest, decisions, ::record, ::cancelled)
            ?.let { return it }
        if (cancelled()) return CustomLyricsUpdateResult.Cancelled

        updateLunabeat(oldManifest, decisions, ::record, ::cancelled)
            ?.let { return it }
        if (cancelled()) return CustomLyricsUpdateResult.Cancelled

        // A sanitized manifest normally contains only known sources. Keep an
        // unexpected source fail-open, just as a removed provider is treated
        // as manually managed by the manifest policy.
        oldManifest.entries.forEach { entry ->
            if (entry.appleMusicId !in decisions) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Skipped(
                        appleMusicId = entry.appleMusicId,
                        source = entry.source,
                        message = "来源不支持自动更新",
                    ),
                )
            }
        }
        if (decisions.size != oldManifest.entries.size) {
            return CustomLyricsUpdateResult.Failed("歌词更新未能检查全部条目")
        }

        return CustomLyricsUpdateTransaction(
            fileIdFactory = fileIdFactory,
            writeRemoteFile = writeRemoteFile,
            publishManifest = publishManifest,
            deleteRemoteFile = deleteRemoteFile,
            isBaselineCurrent = isBaselineCurrent,
        ).apply(
            oldManifest = oldManifest,
            items = oldManifest.entries.map { decisions.getValue(it.appleMusicId) },
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }

    private fun updateAmll(
        manifest: CustomLyricsManifest,
        decisions: MutableMap<Long, CustomLyricsUpdateItem>,
        record: (CustomLyricsEntry, CustomLyricsUpdateItem) -> Unit,
        cancelled: () -> Boolean,
    ): CustomLyricsUpdateResult? {
        val entries = manifest.entries.filter { it.source == CustomLyricsSources.AMLL }
        if (entries.isEmpty()) return null
        val entriesById = entries.associateBy(CustomLyricsEntry::appleMusicId)
        val completed = parallelForEach(
            keys = entries.map(CustomLyricsEntry::appleMusicId),
            fetch = sources.fetchAmll,
            cancelled = cancelled,
        ) { id, raw ->
            val entry = entriesById.getValue(id)
            val ttml = raw?.let {
                runCatching { AmllTtmlFormatConverter.toAppleFormat(it).ttml }.getOrNull()
            }
            if (ttml == null) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.NETWORK,
                        "AMLL 歌词不存在或读取失败",
                    ),
                )
            } else {
                record(entry, compareTtml(entry, ttml))
            }
        }
        if (!completed) return CustomLyricsUpdateResult.Cancelled
        return null
    }

    private fun updateAmLyrics(
        manifest: CustomLyricsManifest,
        decisions: MutableMap<Long, CustomLyricsUpdateItem>,
        record: (CustomLyricsEntry, CustomLyricsUpdateItem) -> Unit,
        cancelled: () -> Boolean,
    ): CustomLyricsUpdateResult? {
        val entries = manifest.entries.filter { it.source == CustomLyricsSources.AM_LYRICS }
        if (entries.isEmpty()) return null
        val index = runCatching { sources.loadAmLyricsIndex() }.getOrNull()
        if (index == null) {
            entries.forEach { entry ->
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.NETWORK,
                        "AM-Lyrics 索引读取失败",
                    ),
                )
            }
            return null
        }
        val changed = linkedMapOf<String, MutableList<Pair<CustomLyricsEntry, AmLyricsIndexEntry>>>()
        entries.forEach { entry ->
            if (cancelled()) return CustomLyricsUpdateResult.Cancelled
            val remote = index.entryFor(entry.appleMusicId)
            if (remote == null) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.SOURCE_MISSING,
                        "AM-Lyrics 未找到对应歌曲",
                    ),
                )
            } else if (!remote.enabled) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.SOURCE_MISSING,
                        "AM-Lyrics 条目已禁用",
                    ),
                )
            } else if (
                remote.sizeBytes == entry.sizeBytes &&
                remote.sha256.equals(entry.sha256, ignoreCase = true)
            ) {
                // The remote index is authoritative for this source; avoid
                // opening the local or remote TTML on the fast path.
                record(entry, CustomLyricsUpdateItem.Unchanged(entry.appleMusicId, entry.source))
            } else {
                changed.getOrPut(remote.path) { mutableListOf() } += entry to remote
            }
        }
        val completed = parallelForEach(
            keys = changed.keys.toList(),
            fetch = { path ->
                val remote = changed.getValue(path).first().second
                sources.fetchAmLyricsTtml(remote)
            },
            cancelled = cancelled,
        ) { path, ttml ->
            val localEntries = changed.getValue(path)
            localEntries.forEach { (entry, _) ->
                if (ttml == null) {
                    record(
                        entry,
                        CustomLyricsUpdateItem.Failed(
                            entry.appleMusicId,
                            entry.source,
                            CustomLyricsUpdateFailureKind.NETWORK,
                            "AM-Lyrics 歌词下载失败或校验失败",
                        ),
                    )
                } else {
                    record(entry, compareTtml(entry, ttml))
                }
            }
        }
        if (!completed) return CustomLyricsUpdateResult.Cancelled
        return null
    }

    private fun updateLunabeat(
        manifest: CustomLyricsManifest,
        decisions: MutableMap<Long, CustomLyricsUpdateItem>,
        record: (CustomLyricsEntry, CustomLyricsUpdateItem) -> Unit,
        cancelled: () -> Boolean,
    ): CustomLyricsUpdateResult? {
        val entries = manifest.entries.filter { it.source == CustomLyricsSources.LUNABEAT }
        if (entries.isEmpty()) return null
        val catalog = runCatching { sources.loadLunabeatCatalog() }.getOrNull()
        if (catalog == null) {
            entries.forEach { entry ->
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.NETWORK,
                        "Lunabeat catalog 读取失败",
                    ),
                )
            }
            return null
        }
        val changed = linkedMapOf<String, MutableList<Pair<CustomLyricsEntry, LunabeatSong>>>()
        entries.forEach { entry ->
            if (cancelled()) return CustomLyricsUpdateResult.Cancelled
            val song = catalog.entryFor(entry.appleMusicId)
            if (song == null) {
                record(
                    entry,
                    CustomLyricsUpdateItem.Failed(
                        entry.appleMusicId,
                        entry.source,
                        CustomLyricsUpdateFailureKind.SOURCE_MISSING,
                        "Lunabeat 未找到对应歌曲",
                    ),
                )
            } else if (song.sha256.equals(entry.sha256, ignoreCase = true)) {
                record(entry, CustomLyricsUpdateItem.Unchanged(entry.appleMusicId, entry.source))
            } else {
                // A path can be shared by alternate IDs in a catalog. Fetch it
                // once and fan the result out to each local mapping.
                changed.getOrPut(song.path) { mutableListOf() } += entry to song
            }
        }
        val completed = parallelForEach(
            keys = changed.keys.toList(),
            fetch = { path -> sources.fetchLunabeatTtml(changed.getValue(path).first().second) },
            cancelled = cancelled,
        ) { path, ttml ->
            val localEntries = changed.getValue(path)
            localEntries.forEach { (entry, _) ->
                if (ttml == null) {
                    record(
                        entry,
                        CustomLyricsUpdateItem.Failed(
                            entry.appleMusicId,
                            entry.source,
                            CustomLyricsUpdateFailureKind.NETWORK,
                            "Lunabeat 歌词下载失败或校验失败",
                        ),
                    )
                } else {
                    record(entry, compareTtml(entry, ttml))
                }
            }
        }
        if (!completed) return CustomLyricsUpdateResult.Cancelled
        return null
    }

    private fun compareTtml(entry: CustomLyricsEntry, ttml: String): CustomLyricsUpdateItem {
        val inspected = CustomLyricsFilePolicy.inspect(ttml)
        if (inspected is CustomLyricsInspection.Rejected) {
            return CustomLyricsUpdateItem.Failed(
                entry.appleMusicId,
                entry.source,
                CustomLyricsUpdateFailureKind.INVALID_TTML,
                "远程歌词不是有效 TTML",
            )
        }
        inspected as CustomLyricsInspection.Accepted
        return if (
            inspected.bytes.size.toLong() == entry.sizeBytes &&
            inspected.sha256.equals(entry.sha256, ignoreCase = true)
        ) {
            CustomLyricsUpdateItem.Unchanged(entry.appleMusicId, entry.source)
        } else {
            CustomLyricsUpdateItem.Changed(entry.appleMusicId, entry.source, inspected.bytes)
        }
    }

    /** Returns false only when cancellation interrupted the batch. */
    private fun <K> parallelForEach(
        keys: List<K>,
        fetch: (K) -> String?,
        cancelled: () -> Boolean,
        onResult: (K, String?) -> Unit,
    ): Boolean {
        if (keys.isEmpty()) return true
        val executor = newExecutor()
        val futures = linkedMapOf<K, Future<String?>>()
        try {
            var offset = 0
            while (offset < keys.size) {
                if (cancelled()) return false
                futures.clear()
                val end = minOf(offset + MAX_FETCH_BATCH, keys.size)
                for (index in offset until end) {
                    val key = keys[index]
                    futures[key] = executor.submit<String?> {
                        if (cancelled()) null else runCatching { fetch(key) }.getOrNull()
                    }
                }
                futures.forEach { (key, future) ->
                    while (true) {
                        if (cancelled()) return false
                        try {
                            onResult(key, future.get(FUTURE_POLL_MS, TimeUnit.MILLISECONDS))
                            break
                        } catch (_: java.util.concurrent.TimeoutException) {
                            // Poll cancellation while a bounded HTTP read is in flight.
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        } catch (_: ExecutionException) {
                            onResult(key, null)
                            break
                        }
                    }
                }
                offset = end
            }
            return true
        } finally {
            if (cancelled()) futures.values.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun newExecutor(): ExecutorService = Executors.newFixedThreadPool(maxConcurrency) { runnable ->
        Thread(runnable, "ampp-lyrics-update").apply { isDaemon = true }
    }

    private fun summarize(
        items: Collection<CustomLyricsUpdateItem>,
        total: Int,
    ): CustomLyricsUpdateSummary = CustomLyricsUpdateSummary(
        checked = items.size.coerceAtMost(total),
        updated = items.count { it is CustomLyricsUpdateItem.Changed },
        unchanged = items.count { it is CustomLyricsUpdateItem.Unchanged },
        skipped = items.count { it is CustomLyricsUpdateItem.Skipped },
        failed = items.count { it is CustomLyricsUpdateItem.Failed },
    )

    companion object {
        const val MIN_CONCURRENCY = 4
        const val MAX_CONCURRENCY = 6
        const val DEFAULT_CONCURRENCY = 4
        private const val MAX_FETCH_BATCH = 32
        private const val FUTURE_POLL_MS = 100L

        fun fromClients(
            amll: dev.amenhancer.module.hook.AmllTtmlClient,
            amLyrics: AmLyricsClient,
            lunabeat: LunabeatClient,
            maxConcurrency: Int = DEFAULT_CONCURRENCY,
        ): CustomLyricsUpdateCoordinator = CustomLyricsUpdateCoordinator(
            sources = CustomLyricsUpdateSources(
                fetchAmll = amll::fetch,
                loadAmLyricsIndex = amLyrics::fetchIndex,
                fetchAmLyricsTtml = amLyrics::fetchTtml,
                loadLunabeatCatalog = lunabeat::loadCatalog,
                fetchLunabeatTtml = lunabeat::fetch,
            ),
            maxConcurrency = maxConcurrency,
        )
    }
}
