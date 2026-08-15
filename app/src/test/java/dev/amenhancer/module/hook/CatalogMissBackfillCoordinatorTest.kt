package dev.amenhancer.module.hook

import android.app.Application
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First red/green behavior seam for cold Catalog song title backfill.
 *
 * These tests deliberately keep the network boundary and clock deterministic:
 * [CatalogSongLookup] stands in for the songs Catalog query and
 * [CatalogMissScheduler] only runs work when the test asks it to.  The
 * coordinator is expected to merge IDs for the 400 ms window, query no more
 * than 50 IDs at a time, and publish returned entities through the same
 * [CatalogTitleCache] instance used by display getters.
 */
class CatalogMissBackfillCoordinatorTest {

    @Test
    fun `relationless cold song queues its stable catalog id without blocking display`() {
        val misses = mutableListOf<String>()
        val cache = CatalogTitleCache(
            CoordinatorTestApplication(inMemoryPreferences(linkedMapOf())),
            "zh-CN",
            CatalogTitleMissListener(misses::add),
        )

        assertNull(cache.correctedTitle(RelationlessSong("catalog-42"), "English Song"))
        assertEquals(listOf("catalog-42"), misses)
    }

    @Test
    fun `relationless library song prefers play params catalog id over local id`() {
        val misses = mutableListOf<String>()
        val cache = CatalogTitleCache(
            CoordinatorTestApplication(inMemoryPreferences(linkedMapOf())),
            "zh-CN",
            CatalogTitleMissListener(misses::add),
        )

        assertNull(cache.correctedTitle(RelationlessLibrarySong("catalog-play-7"), "English Song"))
        assertEquals(listOf("catalog-play-7"), misses)
    }

    @Test
    fun `cold song id is queried after debounce and written to shared cache`() {
        val id = "song-cold"
        val entity = TestCatalogSong(id, "中文歌曲")
        val lookup = RecordingCatalogSongLookup(mapOf(id to entity))
        val scheduler = RecordingCatalogMissScheduler()
        val cache = testCache()
        val coordinator = CatalogMissBackfillCoordinator(
            cache = cache,
            lookup = lookup,
            scheduler = scheduler,
        )

        coordinator.enqueue(id)

        assertEquals(emptyList<List<String>>(), lookup.requests)
        assertEquals(listOf(400L), scheduler.delays)

        scheduler.runAll()

        assertEquals(listOf(listOf(id)), lookup.requests)
        assertEquals("中文歌曲", cache.correctedTitle(entity, "English Song"))
    }

    @Test
    fun `duplicate cold ids are merged into one lookup`() {
        val id = "song-duplicate"
        val entity = TestCatalogSong(id, "重复歌曲")
        val lookup = RecordingCatalogSongLookup(mapOf(id to entity))
        val scheduler = RecordingCatalogMissScheduler()
        val coordinator = CatalogMissBackfillCoordinator(
            cache = testCache(),
            lookup = lookup,
            scheduler = scheduler,
        )

        coordinator.enqueue(id)
        coordinator.enqueue(id)
        coordinator.enqueue(id)

        // One scheduled task represents the merged debounce window.
        assertEquals(listOf(400L), scheduler.delays)
        scheduler.runAll()

        assertEquals(listOf(listOf(id)), lookup.requests)
    }

    @Test
    fun `a lookup batch contains at most fifty song ids`() {
        val ids = (1..51).map { "song-$it" }
        val entities = ids.associateWith { id -> TestCatalogSong(id, "中文$id") }
        val lookup = RecordingCatalogSongLookup(entities)
        val scheduler = RecordingCatalogMissScheduler()
        val cache = testCache()
        val coordinator = CatalogMissBackfillCoordinator(
            cache = cache,
            lookup = lookup,
            scheduler = scheduler,
        )

        ids.forEach(coordinator::enqueue)
        scheduler.runAll()

        assertEquals(listOf(50, 1), lookup.requests.map { it.size })
        assertTrue(lookup.requests.all { it.size <= 50 })
        assertEquals("中文song-1", cache.correctedTitle(entities.getValue("song-1"), "English 1"))
        assertEquals("中文song-51", cache.correctedTitle(entities.getValue("song-51"), "English 51"))
    }

    @Test
    fun `same id observed while lookup is in flight is not queried twice`() {
        val id = "song-in-flight"
        val entity = TestCatalogSong(id, "查询中歌曲")
        val scheduler = RecordingCatalogMissScheduler()
        val requests = mutableListOf<List<String>>()
        lateinit var coordinator: CatalogMissBackfillCoordinator
        coordinator = CatalogMissBackfillCoordinator(
            cache = testCache(),
            lookup = CatalogSongLookup { ids ->
                requests += ids.toList()
                coordinator.enqueue(id)
                listOf(entity)
            },
            scheduler = scheduler,
        )

        coordinator.enqueue(id)
        scheduler.runAll()

        assertEquals(listOf(listOf(id)), requests)
    }

    @Test
    fun `scheduler rejection leaves the same id eligible for a later miss`() {
        val id = "song-schedule-retry"
        val entity = TestCatalogSong(id, "重试歌曲")
        val scheduler = RejectFirstCatalogMissScheduler()
        val lookup = RecordingCatalogSongLookup(mapOf(id to entity))
        val coordinator = CatalogMissBackfillCoordinator(
            cache = testCache(),
            lookup = lookup,
            scheduler = scheduler,
        )

        coordinator.enqueue(id)
        coordinator.enqueue(id)
        scheduler.runAll()

        assertEquals(listOf(listOf(id)), lookup.requests)
    }

    @Test
    fun `lookup failure clears in flight and a later miss retries once`() {
        val id = "song-lookup-retry"
        val entity = TestCatalogSong(id, "查询重试歌曲")
        val scheduler = RecordingCatalogMissScheduler()
        var attempts = 0
        val coordinator = CatalogMissBackfillCoordinator(
            cache = testCache(),
            lookup = CatalogSongLookup { ids ->
                attempts += 1
                if (attempts == 1) error("temporary lookup failure")
                ids.map { entity }
            },
            scheduler = scheduler,
        )

        coordinator.enqueue(id)
        scheduler.runAll()
        coordinator.enqueue(id)
        scheduler.runAll()

        assertEquals(2, attempts)
    }

    private fun testCache(): CatalogTitleCache = CatalogTitleCache(
        CoordinatorTestApplication(inMemoryPreferences(linkedMapOf())),
        "zh-CN",
    )
}

private class RecordingCatalogSongLookup(
    private val entities: Map<String, TestCatalogSong>,
) : CatalogSongLookup {
    val requests = mutableListOf<List<String>>()

    override fun lookup(ids: List<String>): List<Any> {
        requests += ids.toList()
        return ids.mapNotNull { entities[it] }
    }
}

private class RecordingCatalogMissScheduler : CatalogMissScheduler {
    val delays = mutableListOf<Long>()
    private val tasks = java.util.ArrayDeque<() -> Unit>()

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        delays += delayMillis
        tasks.addLast(task)
    }

    fun runNext() {
        if (tasks.isEmpty()) return
        tasks.removeFirst().invoke()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }
}

private class RejectFirstCatalogMissScheduler : CatalogMissScheduler {
    private var rejected = false
    private val tasks = java.util.ArrayDeque<() -> Unit>()

    override fun schedule(delayMillis: Long, task: () -> Unit) {
        if (!rejected) {
            rejected = true
            error("scheduler rejected test task")
        }
        tasks.addLast(task)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }
}

/** Minimal host-shaped entity consumed by CatalogTitleCache's reflective path. */
private class TestCatalogSong(
    private val id: String,
    private val title: String,
) {
    fun getId(): String = id
    fun getTitle(): String = title
}

private class RelationlessSong(private val subscriptionStoreId: String) {
    fun getSubscriptionStoreId(): String = subscriptionStoreId
    fun getId(): String = "local-library-id"
    fun getRelationships(): Map<String, Any> = emptyMap()
}

private class RelationlessLibrarySong(catalogId: String) {
    private val attributes = RelationlessAttributes(catalogId)

    fun getSubscriptionStoreId(): String = ""
    fun getId(): String = "local-library-id"
    fun getAttributes(): RelationlessAttributes = attributes
    fun getRelationships(): Map<String, Any> = emptyMap()
}

private class RelationlessAttributes(catalogId: String) {
    private val playParams = RelationlessPlayParams(catalogId)

    fun getPlayParams(): RelationlessPlayParams = playParams
}

private class RelationlessPlayParams(private val catalogId: String) {
    fun getCatalogId(): String = catalogId
}

private class CoordinatorTestApplication(
    private val preferences: SharedPreferences,
) : Application() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

private fun inMemoryPreferences(store: MutableMap<String, Any>): SharedPreferences {
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
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
            "apply" -> Unit
            "toString" -> "in-memory-editor"
            "hashCode" -> System.identityHashCode(editor)
            "equals" -> args?.getOrNull(0) === editor
            else -> null
        }
    } as SharedPreferences.Editor

    return Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
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
    } as SharedPreferences
}
