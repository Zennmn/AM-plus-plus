package dev.amenhancer.module.hook

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Catalog boundary used by the cold-title miss coordinator. */
internal fun interface CatalogSongLookup {
    fun lookup(ids: List<String>): List<Any>
}

/** Time boundary kept injectable so debounce behavior is deterministic in JVM tests. */
internal fun interface CatalogMissScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit)

    /** Allows a lifecycle seam to initialize scheduler resources off the hot path. */
    fun prewarm() = Unit
}

/** Bounded state exposed for diagnostics and deterministic coordinator tests. */
internal data class CatalogMissBackfillState(
    val pending: Int,
    val inFlight: Int,
    val captured: Int,
    val retryable: Int,
)

/**
 * AMTool-style, non-blocking cold-cache coordinator.
 *
 * Display hooks only add stable Catalog song IDs. Work is merged for 400 ms,
 * bounded to 50 IDs per request, and published through the same title cache
 * observed by subsequent display getters.
 */
internal class CatalogMissBackfillCoordinator(
    private val cacheProvider: () -> CatalogTitleCache,
    private val lookup: CatalogSongLookup,
    private val scheduler: CatalogMissScheduler = DefaultCatalogMissScheduler,
    private val logger: (String) -> Unit = {},
) {
    constructor(
        cache: CatalogTitleCache,
        lookup: CatalogSongLookup,
        scheduler: CatalogMissScheduler = DefaultCatalogMissScheduler,
        logger: (String) -> Unit = {},
    ) : this({ cache }, lookup, scheduler, logger)

    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private val inFlight = LinkedHashSet<String>()
    private val completed = LinkedHashSet<String>()
    private val retryable = LinkedHashSet<String>()
    private var scheduled = false

    val pendingCount: Int
        get() = synchronized(lock) { pending.size }

    val inFlightCount: Int
        get() = synchronized(lock) { inFlight.size }

    val capturedCount: Int
        get() = synchronized(lock) { completed.size }

    val retryableCount: Int
        get() = synchronized(lock) { retryable.size }

    fun state(): CatalogMissBackfillState = synchronized(lock) {
        CatalogMissBackfillState(
            pending = pending.size,
            inFlight = inFlight.size,
            captured = completed.size,
            retryable = retryable.size,
        )
    }

    fun enqueue(id: String) {
        val normalized = id.trim()
        if (normalized.isEmpty()) return
        val shouldSchedule = synchronized(lock) {
            if (normalized in completed || normalized in inFlight) {
                false
            } else {
                val wasRetryable = retryable.remove(normalized)
                if (normalized in pending && !wasRetryable) return@synchronized false
                pending += normalized
                while (pending.size > MAX_PENDING_IDS) {
                    val eldest = pending.first()
                    pending.remove(eldest)
                    rememberRetryableLocked(eldest)
                }
                if (scheduled) {
                    false
                } else {
                    scheduled = true
                    true
                }
            }
        }
        if (shouldSchedule) scheduleDrain()
    }

    /** Initializes the production scheduler before a display getter can enqueue. */
    fun prewarm() = runCatching { scheduler.prewarm() }
        .onFailure { logger("catalog miss scheduler prewarm failed: $it") }

    private fun scheduleDrain() {
        runCatching {
            scheduler.schedule(DEBOUNCE_MILLIS, ::drain)
        }.onFailure { error ->
            synchronized(lock) {
                scheduled = false
                val failed = pending.toList()
                pending.clear()
                failed.forEach(::rememberRetryableLocked)
            }
            logger("catalog miss schedule failed: $error")
        }
    }

    private fun drain() {
        val batch = synchronized(lock) {
            scheduled = false
            pending.take(MAX_BATCH_SIZE).also { ids ->
                pending.removeAll(ids.toSet())
                inFlight.addAll(ids)
            }
        }
        if (batch.isEmpty()) return

        runCatching {
            val requested = batch.toHashSet()
            val cache = cacheProvider()
            lookup.lookup(batch).forEach { entity ->
                val id = LibraryRefreshHost.readString(entity, "getId") ?: return@forEach
                if (id !in requested) return@forEach
                cache.captureCatalogMetadataForId(id, entity, "songs")
            }
        }.onSuccess {
            synchronized(lock) {
                batch.forEach { id ->
                    inFlight -= id
                    retryable -= id
                    completed += id
                    while (completed.size > MAX_COMPLETED_IDS) {
                        completed.remove(completed.first())
                    }
                }
            }
        }.onFailure { error ->
            // Failed IDs are deliberately not marked complete. A later display
            // miss may enqueue them again without creating a retry loop here.
            synchronized(lock) {
                inFlight.removeAll(batch.toSet())
                batch.forEach(::rememberRetryableLocked)
            }
            logger("catalog miss lookup failed (${batch.size} ids): $error")
        }

        val shouldSchedule = synchronized(lock) {
            if (pending.isEmpty() || scheduled) {
                false
            } else {
                scheduled = true
                true
            }
        }
        if (shouldSchedule) scheduleDrain()
    }

    private fun rememberRetryableLocked(id: String) {
        retryable += id
        while (retryable.size > MAX_RETRYABLE_IDS) retryable.remove(retryable.first())
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
        const val MAX_BATCH_SIZE = 50
        const val MAX_PENDING_IDS = 500
        const val MAX_COMPLETED_IDS = 500
        const val MAX_RETRYABLE_IDS = 500
    }
}

private object DefaultCatalogMissScheduler : CatalogMissScheduler {
    private val executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "am++-catalog-miss-backfill").apply { isDaemon = true }
        }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun prewarm() {
        executor
    }
}
