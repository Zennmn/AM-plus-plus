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
    private class Harness {
        val mainCallbacks = ArrayDeque<() -> Unit>()
        val frameCallbacks = ArrayDeque<() -> Unit>()
        var now = 0L
        var lastStats: AppleMetadataRefreshFrameStats? = null
        val queue = AppleInAppMetadataRefreshQueue(
            postToMain = { mainCallbacks.addLast(it) },
            frameScheduler = MetadataFrameScheduler { frameCallbacks.addLast(it) },
            diagnostics = { lastStats = it },
            nowNanos = { ++now },
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
        assertEquals(1, harness.mainCallbacks.size)
        harness.deliverFrame()
        assertEquals(2, executions.get())
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
        assertTrue(stats.durationNanos >= 0L)
    }
}
