package dev.amenhancer.module.hook

import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The identity captured once at a display seam.  No relationship or storage
 * access is part of this value; those operations belong to the background
 * [CatalogObservationAdapter].
 */
internal data class CatalogIdentitySnapshot(
    val ids: List<String>,
    val entityKind: TitleCorrectionPolicy.EntityKind,
    val preferCollectionId: Boolean = false,
) {
    val primaryId: String?
        get() = ids.firstOrNull()

    val key: String
        get() = buildString {
            append(entityKind.name)
            append(':')
            append(if (preferCollectionId) 'c' else 'i')
            append(':')
            append(primaryId.orEmpty())
        }
}

/** A request handed to the relation/storage adapter on a worker thread. */
internal data class CatalogObservationRequest(
    val entity: Any,
    val identity: CatalogIdentitySnapshot,
)

/**
 * Result returned by a background observation.  [captured] means that the
 * adapter found and published catalog metadata.  A non-captured result (for
 * example, a relation-less local song) remains retryable; [missId] can carry
 * the stable id to the catalog backfill coordinator without marking the
 * display observation as successfully captured.
 */
internal data class CatalogObservationResult(
    val captured: Boolean,
    val missId: String? = null,
)

/** Relation traversal, catalog access, and persistence boundary. */
internal fun interface CatalogObservationAdapter {
    fun capture(request: CatalogObservationRequest): CatalogObservationResult
}

/** Snapshot of bounded coordinator state, useful for diagnostics and tests. */
internal data class CatalogObservationState(
    val pending: Int,
    val inFlight: Int,
    val captured: Int,
    val retryable: Int,
)

/**
 * Coalesces cold display observations without doing host I/O on the caller's
 * thread.  The caller may enqueue from a getter with [schedule] false; a
 * later background drain (or [observe] with [schedule] true) performs the
 * relation walk.  Every state collection has an explicit bound.
 */
internal class CatalogObservationCoordinator(
    private val adapter: CatalogObservationAdapter,
    private val scheduler: CatalogMissScheduler = InlineCatalogObservationScheduler,
    private val logger: (String) -> Unit = {},
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val maxCaptured: Int = DEFAULT_MAX_CAPTURED,
    private val maxRetryable: Int = DEFAULT_MAX_RETRYABLE,
) {
    init {
        require(maxPending > 0) { "maxPending must be positive" }
        require(maxInFlight > 0) { "maxInFlight must be positive" }
        require(maxCaptured > 0) { "maxCaptured must be positive" }
        require(maxRetryable > 0) { "maxRetryable must be positive" }
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, CatalogObservationRequest>()
    private val inFlight = LinkedHashMap<String, CatalogObservationRequest>()
    private val captured = LinkedHashSet<String>()
    private val retryable = LinkedHashSet<String>()
    private var scheduled = false
    private var backgroundStarted = false

    val pendingCount: Int
        get() = synchronized(lock) { pending.size }

    val inFlightCount: Int
        get() = synchronized(lock) { inFlight.size }

    val capturedCount: Int
        get() = synchronized(lock) { captured.size }

    val retryableCount: Int
        get() = synchronized(lock) { retryable.size }

    fun state(): CatalogObservationState = synchronized(lock) {
        CatalogObservationState(
            pending = pending.size,
            inFlight = inFlight.size,
            captured = captured.size,
            retryable = retryable.size,
        )
    }

    /**
     * Adds an identity to the bounded queue. Before the background lifecycle
     * seam starts, a UI call with [schedule] false only records bounded state;
     * after startup it may submit work to the already-prewarmed scheduler.
     */
    fun observe(entity: Any?, identity: CatalogIdentitySnapshot, schedule: Boolean = true) {
        if (entity == null || identity.primaryId.isNullOrBlank()) return
        val request = CatalogObservationRequest(entity, identity)
        val shouldSchedule = synchronized(lock) {
            val key = identity.key
            if (key in captured || key in inFlight) return@synchronized false
            // A retryable item is explicitly re-armed by the next observation.
            val wasRetryable = retryable.remove(key)
            if (pending.containsKey(key) && !wasRetryable) return@synchronized false
            putPendingLocked(key, request)
            if ((!schedule && !backgroundStarted) || scheduled) {
                false
            } else {
                scheduled = true
                true
            }
        }
        if (shouldSchedule) scheduleDrain()
    }

    /**
     * Starts a queued drain from an explicitly background lifecycle seam. The
     * display getter never calls this method, so constructing/using a cold
     * cache on the UI thread cannot create a worker or scheduler. Hosts may
     * invoke it after their adapter is ready, or tests may use [drainNow].
     */
    fun startBackgroundDrain() {
        val shouldSchedule = synchronized(lock) {
            backgroundStarted = true
            if (pending.isEmpty() || scheduled) {
                false
            } else {
                scheduled = true
                true
            }
        }
        runCatching { scheduler.prewarm() }
            .onFailure { logger("catalog observation scheduler prewarm failed: $it") }
        if (shouldSchedule) scheduleDrain()
    }

    /**
     * Captures one request synchronously.  This is intentionally a background
     * seam; [CatalogTitleCache] only calls it after observing a non-main
     * caller.  Failures are swallowed and retained as retryable state.
     */
    fun captureNow(entity: Any?, identity: CatalogIdentitySnapshot): Boolean {
        if (entity == null || identity.primaryId.isNullOrBlank()) return false
        val request = CatalogObservationRequest(entity, identity)
        val key = identity.key
        val accepted = synchronized(lock) {
            if (key in captured || key in inFlight) return@synchronized false
            pending.remove(key)
            retryable.remove(key)
            if (inFlight.size >= maxInFlight) {
                rememberRetryableLocked(key)
                return@synchronized false
            }
            inFlight[key] = request
            true
        }
        if (!accepted) return false
        return process(request, key, scheduleAfter = true)
    }

    /** Executes queued observations synchronously for a worker or deterministic test. */
    fun drainNow() {
        val batch = synchronized(lock) {
            scheduled = false
            // `captureNow` may already be traversing a relation on another
            // worker.  Account for those requests before taking a batch so
            // the bounded in-flight contract is true under contention too.
            val capacity = (maxInFlight - inFlight.size).coerceAtLeast(0)
            val entries = pending.entries.take(capacity)
            entries.forEach { (key, request) ->
                pending.remove(key)
                inFlight[key] = request
            }
            entries.map { it.value }
        }
        batch.forEach { request -> process(request, request.identity.key, scheduleAfter = false) }
        requestDrainIfCapacity()
    }

    private fun process(
        request: CatalogObservationRequest,
        key: String,
        scheduleAfter: Boolean,
    ): Boolean {
        val result = runCatching { adapter.capture(request) }
            .onFailure { error -> logger("catalog observation failed: $error") }
            .getOrNull()
        if (result == null) {
            synchronized(lock) {
                inFlight.remove(key)
                rememberRetryableLocked(key)
            }
            if (scheduleAfter) requestDrainIfCapacity()
            return false
        }
        synchronized(lock) {
            inFlight.remove(key)
            if (result.captured) {
                retryable.remove(key)
                rememberCapturedLocked(key)
            } else {
                // A relation-less result (usually a song miss handed to the
                // catalog backfill coordinator) is not a successful capture.
                // Keep it re-armable so a later display miss can retry after a
                // transient backfill failure.
                rememberRetryableLocked(key)
            }
        }
        result.missId?.let { id -> runCatching { logger("catalog observation miss: $id") } }
        if (scheduleAfter) requestDrainIfCapacity()
        return result.captured
    }

    /** Schedules queued work only when an in-flight slot is actually free. */
    private fun requestDrainIfCapacity() {
        val shouldSchedule = synchronized(lock) {
            if (pending.isEmpty() || scheduled || inFlight.size >= maxInFlight) {
                false
            } else {
                scheduled = true
                true
            }
        }
        if (shouldSchedule) scheduleDrain()
    }

    private fun scheduleDrain() {
        runCatching {
            scheduler.schedule(0L, ::drainNow)
        }.onFailure { error ->
            synchronized(lock) {
                scheduled = false
                // Keep requests available for a later observation.  The
                // retryable set is bounded independently from pending.
                val failed = pending.keys.toList()
                pending.clear()
                failed.forEach(::rememberRetryableLocked)
            }
            logger("catalog observation schedule failed: $error")
        }
    }

    private fun putPendingLocked(key: String, request: CatalogObservationRequest) {
        pending[key] = request
        while (pending.size > maxPending) {
            val eldest = pending.entries.iterator().next()
            pending.remove(eldest.key)
            rememberRetryableLocked(eldest.key)
        }
    }

    private fun rememberCapturedLocked(key: String) {
        captured += key
        while (captured.size > maxCaptured) {
            captured.remove(captured.first())
        }
    }

    private fun rememberRetryableLocked(key: String) {
        retryable += key
        while (retryable.size > maxRetryable) {
            retryable.remove(retryable.first())
        }
    }

    private companion object {
        const val DEFAULT_MAX_PENDING = 256
        const val DEFAULT_MAX_IN_FLIGHT = 50
        const val DEFAULT_MAX_CAPTURED = 500
        const val DEFAULT_MAX_RETRYABLE = 500
    }
}

/** No-op scheduler used by cache's synchronous/background test seam. */
internal object InlineCatalogObservationScheduler : CatalogMissScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit) = task()
}

/** Production scheduler used only after the title feature starts background services. */
internal object DefaultCatalogObservationScheduler : CatalogMissScheduler {
    private val executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "am++-catalog-observation").apply { isDaemon = true }
        }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun prewarm() {
        executor
    }
}
