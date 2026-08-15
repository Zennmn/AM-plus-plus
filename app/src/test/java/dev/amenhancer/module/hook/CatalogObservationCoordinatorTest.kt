package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * RED coverage for the cold observation seam.  The display-facing call only
 * snapshots an identity and queues a bounded observation; relation access and
 * persistence are adapter work performed by the drain path.
 */
class CatalogObservationCoordinatorTest {

    @Test
    fun `main cold corrected title does not read relationships or start scheduler`() {
        val relation = CountingRelationEntity("song-main")
        val scheduler = RecordingObservationScheduler()
        val adapter = RecordingObservationAdapter { error("relation traversal must stay off the UI path") }
        val coordinator = CatalogObservationCoordinator(
            adapter = adapter,
            scheduler = scheduler,
        )
        val cache = testCache(
            mainThread = { true },
            observationCoordinator = coordinator,
        )

        assertNull(cache.correctedTitle(relation, "English Song"))
        assertEquals(0, relation.relationshipReads)
        assertEquals(0, relation.entityReads)
        assertEquals(0, scheduler.scheduled)
        assertEquals(1, coordinator.pendingCount)
    }

    @Test
    fun `warm attributes bind with same identity queues only once`() {
        val relation = CountingRelationEntity("song-warm")
        val scheduler = RecordingObservationScheduler()
        val coordinator = CatalogObservationCoordinator(
            adapter = RecordingObservationAdapter { CatalogObservationResult(captured = true) },
            scheduler = scheduler,
        )
        val cache = testCache(
            mainThread = { true },
            observationCoordinator = coordinator,
        )

        cache.correctedTitle(relation, "English Song")
        cache.correctedTitle(relation, "English Song")
        cache.correctedTitle(relation, "English Song")

        assertEquals(1, coordinator.pendingCount)
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun `relation hit is captured in memory then persisted once on background drain`() {
        val persisted = linkedMapOf<String, Any>()
        val relation = CountingRelationEntity(
            stableId = "song-hit",
            catalog = CountingCatalogEntity("中文歌曲"),
        )
        val scheduler = RecordingObservationScheduler()
        var main = true
        val cache = testCache(
            persisted = persisted,
            mainThread = { main },
        )

        assertNull(cache.correctedTitle(relation, "English Song"))
        val coordinator = cache.observationCoordinatorForTests()
        assertEquals(1, coordinator.pendingCount)

        main = false
        coordinator.drainNow()

        assertEquals("中文歌曲", cache.correctedTitle(relation, "English Song"))
        assertEquals("中文歌曲", persisted[TitleCorrectionPolicy.cacheKey(
            TitleCorrectionPolicy.CacheKind.SONG,
            "zh-CN",
            "song-hit",
        )])
        assertEquals(1, relation.relationshipReads)
        assertEquals(1, relation.entityReads)
        // The default adapter owns one persistence transaction for one hit.
        assertEquals(1, editCalls)
        assertTrue(scheduler.scheduled <= 1)
    }

    @Test
    fun `main thread direct capture is flushed by a worker without another relation walk`() {
        val persisted = linkedMapOf<String, Any>()
        var main = true
        val cache = testCache(
            persisted = persisted,
            mainThread = { main },
        )

        cache.captureCatalogMetadataForId(
            id = "main-direct",
            catalog = CountingCatalogEntity("中文歌曲"),
            mediaKind = "songs",
        )
        assertNull(persisted[TitleCorrectionPolicy.cacheKey(
            TitleCorrectionPolicy.CacheKind.SONG,
            "zh-CN",
            "main-direct",
        )])

        // A real host invokes this from the background service.  Flip the
        // injected thread predicate and use the deterministic worker seam.
        main = false
        cache.flushPendingDiskForTests()

        assertEquals(
            "中文歌曲",
            persisted[TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.SONG,
                "zh-CN",
                "main-direct",
            )],
        )
    }

    @Test
    fun `lookup and scheduler failures remain fail open and can retry`() {
        var attempts = 0
        val scheduler = RecordingObservationScheduler(rejectFirst = true)
        val coordinator = CatalogObservationCoordinator(
            adapter = RecordingObservationAdapter {
                attempts += 1
                if (attempts == 1) error("temporary relation failure")
                CatalogObservationResult(captured = true)
            },
            scheduler = scheduler,
        )
        val entity = Any()
        val identity = testIdentity("retry-song")

        coordinator.observe(entity, identity)
        assertEquals(1, coordinator.retryableCount)
        coordinator.observe(entity, identity)
        scheduler.runAll()

        assertEquals(1, attempts)
        assertEquals(1, coordinator.retryableCount)
        coordinator.observe(entity, identity)
        scheduler.runAll()

        assertEquals(2, attempts)
        assertEquals(1, coordinator.capturedCount)
        assertEquals(0, coordinator.retryableCount)
    }

    @Test
    fun `non captured miss stays retryable for the next display observation`() {
        var attempts = 0
        val scheduler = RecordingObservationScheduler()
        val coordinator = CatalogObservationCoordinator(
            adapter = RecordingObservationAdapter {
                attempts += 1
                CatalogObservationResult(
                    captured = attempts > 1,
                    missId = "retryable-song".takeIf { attempts == 1 },
                )
            },
            scheduler = scheduler,
        )
        val identity = testIdentity("retryable-song")

        coordinator.observe(Any(), identity)
        scheduler.runAll()
        assertEquals(1, attempts)
        assertEquals(0, coordinator.capturedCount)
        assertEquals(1, coordinator.retryableCount)

        coordinator.observe(Any(), identity)
        scheduler.runAll()
        assertEquals(2, attempts)
        assertEquals(1, coordinator.capturedCount)
        assertEquals(0, coordinator.retryableCount)
    }

    @Test
    fun `drain honors remaining in flight capacity under contention`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduler = RecordingObservationScheduler()
        val coordinator = CatalogObservationCoordinator(
            adapter = RecordingObservationAdapter {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "timed out" }
                CatalogObservationResult(captured = true)
            },
            scheduler = scheduler,
            maxInFlight = 1,
        )

        val first = thread(start = true) {
            coordinator.captureNow(Any(), testIdentity("in-flight-1"))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        coordinator.observe(Any(), testIdentity("in-flight-2"), schedule = false)
        coordinator.drainNow()
        assertEquals(1, coordinator.inFlightCount)
        assertEquals(1, coordinator.pendingCount)

        release.countDown()
        first.join(2_000)
        coordinator.drainNow()

        assertEquals(0, coordinator.inFlightCount)
        assertEquals(0, coordinator.pendingCount)
        assertEquals(2, coordinator.capturedCount)
    }

    @Test
    fun `pending in flight captured and retryable sets stay bounded`() {
        val scheduler = RecordingObservationScheduler()
        val coordinator = CatalogObservationCoordinator(
            adapter = RecordingObservationAdapter { error("hold for bounded-state test") },
            scheduler = scheduler,
            maxPending = 3,
            maxInFlight = 2,
            maxCaptured = 2,
            maxRetryable = 2,
        )

        (1..20).forEach { index ->
            coordinator.observe(Any(), testIdentity("song-$index"), schedule = false)
        }
        assertTrue(coordinator.pendingCount <= 3)
        coordinator.drainNow()
        assertTrue(coordinator.inFlightCount <= 2)
        assertTrue(coordinator.retryableCount <= 2)
        assertTrue(coordinator.capturedCount <= 2)
    }

    private fun testCache(
        persisted: MutableMap<String, Any> = linkedMapOf(),
        mainThread: () -> Boolean = { false },
        observationCoordinator: CatalogObservationCoordinator? = null,
    ): CatalogTitleCache {
        editCalls = 0
        return CatalogTitleCache(
        application = CoordinatorObservationTestApplication(inMemoryPreferences(persisted) { editCalls += 1 }),
        configuredLanguage = "zh-CN",
        observationCoordinator = observationCoordinator,
        mainThread = mainThread,
        )
    }

    private fun testIdentity(id: String): CatalogIdentitySnapshot = CatalogIdentitySnapshot(
        ids = listOf(id),
        entityKind = TitleCorrectionPolicy.EntityKind.SONG,
    )

    private var editCalls: Int = 0
}

private class RecordingObservationScheduler(
    private val rejectFirst: Boolean = false,
) : CatalogMissScheduler {
    var scheduled: Int = 0
        private set
    private var rejected = false
    private val tasks = ArrayDeque<() -> Unit>()

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        scheduled += 1
        if (rejectFirst && !rejected) {
            rejected = true
            error("scheduler unavailable")
        }
        tasks.addLast(task)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }
}

private class RecordingObservationAdapter(
    private val block: (CatalogObservationRequest) -> CatalogObservationResult,
) : CatalogObservationAdapter {
    override fun capture(request: CatalogObservationRequest): CatalogObservationResult = block(request)
}

private class CountingRelationEntity(
    private val stableId: String,
    val catalog: CountingCatalogEntity? = null,
) {
    var relationshipReads: Int = 0
    var entityReads: Int = 0

    fun getId(): String = stableId

    fun getRelationships(): Map<String, Any> {
        relationshipReads += 1
        return mapOf("catalog" to object {
            fun getEntities(): Array<Any> {
                entityReads += 1
                return catalog?.let { arrayOf(it) } ?: emptyArray()
            }
        })
    }
}

private class CountingCatalogEntity(private val title: String) {
    fun getTitle(): String = title
}

private class CoordinatorObservationTestApplication(
    private val preferences: android.content.SharedPreferences,
) : android.app.Application() {
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = preferences
}

private fun inMemoryPreferences(
    store: MutableMap<String, Any>,
    onEdit: () -> Unit = {},
): android.content.SharedPreferences {
    lateinit var editor: android.content.SharedPreferences.Editor
    editor = java.lang.reflect.Proxy.newProxyInstance(
        android.content.SharedPreferences.Editor::class.java.classLoader,
        arrayOf(android.content.SharedPreferences.Editor::class.java),
    ) { _, method, args ->
        val key = args?.getOrNull(0) as? String
        when (method.name) {
            "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                val value = args?.getOrNull(1)
                if (value == null) store.remove(key) else if (key != null) store[key] = value
                editor
            }
            "remove" -> {
                if (key != null) store.remove(key)
                editor
            }
            "clear" -> {
                store.clear()
                editor
            }
            "commit" -> true
            "apply" -> {
                onEdit()
                Unit
            }
            "toString" -> "in-memory-editor"
            "hashCode" -> System.identityHashCode(editor)
            "equals" -> args?.getOrNull(0) === editor
            else -> null
        }
    } as android.content.SharedPreferences.Editor
    return java.lang.reflect.Proxy.newProxyInstance(
        android.content.SharedPreferences::class.java.classLoader,
        arrayOf(android.content.SharedPreferences::class.java),
    ) { proxy, method, args ->
        val key = args?.getOrNull(0) as? String
        when (method.name) {
            "getAll" -> HashMap(store)
            "getString" -> store[key] as? String ?: args?.getOrNull(1) as? String
            "getStringSet" -> @Suppress("UNCHECKED_CAST") {
                store[key] as? Set<String> ?: args?.getOrNull(1) as? Set<String>
            }
            "getInt" -> store[key] as? Int ?: (args?.getOrNull(1) as? Int ?: 0)
            "getLong" -> store[key] as? Long ?: (args?.getOrNull(1) as? Long ?: 0L)
            "getFloat" -> store[key] as? Float ?: (args?.getOrNull(1) as? Float ?: 0f)
            "getBoolean" -> store[key] as? Boolean ?: (args?.getOrNull(1) as? Boolean ?: false)
            "contains" -> key != null && store.containsKey(key)
            "edit" -> editor
            "toString" -> "in-memory-preferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.getOrNull(0) === proxy
            else -> null
        }
    } as android.content.SharedPreferences
}
