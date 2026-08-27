/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.view.Choreographer

/**
 * The kinds of work that are safe to coalesce until the next UI frame.
 * Playback/MediaSession work deliberately does not use this queue.
 */
internal enum class AppleMetadataRefreshKind {
    VISIBLE_RESOLUTION,
    DATA_BINDING_REBIND,
    GENERIC_RECYCLER_NOTIFY,
    LIBRARY_CONTROLLER_REBIND,
    LIBRARY_COMPOSE_REBIND,
    LISTEN_NOW_REBIND,
    COLLECTION_PAGE_RESOLUTION,
    ARTIST_BINDING,
    RECENT_SEARCH_BINDING,
}

/** A small seam that lets JVM tests drive frame delivery without Android's Choreographer. */
internal fun interface MetadataFrameScheduler {
    fun postFrame(callback: () -> Unit)
}

/**
 * Typed request passed through the frame queue. The pending map is detached before callbacks run;
 * work that does not fit the current frame is requeued for a later callback. The queue validates
 * the captured page generation immediately before invoking each action; callers may still apply
 * their own binding/visibility checks for finer-grained safety.
 */
internal data class AppleMetadataRefreshIntent(
    val kind: AppleMetadataRefreshKind,
    val mediaId: String? = null,
    val mediaIds: Set<String> = emptySet(),
    val priority: AppleInternalCatalogResolver.RequestPriority =
        AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    val originalResolutionMode: InAppOriginalResolutionMode =
        InAppOriginalResolutionMode.AFTER_LOCALIZED,
    val target: Any? = null,
    val slot: Int = -1,
    val generation: Long = 0L,
    val alias: AppleInternalCatalogResolver.Alias? = null,
    val action: (AppleMetadataRefreshIntent) -> Unit,
)

internal data class AppleMetadataRefreshFrameStats(
    val durationNanos: Long,
    val enqueued: Int,
    val merged: Int,
    val executed: Int,
    val failed: Int,
    val maxDepth: Int,
    /** Number of intents left for a later frame, including work enqueued while draining. */
    val deferred: Int = 0,
    /** Whether this frame hit the work budget or an action itself overran it. */
    val overBudget: Boolean = false,
)

internal fun AppleInAppMetadataRefreshQueue.enqueueAction(
    kind: AppleMetadataRefreshKind,
    mediaId: String? = null,
    mediaIds: Collection<String> = emptyList(),
    priority: AppleInternalCatalogResolver.RequestPriority =
        AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    originalResolutionMode: InAppOriginalResolutionMode =
        InAppOriginalResolutionMode.AFTER_LOCALIZED,
    target: Any? = null,
    slot: Int = -1,
    generation: Long = 0L,
    alias: AppleInternalCatalogResolver.Alias? = null,
    action: () -> Unit,
) {
    enqueue(
        AppleMetadataRefreshIntent(
            kind = kind,
            mediaId = mediaId,
            mediaIds = mediaIds.toSet(),
            priority = priority,
            originalResolutionMode = originalResolutionMode,
            target = target,
            slot = slot,
            generation = generation,
            alias = alias,
            action = { action() },
        ),
    )
}

/**
 * Coalesces visible metadata work into one Choreographer callback per frame.  The module keeps
 * the Android scheduling details behind [MetadataFrameScheduler], so the merge policy is fully
 * testable without a device.
 */
internal class AppleInAppMetadataRefreshQueue(
    private val postToMain: ((() -> Unit) -> Unit),
    private val frameScheduler: MetadataFrameScheduler = MetadataFrameScheduler { callback ->
        Choreographer.getInstance().postFrameCallback { callback() }
    },
    private val diagnostics: ((AppleMetadataRefreshFrameStats) -> Unit)? = null,
    private val nowNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val frameBudgetNanos: Long = DEFAULT_FRAME_BUDGET_NANOS,
) {
    private class IdentityKey(
        val kind: AppleMetadataRefreshKind,
        val mediaId: String?,
        val target: Any?,
        val slot: Int,
    ) {
        override fun equals(other: Any?): Boolean = other is IdentityKey &&
            kind == other.kind &&
            mediaId == other.mediaId &&
            target === other.target &&
            slot == other.slot

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + (mediaId?.hashCode() ?: 0)
            result = 31 * result + System.identityHashCode(target)
            result = 31 * result + slot
            return result
        }
    }

    private class Pending(
        var intent: AppleMetadataRefreshIntent,
        /** An intent which already yielded to a previous frame gets one fair turn next frame. */
        val deferred: Boolean = false,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<IdentityKey, Pending>()
    private var frameScheduled = false
    private var draining = false
    private var nextFrameToken = 0L
    private var scheduledFrameToken = 0L
    private var nextEnqueued = 0
    private var nextMerged = 0
    private var maxDepth = 0
    private var clearEpoch = 0L

    init {
        require(frameBudgetNanos >= 0L) { "frameBudgetNanos must be non-negative" }
    }

    fun enqueue(intent: AppleMetadataRefreshIntent) {
        val normalized = normalize(intent)
        val key = identityKey(normalized)
        var shouldSchedule = false
        var frameToken = 0L
        synchronized(lock) {
            val existing = pending[key]
            if (existing == null) {
                pending[key] = Pending(normalized)
                nextEnqueued += 1
                maxDepth = maxOf(maxDepth, pending.size)
            } else {
                existing.intent = mergeIntents(existing.intent, normalized)
                nextMerged += 1
            }
            if (!frameScheduled && !draining) {
                frameScheduled = true
                frameToken = ++nextFrameToken
                scheduledFrameToken = frameToken
                shouldSchedule = true
            }
        }
        if (shouldSchedule) {
            scheduleFrame(frameToken)
        }
    }

    /** Drain synchronously for unit tests; production code is driven by [MetadataFrameScheduler]. */
    internal fun drainNowForTests(): AppleMetadataRefreshFrameStats = drain(null)

    internal fun pendingSizeForTests(): Int = synchronized(lock) { pending.size }

    internal fun clearPending() {
        synchronized(lock) {
            pending.clear()
            clearEpoch += 1
            frameScheduled = false
            scheduledFrameToken = 0L
            nextEnqueued = 0
            nextMerged = 0
            maxDepth = 0
        }
    }

    private fun normalize(intent: AppleMetadataRefreshIntent): AppleMetadataRefreshIntent = intent.copy(
        mediaId = intent.mediaId?.trim()?.takeIf(String::isNotEmpty),
        mediaIds = intent.mediaIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet(),
    )

    private fun identityKey(intent: AppleMetadataRefreshIntent): IdentityKey = IdentityKey(
        kind = intent.kind,
        mediaId = intent.mediaId,
        target = intent.target,
        slot = intent.slot,
    )

    /** Merge [incoming] into [previous], preserving the latest callback and strongest metadata. */
    private fun mergeIntents(
        previous: AppleMetadataRefreshIntent,
        incoming: AppleMetadataRefreshIntent,
    ): AppleMetadataRefreshIntent = previous.copy(
        mediaIds = previous.mediaIds + incoming.mediaIds,
        priority = higherPriority(previous.priority, incoming.priority),
        originalResolutionMode = higherResolutionMode(
            previous.originalResolutionMode,
            incoming.originalResolutionMode,
        ),
        generation = maxOf(previous.generation, incoming.generation),
        alias = incoming.alias ?: previous.alias,
        action = incoming.action,
    )

    /**
     * Reinsert work which did not fit in this frame.  A callback enqueued while the frame was
     * draining is newer than deferred work, so deferred intents are merged as the base and the
     * already-pending intent is merged on top of it.
     */
    private fun requeueDeferredLocked(intents: List<AppleMetadataRefreshIntent>) {
        if (intents.isEmpty()) return
        // Deferred work gets one fair turn before newly arriving higher-priority work. Remaining
        // work still uses the regular kind order, so a busy visible-resolution stream cannot
        // starve an already-deferred binding or Recycler refresh indefinitely.
        val newer = LinkedHashMap(pending)
        pending.clear()
        intents.forEach { deferred ->
            val key = identityKey(deferred)
            val existingNewer = newer.remove(key)
            if (existingNewer == null) {
                pending[key] = Pending(deferred, deferred = true)
            } else {
                pending[key] = Pending(
                    intent = mergeIntents(deferred, existingNewer.intent),
                    deferred = true,
                )
                // The pending intent arrived while this frame was draining and belongs to the
                // next frame's merge accounting, just like any other re-entrant enqueue.
                nextMerged += 1
            }
            maxDepth = maxOf(maxDepth, pending.size)
        }
        newer.forEach { (key, value) ->
            pending[key] = value
            maxDepth = maxOf(maxDepth, pending.size)
        }
    }

    /**
     * Preserve kind priority for fresh work while promoting exactly one older deferred intent.
     * This gives each budget-yielded UI mutation bounded progress without turning every following
     * frame into a full oldest-first drain.
     */
    private fun nextFrameBatchLocked(): List<AppleMetadataRefreshIntent> {
        val oldestDeferred = pending.values.firstOrNull { it.deferred }
        return buildList {
            oldestDeferred?.let { add(it.intent) }
            pending.values
                .asSequence()
                .filter { it !== oldestDeferred }
                .sortedBy { it.intent.kind.order }
                .forEach { add(it.intent) }
        }
    }

    private fun scheduleFrame(frameToken: Long) {
        val dispatch: () -> Unit = dispatch@{
            val stillScheduled = synchronized(lock) {
                frameScheduled && scheduledFrameToken == frameToken
            }
            if (!stillScheduled) return@dispatch
            try {
                frameScheduler.postFrame { drain(frameToken) }
            } catch (_: Throwable) {
                clearFailedSchedule(frameToken)
            }
        }
        try {
            postToMain(dispatch)
        } catch (_: Throwable) {
            clearFailedSchedule(frameToken)
        }
    }

    private fun clearFailedSchedule(frameToken: Long) {
        synchronized(lock) {
            if (scheduledFrameToken == frameToken) {
                frameScheduled = false
                scheduledFrameToken = 0L
            }
        }
    }

    private fun elapsedSince(startedAt: Long): Long =
        (nowNanos() - startedAt).takeIf { it >= 0L } ?: 0L

    private fun emptyStats(startedAt: Long): AppleMetadataRefreshFrameStats =
        AppleMetadataRefreshFrameStats(
            durationNanos = elapsedSince(startedAt),
            enqueued = 0,
            merged = 0,
            executed = 0,
            failed = 0,
            maxDepth = 0,
        )

    private fun drain(frameToken: Long?): AppleMetadataRefreshFrameStats {
        val startedAt = nowNanos()
        var stale = false
        lateinit var batch: List<AppleMetadataRefreshIntent>
        var enqueued = 0
        var merged = 0
        var depth = 0
        var clearEpochAtStart = 0L
        synchronized(lock) {
            stale = draining || (frameToken != null &&
                (!frameScheduled || scheduledFrameToken != frameToken))
            if (stale) {
                // A duplicate or stale scheduler callback must not disturb the live frame state.
            } else {
                // A direct test drain supersedes any callback that was posted but not delivered.
                if (frameToken == null) scheduledFrameToken = 0L
                draining = true
                clearEpochAtStart = clearEpoch
                batch = nextFrameBatchLocked()
                pending.clear()
                enqueued = nextEnqueued
                merged = nextMerged
                depth = maxDepth
                nextEnqueued = 0
                nextMerged = 0
                maxDepth = 0
            }
        }
        if (stale) return emptyStats(startedAt)
        var executed = 0
        var failed = 0
        var processed = 0
        var nextIndex = 0
        var budgetExhausted = false
        while (nextIndex < batch.size) {
            if (processed > 0 && elapsedSince(startedAt) >= frameBudgetNanos) {
                budgetExhausted = true
                break
            }
            val intent = batch[nextIndex]
            nextIndex += 1
            processed += 1
            try {
                intent.action(intent)
                executed += 1
            } catch (_: Throwable) {
                failed += 1
            }
        }
        if (nextIndex < batch.size) budgetExhausted = true

        val deferredBatch = batch.subList(nextIndex, batch.size)
        var scheduleNext = false
        var nextFrameToken = 0L
        val deferred: Int
        val frameMaxDepth: Int
        synchronized(lock) {
            if (clearEpoch == clearEpochAtStart) {
                requeueDeferredLocked(deferredBatch)
            }
            deferred = pending.size
            frameMaxDepth = maxOf(depth, maxDepth)
            draining = false
            if (pending.isNotEmpty()) {
                frameScheduled = true
                nextFrameToken = ++this.nextFrameToken
                scheduledFrameToken = nextFrameToken
                scheduleNext = true
            } else {
                frameScheduled = false
                scheduledFrameToken = 0L
            }
        }
        val durationNanos = elapsedSince(startedAt)
        if (scheduleNext) scheduleFrame(nextFrameToken)
        val stats = AppleMetadataRefreshFrameStats(
            durationNanos = durationNanos,
            enqueued = enqueued,
            merged = merged,
            executed = executed,
            failed = failed,
            maxDepth = frameMaxDepth,
            deferred = deferred,
            overBudget = budgetExhausted || durationNanos > frameBudgetNanos,
        )
        try {
            diagnostics?.invoke(stats)
        } catch (_: Throwable) {
            // Diagnostics must never affect metadata delivery.
        }
        return stats
    }

    private companion object {
        private const val DEFAULT_FRAME_BUDGET_NANOS = 4_000_000L

        private val AppleMetadataRefreshKind.order: Int
            get() = when (this) {
                AppleMetadataRefreshKind.VISIBLE_RESOLUTION -> 0
                AppleMetadataRefreshKind.COLLECTION_PAGE_RESOLUTION -> 1
                AppleMetadataRefreshKind.ARTIST_BINDING -> 2
                AppleMetadataRefreshKind.RECENT_SEARCH_BINDING -> 3
                AppleMetadataRefreshKind.LISTEN_NOW_REBIND -> 4
                AppleMetadataRefreshKind.LIBRARY_CONTROLLER_REBIND -> 5
                AppleMetadataRefreshKind.LIBRARY_COMPOSE_REBIND -> 6
                AppleMetadataRefreshKind.DATA_BINDING_REBIND -> 7
                AppleMetadataRefreshKind.GENERIC_RECYCLER_NOTIFY -> 8
            }

        fun higherPriority(
            first: AppleInternalCatalogResolver.RequestPriority,
            second: AppleInternalCatalogResolver.RequestPriority,
        ): AppleInternalCatalogResolver.RequestPriority = if (
            first.ordinal >= second.ordinal
        ) first else second

        fun higherResolutionMode(
            first: InAppOriginalResolutionMode,
            second: InAppOriginalResolutionMode,
        ): InAppOriginalResolutionMode = if (first.ordinal >= second.ordinal) first else second
    }
}
