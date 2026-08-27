package io.github.proify.lyricon.amprovider.xposed.metadata

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataRefreshIntent
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataRefreshKind
import io.github.proify.lyricon.amprovider.xposed.AppleInAppMetadataRefreshQueue
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataRefreshFrameStats
import io.github.proify.lyricon.amprovider.xposed.InAppOriginalResolutionMode
import io.github.proify.lyricon.amprovider.xposed.MetadataFrameScheduler
import io.github.proify.lyricon.amprovider.xposed.enqueueAction
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInAppMetadataRefreshQueueTest {
    private class Harness(
        frameBudgetNanos: Long = 4_000_000L,
    ) {
        val mainCallbacks = ArrayDeque<() -> Unit>()
        val frameCallbacks = ArrayDeque<() -> Unit>()
        var now = 0L
        var lastStats: AppleMetadataRefreshFrameStats? = null
        val queue = AppleInAppMetadataRefreshQueue(
            postToMain = { mainCallbacks.addLast(it) },
            frameScheduler = MetadataFrameScheduler { frameCallbacks.addLast(it) },
            diagnostics = { lastStats = it },
            nowNanos = { now },
            frameBudgetNanos = frameBudgetNanos,
        )

        fun deliverFrame() {
            assertEquals(1, mainCallbacks.size)
            mainCallbacks.removeFirst().invoke()
            assertEquals(1, frameCallbacks.size)
            frameCallbacks.removeFirst().invoke()
        }
    }

    @Test
    fun `same resolution frame merges ids and keeps strongest priority and mode`() {
        val harness = Harness()
        var mergedIntent: AppleMetadataRefreshIntent? = null
        harness.queue.enqueue(
            AppleMetadataRefreshIntent(
                kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
                mediaIds = setOf(" one "),
                priority = AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
                action = { mergedIntent = it },
            ),
        )
        harness.queue.enqueue(
            AppleMetadataRefreshIntent(
                kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
                mediaIds = setOf("two"),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                action = { mergedIntent = it },
            ),
        )

        assertEquals(1, harness.mainCallbacks.size)
        harness.deliverFrame()
        val stats = requireNotNull(harness.lastStats)
        assertEquals(1, stats.executed)
        assertEquals(1, stats.enqueued)
        assertEquals(1, stats.merged)
        assertEquals(0, stats.deferred)
        assertEquals(false, stats.overBudget)
        assertEquals(
            setOf("one", "two"),
            mergedIntent?.mediaIds,
        )
        assertEquals(
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            mergedIntent?.priority,
        )
        assertEquals(
            InAppOriginalResolutionMode.ORIGINAL_FIRST,
            mergedIntent?.originalResolutionMode,
        )
    }

    @Test
    fun `target identity is part of the coalescing key`() {
        val harness = Harness()
        val target = Any()
        val otherTarget = Any()
        val executions = AtomicInteger()
        fun enqueue(target: Any) {
            harness.queue.enqueueAction(
                kind = AppleMetadataRefreshKind.DATA_BINDING_REBIND,
                mediaId = "song",
                target = target,
                action = { executions.incrementAndGet() },
            )
        }
        enqueue(target)
        enqueue(target)
        enqueue(otherTarget)

        harness.mainCallbacks.removeFirst().invoke()
        assertEquals(1, harness.frameCallbacks.size)
        harness.frameCallbacks.removeFirst().invoke()
        assertEquals(2, executions.get())
    }

    @Test
    fun `enqueue during drain is delivered on a later frame`() {
        val harness = Harness()
        val executions = AtomicInteger()
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
            mediaIds = listOf("first"),
        ) {
            executions.incrementAndGet()
            harness.queue.enqueueAction(
                kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
                mediaIds = listOf("second"),
                action = { executions.incrementAndGet() },
            )
        }

        harness.deliverFrame()
        assertEquals(1, executions.get())
        assertEquals(1, requireNotNull(harness.lastStats).deferred)
        assertEquals(false, requireNotNull(harness.lastStats).overBudget)
        assertEquals(1, harness.mainCallbacks.size)
        harness.deliverFrame()
        assertEquals(2, executions.get())
        assertEquals(0, requireNotNull(harness.lastStats).deferred)
    }

    @Test
    fun `failed intent is isolated and counted`() {
        val harness = Harness()
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.RECENT_SEARCH_BINDING,
            mediaId = "song",
        ) { error("expected test failure") }

        harness.deliverFrame()
        val stats = requireNotNull(harness.lastStats)
        assertEquals(1, stats.failed)
        assertEquals(0, stats.deferred)
        assertEquals(false, stats.overBudget)
        assertTrue(stats.durationNanos >= 0L)
    }

    @Test
    fun `frame budget defers remaining work and next frame drains it`() {
        val harness = Harness(frameBudgetNanos = 5L)
        val executions = mutableListOf<Int>()
        repeat(3) { index ->
            harness.queue.enqueueAction(
                kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
                mediaId = "song-$index",
            ) {
                executions += index
                if (index == 0) harness.now += 6L
            }
        }

        harness.deliverFrame()

        assertEquals(listOf(0), executions)
        val firstStats = requireNotNull(harness.lastStats)
        assertEquals(1, firstStats.executed)
        assertEquals(2, firstStats.deferred)
        assertEquals(true, firstStats.overBudget)
        assertEquals(1, harness.mainCallbacks.size)

        harness.deliverFrame()

        assertEquals(listOf(0, 1, 2), executions)
        val secondStats = requireNotNull(harness.lastStats)
        assertEquals(2, secondStats.executed)
        assertEquals(0, secondStats.deferred)
        assertEquals(false, secondStats.overBudget)
    }

    @Test
    fun `deferred work coalesces with a newer intent before the next frame`() {
        val harness = Harness(frameBudgetNanos = 5L)
        val executions = mutableListOf<String>()
        val target = Any()
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
            mediaId = "first",
        ) {
            harness.now += 6L
            executions += "first"
        }
        harness.queue.enqueue(
            AppleMetadataRefreshIntent(
                kind = AppleMetadataRefreshKind.DATA_BINDING_REBIND,
                mediaId = "deferred",
                target = target,
                generation = 1L,
                mediaIds = setOf("old"),
                action = { intent -> executions += "old:${intent.mediaIds}" },
            ),
        )

        harness.deliverFrame()
        assertEquals(listOf("first"), executions)
        assertEquals(1, requireNotNull(harness.lastStats).deferred)

        harness.queue.enqueue(
            AppleMetadataRefreshIntent(
                kind = AppleMetadataRefreshKind.DATA_BINDING_REBIND,
                mediaId = " deferred ",
                target = target,
                generation = 2L,
                mediaIds = setOf("new"),
                action = { intent -> executions += "new:${intent.mediaIds}" },
            ),
        )

        harness.deliverFrame()

        assertEquals(
            listOf("first", "new:[old, new]"),
            executions,
        )
        val stats = requireNotNull(harness.lastStats)
        assertEquals(0, stats.enqueued)
        assertEquals(1, stats.merged)
        assertEquals(1, stats.executed)
        assertEquals(0, stats.deferred)
    }

    @Test
    fun `one deferred intent runs before newer higher priority work`() {
        val harness = Harness(frameBudgetNanos = 5L)
        val executions = mutableListOf<String>()
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
            mediaId = "first-visible",
        ) {
            executions += "first-visible"
            harness.now += 6L
        }
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.GENERIC_RECYCLER_NOTIFY,
            mediaId = "deferred-refresh",
        ) {
            executions += "deferred-refresh"
            harness.now += 6L
        }

        harness.deliverFrame()
        assertEquals(listOf("first-visible"), executions)

        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
            mediaId = "new-visible",
        ) { executions += "new-visible" }

        harness.deliverFrame()

        assertEquals(listOf("first-visible", "deferred-refresh"), executions)
        assertEquals(1, requireNotNull(harness.lastStats).deferred)
        harness.deliverFrame()
        assertEquals(
            listOf("first-visible", "deferred-refresh", "new-visible"),
            executions,
        )
    }

    @Test
    fun `clear during a drain discards its deferred tail but keeps newer work`() {
        val harness = Harness(frameBudgetNanos = 5L)
        val executions = mutableListOf<String>()
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
            mediaId = "running",
        ) {
            executions += "running"
            harness.now += 6L
            harness.queue.clearPending()
            harness.queue.enqueueAction(
                kind = AppleMetadataRefreshKind.VISIBLE_RESOLUTION,
                mediaId = "fresh",
            ) { executions += "fresh" }
        }
        harness.queue.enqueueAction(
            kind = AppleMetadataRefreshKind.GENERIC_RECYCLER_NOTIFY,
            mediaId = "discarded",
        ) { executions += "discarded" }

        harness.deliverFrame()

        assertEquals(listOf("running"), executions)
        assertEquals(1, requireNotNull(harness.lastStats).deferred)
        harness.deliverFrame()
        assertEquals(listOf("running", "fresh"), executions)
    }
}
