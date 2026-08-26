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
 * Typed request passed through the frame queue.  The pending map is cleared before callbacks
 * run, so target references live for at most one frame; callers must still perform their own
 * visibility/generation checks at execution time.
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
    )

    private val lock = Any()
    private val pending = LinkedHashMap<IdentityKey, Pending>()
    private var frameScheduled = false
    private var nextEnqueued = 0
    private var nextMerged = 0
    private var maxDepth = 0

    fun enqueue(intent: AppleMetadataRefreshIntent) {
        val normalized = intent.copy(
            mediaId = intent.mediaId?.trim()?.takeIf(String::isNotEmpty),
            mediaIds = intent.mediaIds.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
        )
        val key = IdentityKey(
            kind = normalized.kind,
            mediaId = normalized.mediaId,
            target = normalized.target,
            slot = normalized.slot,
        )
        var shouldSchedule = false
        synchronized(lock) {
            val existing = pending[key]
            if (existing == null) {
                pending[key] = Pending(normalized)
                nextEnqueued += 1
                maxDepth = maxOf(maxDepth, pending.size)
            } else {
                val previous = existing.intent
                existing.intent = previous.copy(
                    mediaIds = previous.mediaIds + normalized.mediaIds,
                    priority = higherPriority(previous.priority, normalized.priority),
                    originalResolutionMode = higherResolutionMode(
                        previous.originalResolutionMode,
                        normalized.originalResolutionMode,
                    ),
                    generation = maxOf(previous.generation, normalized.generation),
                    alias = normalized.alias ?: previous.alias,
                    action = normalized.action,
                )
                nextMerged += 1
            }
            if (!frameScheduled) {
                frameScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) {
            postToMain {
                frameScheduler.postFrame(::drain)
            }
        }
    }

    /** Drain synchronously for unit tests; production code is driven by [MetadataFrameScheduler]. */
    internal fun drainNowForTests(): AppleMetadataRefreshFrameStats = drain()

    internal fun pendingSizeForTests(): Int = synchronized(lock) { pending.size }

    internal fun clearPending() {
        synchronized(lock) {
            pending.clear()
            frameScheduled = false
            nextEnqueued = 0
            nextMerged = 0
            maxDepth = 0
        }
    }

    private fun drain(): AppleMetadataRefreshFrameStats {
        val startedAt = nowNanos()
        val batch: List<AppleMetadataRefreshIntent>
        val enqueued: Int
        val merged: Int
        val depth: Int
        synchronized(lock) {
            batch = pending.values
                .map(Pending::intent)
                .sortedBy { it.kind.order }
            pending.clear()
            enqueued = nextEnqueued
            merged = nextMerged
            depth = maxDepth
            nextEnqueued = 0
            nextMerged = 0
            maxDepth = 0
            frameScheduled = false
        }
        var executed = 0
        var failed = 0
        batch.forEach { intent ->
            try {
                intent.action(intent)
                executed += 1
            } catch (_: Throwable) {
                failed += 1
            }
        }
        val stats = AppleMetadataRefreshFrameStats(
            durationNanos = nowNanos() - startedAt,
            enqueued = enqueued,
            merged = merged,
            executed = executed,
            failed = failed,
            maxDepth = depth,
        )
        try {
            diagnostics?.invoke(stats)
        } catch (_: Throwable) {
            // Diagnostics must never affect metadata delivery.
        }
        return stats
    }

    private companion object {
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
