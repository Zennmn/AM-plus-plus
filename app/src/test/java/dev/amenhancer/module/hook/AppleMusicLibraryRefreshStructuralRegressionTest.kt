package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural regression for the AMTool-style refresh contract: reentrancy
 * guard, token-scoped cancel, cooperative cancellation with late-batch
 * discard, 200ms/30s ready polling, ready-timeout-as-completed semantics,
 * batch-100 pagination with per-batch tolerance and independent degrade for
 * every backfill symbol. The assertions read the shipped sources so a future
 * rewrite that silently drops one of these behaviors fails the build.
 */
class AppleMusicLibraryRefreshStructuralRegressionTest {

    @Test
    fun `refresh responder keeps the native poll contract and ready polling`() {
        val target = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt")

        assertTrue(target.contains("update.invoke(library, updateReason)"))
        assertTrue(target.contains("UserInitiatedPoll"))
        assertTrue(target.contains("READY_POLL_INTERVAL_MILLIS = 200L"))
        assertTrue(target.contains("READY_TIMEOUT_MILLIS = 30_000L"))
        assertTrue(target.contains("SystemClock.sleep(READY_POLL_INTERVAL_MILLIS)"))
        assertTrue(target.contains("onSuccess"))
        assertTrue(target.contains("onError"))
        assertTrue(target.contains("RESULT_COMPLETED"))
    }

    @Test
    fun `refresh rejects a second concurrent request like AMTool`() {
        val target = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt")

        assertTrue(target.contains("activeTask.compareAndSet(null, task)"))
        assertTrue(target.contains("刷新和补查正在进行"))
        assertTrue(target.contains("AtomicReference<ActiveRefreshTask?>"))
    }

    @Test
    fun `cancel is token scoped and cooperative with late batch discard`() {
        val target = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt")
        val backfill = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogBackfill.kt")

        assertTrue(target.contains("LibraryRefreshProtocol.CANCEL_ACTION"))
        assertTrue(target.contains("if (task.token != token) return"))
        assertTrue(target.contains("generation.incrementAndGet()"))
        assertTrue(target.contains("cancellation()"))
        assertTrue(backfill.contains("shouldDiscardBatch"))
        assertTrue(backfill.contains("丢弃迟到批次响应"))
        assertTrue(backfill.contains("generation()"))
        assertTrue(target.contains("RESULT_CANCELLED"))
        assertTrue(target.contains("已停止刷新资料库"))
    }

    @Test
    fun `ready timeout completes with a skipped backfill instead of failing`() {
        val target = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt")

        assertTrue(target.contains("等待资料库就绪超时，已跳过批量补查"))
        assertTrue(target.contains("歌曲名显示修正未启用或目标语言不可用，已跳过批量补查"))
        assertFalse(target.contains("等待资料库就绪超时" + "并报告原生刷新失败"))
    }

    @Test
    fun `backfill batches at 100 with per batch tolerance and kept progress`() {
        val backfill = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogBackfill.kt")

        assertTrue(backfill.contains("CATALOG_BATCH_SIZE = 100"))
        assertTrue(backfill.contains("backfillBatchRanges"))
        assertTrue(backfill.contains("batchFailures"))
        assertTrue(backfill.contains("个批次失败已跳过"))
        assertTrue(backfill.contains("runCatching"))
    }

    @Test
    fun `backfill caps library items playlists and playlist tracks`() {
        val backfill = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogBackfill.kt")

        assertTrue(backfill.contains("MAX_LIBRARY_ITEMS = 50_000"))
        assertTrue(backfill.contains("MAX_PLAYLISTS = 100"))
        assertTrue(backfill.contains("MAX_PLAYLIST_TRACKS = 50_000"))
        assertTrue(backfill.contains("coerceAtMost(MAX_LIBRARY_ITEMS)"))
    }

    @Test
    fun `playlist path resolves contract verified and degrades independently`() {
        val backfill = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogBackfill.kt")

        assertTrue(backfill.contains("playlistItemsQuery"))
        assertTrue(backfill.contains("playlistBuilder"))
        assertTrue(backfill.contains("playlistTypeEnum"))
        assertTrue(backfill.contains("playlistDescriptorFactory"))
        assertTrue(backfill.contains("FAVORITES_PLAYLIST"))
        assertTrue(backfill.contains("EntityTypeContainer"))
        assertTrue(backfill.contains("播放列表枚举跳过"))
        assertTrue(backfill.contains("播放列表查询符号未确认"))
        assertTrue(backfill.contains("isSongsQueryMethod"))
    }

    @Test
    fun `backfill writes song album and artist caches from each batch`() {
        val backfill = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogBackfill.kt")
        val invoker = projectFile("app/src/main/java/dev/amenhancer/module/hook/MediaApiRepositoryCatalogInvoker.kt")

        assertTrue(backfill.contains("captureCatalogMetadataForId"))
        assertTrue(backfill.contains("CatalogEntityLookup"))
        assertTrue(invoker.contains("getData"))
        assertTrue(invoker.contains("MediaApiRepositoryGetEntitiesWithIdsInvocationMethod"))
        assertTrue(invoker.contains("repositoryInstance"))
    }

    @Test
    fun `requester broadcasts the token scoped cancel`() {
        val requester = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/LibraryRefreshRequester.kt",
        )
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/CurrentSongIdentityProtocol.kt",
        )

        assertTrue(requester.contains("CANCEL_ACTION"))
        assertTrue(requester.contains("EXTRA_REQUEST_TOKEN"))
        assertTrue(requester.contains("setPackage(ModuleConstants.TARGET_PACKAGE)"))
        assertTrue(protocol.contains("CANCEL_ACTION"))
        assertTrue(protocol.contains("RESULT_CANCELLED = 4"))
        assertTrue(protocol.contains("RESULT_TRIGGERED = RESULT_STARTED"))
    }

    @Test
    fun `embedded refresh receiver can stay same process and non exported`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt",
        )
        val adaptation = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt",
        )
        val installation = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt",
        )

        assertTrue(target.contains("useRequestPermission"))
        assertTrue(target.contains("Context.RECEIVER_NOT_EXPORTED"))
        assertTrue(target.contains("requestPermission == null"))
        assertTrue(adaptation.contains("useLibraryRefreshPermission: Boolean = true"))
        assertTrue(adaptation.contains("useRequestPermission = useLibraryRefreshPermission"))
        assertTrue(installation.contains("useLibraryRefreshPermission = false"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
