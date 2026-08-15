package dev.amenhancer.module.hook

import android.app.Application
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the 6.5.1 album -> catalog relationship shape. */
class CatalogTitleCacheRelationTest {

    @Test
    fun `album relation capture writes title and album keys`() {
        val persisted = linkedMapOf<String, Any>()
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )
        val album = AlbumRelationEntity(
            id = "album-42",
            relationships = mapOf(
                "catalog" to CatalogRelationship(
                    arrayOf(CatalogEntity("中文专辑")),
                ),
            ),
        )

        assertEquals("中文专辑", cache.correctedTitle(album, "English Album"))
        assertEquals(
            "中文专辑",
            persisted[TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.TITLE, "zh-CN", "album-42")],
        )
        assertEquals(
            "中文专辑",
            persisted[TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM, "zh-CN", "album-42")],
        )
    }

    @Test
    fun `playback title candidates follow subscription then collection identity`() {
        val persisted = linkedMapOf<String, Any>(
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.SONG,
                "zh-CN",
                "subscription-42",
            ) to "订阅歌曲",
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.SONG,
                "zh-CN",
                "song-42",
            ) to "实体歌曲",
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                "zh-CN",
                "album-42",
            ) to "专辑名称",
        )
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )
        val playback = PlaybackSong(
            id = "song-42",
            subscriptionStoreId = "subscription-42",
            collectionId = "album-42",
        )

        assertEquals("订阅歌曲", cache.correctedDisplayTitle(playback, "English Song"))
        assertEquals(
            "专辑名称",
            cache.correctedDisplayAlbumName(playback, "English Album", playback),
        )
    }

    @Test
    fun `catalog title lookup tries subscription identity when getId cache misses`() {
        val persisted = linkedMapOf<String, Any>(
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.SONG,
                "zh-CN",
                "subscription-42",
            ) to "订阅歌曲",
        )
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )

        assertEquals("订阅歌曲", cache.correctedTitle(PlaybackSong("song-42", "subscription-42", "album-42"), "English Song"))
    }

    @Test
    fun `identity candidates accept numeric and opaque ids`() {
        val persisted = linkedMapOf<String, Any>(
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.SONG,
                "zh-CN",
                "opaque-42",
            ) to "数字歌曲",
        )
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )

        assertEquals("数字歌曲", cache.correctedTitle(NumericIdentitySong(), "Numeric Song"))
    }

    @Test
    fun `catalog relation title can come from attributes instead of direct getTitle`() {
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(linkedMapOf())),
            "zh-CN",
        )
        val entity = AlbumRelationEntity(
            id = "album-attributes",
            relationships = mapOf(
                "catalog" to CatalogRelationship(
                    arrayOf(AttributeCatalogEntity("属性中文专辑")),
                ),
            ),
        )

        assertEquals("属性中文专辑", cache.correctedTitle(entity, "English Album"))
    }

    @Test
    fun `source converter title lookup captures relation before deferred work`() {
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(linkedMapOf())),
            "zh-CN",
        )
        val entity = AlbumRelationEntity(
            id = "album-source",
            relationships = mapOf(
                "catalog" to CatalogRelationship(
                    arrayOf(CatalogEntity("转换中文专辑")),
                ),
            ),
        )

        assertEquals("转换中文专辑", cache.correctedTitleFromSource(entity, "English Album"))
    }

    @Test
    fun `action sheet aliases cached song artist to artist navigation id`() {
        val persisted = linkedMapOf<String, Any>(
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.ARTIST,
                "zh-CN",
                "song-42",
            ) to "林俊杰",
        )
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )
        val playback = PlaybackSong("song-42", "song-42", "album-42")
        val artistItem = ArtistNavigationItem("artist-7")

        cache.aliasDisplayArtist(playback, "artist-7")

        assertEquals("林俊杰", cache.correctedDisplayArtist(artistItem, "JJ Lin"))
    }

    @Test
    fun `action sheet response map corrects artist title by its map key`() {
        val persisted = linkedMapOf<String, Any>(
            TitleCorrectionPolicy.cacheKey(
                TitleCorrectionPolicy.CacheKind.ARTIST,
                "zh-CN",
                "artist-7",
            ) to "林俊杰",
        )
        val cache = CatalogTitleCache(
            FakeApplication(inMemoryPreferences(persisted)),
            "zh-CN",
        )

        assertEquals("林俊杰", cache.correctedArtistById("artist-7", "JJ Lin"))
    }
}
private class FakeApplication(
    private val preferences: SharedPreferences,
) : Application() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

private class AlbumRelationEntity(
    private val id: String,
    private val relationships: Map<String, Any>,
) {
    fun getId(): String = id
    fun getRelationships(): Map<String, Any> = relationships
}

private class CatalogRelationship(
    private val entities: Array<Any>,
) {
    fun getEntities(): Array<Any> = entities
}

private class CatalogEntity(
    private val title: String,
) {
    fun getTitle(): String = title
}

private class AttributeCatalogEntity(
    private val title: String,
) {
    fun getAttributes(): CatalogAttributes = CatalogAttributes(title)
}

private class CatalogAttributes(
    private val title: String,
) {
    fun getName(): String = title
}

private class PlaybackSong(
    private val id: String,
    private val subscriptionStoreId: String,
    private val collectionId: String,
) {
    fun getId(): String = id
    fun getSubscriptionStoreId(): String = subscriptionStoreId
    fun getCollectionId(): String = collectionId
}

private class ArtistNavigationItem(private val id: String) {
    fun getId(): String = id
}

private class NumericIdentitySong {
    fun getId(): Long = 42L
    fun getSubscriptionStoreId(): OpaqueId = OpaqueId("opaque-42")
}

private class OpaqueId(private val value: String) {
    override fun toString(): String = value
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
                if (value == null) store.remove(key) else store[key!!] = value
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
