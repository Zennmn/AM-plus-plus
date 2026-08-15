package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Pure JVM coverage for the refresh backfill: batch pagination, the late-batch
 * discard guard, result summaries and the contract-verified host seam (query
 * methods, builders, playlist chain) resolved against fixture classes shaped
 * like the verified Apple Music 6.5.1 host.
 */
class AppleMusicCatalogBackfillPolicyTest {

    @Test
    fun `shared repository invoker returns immediate catalog response entities`() {
        val entity = CatalogEntityFixture("song-42")
        val repository = ImmediateCatalogRepositoryFixture(entity)
        val method = repository.javaClass.getDeclaredMethod(
            "getEntitiesWithIds",
            String::class.java,
            List::class.java,
            Map::class.java,
            HostContinuationFixture::class.java,
        )
        val invoker = MediaApiRepositoryCatalogInvoker(
            method = method,
            repository = repository,
            classLoader = repository.javaClass.classLoader!!,
        )

        assertEquals(listOf(entity), invoker.lookup("songs", listOf("song-42")))
    }

    @Test
    fun `host empty coroutine context resolves obfuscated Bg i singleton`() {
        val continuationType = HostContinuationFixture::class.java
        val context = resolveHostEmptyCoroutineContext(
            continuationType.classLoader!!,
            continuationType,
        )
        assertSame(Bg.i.a, context)
    }

    @Test
    fun `host empty coroutine context requires a continuation context return type`() {
        assertNull(
            resolveHostEmptyCoroutineContext(
                NoContextFixture::class.java.classLoader!!,
                NoContextFixture::class.java,
            ),
        )
    }

    @Test
    fun `batch ranges split at 100 and keep the tail`() {
        assertEquals(emptyList<IntRange>(), backfillBatchRanges(0))
        assertEquals(listOf(0 until 1), backfillBatchRanges(1))
        assertEquals(listOf(0 until 50), backfillBatchRanges(50))
        assertEquals(listOf(0 until 100), backfillBatchRanges(100))
        assertEquals(
            listOf(0 until 100, 100 until 113),
            backfillBatchRanges(113),
        )
        assertEquals(1, backfillBatchRanges(99).size)
        assertEquals(listOf(0 until 7, 7 until 9), backfillBatchRanges(9, batchSize = 7))
    }

    @Test
    fun `late batch is discarded after cancel or generation change`() {
        assertFalse(shouldDiscardBatch(cancelled = false, capturedGeneration = 1L, currentGeneration = 1L))
        assertTrue(shouldDiscardBatch(cancelled = true, capturedGeneration = 1L, currentGeneration = 1L))
        assertTrue(shouldDiscardBatch(cancelled = false, capturedGeneration = 1L, currentGeneration = 2L))
        assertTrue(shouldDiscardBatch(cancelled = true, capturedGeneration = 1L, currentGeneration = 2L))
    }

    @Test
    fun `catalog immediate response accepts only the resolved response type`() {
        val response = MediaApiResponseFixture()
        val coroutineSuspended = CoroutineSuspendedFixture()

        assertTrue(isCatalogImmediateResponse(MediaApiResponseFixture::class.java, response))
        assertFalse(
            isCatalogImmediateResponse(MediaApiResponseFixture::class.java, coroutineSuspended),
        )
        assertFalse(isCatalogImmediateResponse(MediaApiResponseFixture::class.java, Any()))
        assertFalse(isCatalogImmediateResponse(null, response))
        assertFalse(isCatalogImmediateResponse(MediaApiResponseFixture::class.java, null))
    }

    @Test
    fun `completion message reports written and total counts`() {
        val result = CatalogBackfillResult(
            songs = 3,
            albums = 2,
            songTotal = 12,
            albumTotal = 5,
            attempted = true,
        )
        assertEquals("刷新完成：歌曲 3/12，专辑 2/5", result.completionMessage())
    }

    @Test
    fun `completion message includes playlist merge and skipped batch notes`() {
        val result = CatalogBackfillResult(
            songs = 3,
            albums = 2,
            songTotal = 15,
            albumTotal = 5,
            playlists = 2,
            playlistSongs = 6,
            batchFailures = 1,
            attempted = true,
        )
        assertEquals(
            "刷新完成：歌曲 3/15，专辑 2/5（播放列表 2 个，并入歌曲 6 首）；1 个批次失败已跳过",
            result.completionMessage(),
        )
    }

    @Test
    fun `completion message includes the first batch failure detail`() {
        val result = CatalogBackfillResult(
            songs = 0,
            albums = 0,
            songTotal = 3,
            albumTotal = 2,
            batchFailures = 2,
            batchError = "IllegalStateException: request failed",
            attempted = true,
        )
        assertEquals(
            "刷新完成：歌曲 0/3，专辑 0/2；2 个批次失败已跳过（IllegalStateException: request failed）",
            result.completionMessage(),
        )
    }

    @Test
    fun `catalog failure summary unwraps reflection wrappers and bounds text`() {
        val summary = catalogFailureSummary(
            InvocationTargetException(IllegalStateException("request\nfailed")),
        )
        assertTrue(summary.startsWith("IllegalStateException: request failed @"))
    }

    @Test
    fun `completion message reports a degraded backfill instead of counts`() {
        val result = CatalogBackfillResult(attempted = true, error = "歌曲查询符号不可用")
        assertFalse(result.successful)
        assertEquals("Catalog 回填已降级：歌曲查询符号不可用", result.completionMessage())
    }

    @Test
    fun `id extraction prefers subscription store id for songs and skips blank`() {
        assertEquals("123", LibraryRefreshHost.readString(SongItemFixture("123", "456"), "getSubscriptionStoreId"))
        assertEquals("456", LibraryRefreshHost.readString(SongItemFixture("123", "456"), "getId"))
        assertNull(LibraryRefreshHost.readString(SongItemFixture("", ""), "getSubscriptionStoreId"))
        assertNull(LibraryRefreshHost.readString(SongItemFixture("0", ""), "getSubscriptionStoreId"))
        assertNull(LibraryRefreshHost.readString(Any(), "getSubscriptionStoreId"))
    }

    @Test
    fun `host resolves songs albums and playlist list queries from the fixture interface`() {
        val host = LibraryRefreshHost(fixtureSource())
        val songs = host.songsQuery()
        val albums = host.albumsQuery()
        val playlists = host.playlistsQuery()
        val items = host.playlistItemsQuery()

        assertEquals("g", songs?.name)
        assertEquals("y", albums?.name)
        assertEquals("C", playlists?.name)
        assertEquals("w", items?.name)
        assertEquals(1, songs?.parameterCount)
        assertEquals(2, items?.parameterCount)
    }

    @Test
    fun `host builds the verified query builders only when their shape matches`() {
        val host = LibraryRefreshHost(fixtureSource())
        assertTrue(host.buildSongQuery() is QueryTypeFixture)
        assertTrue(host.buildAlbumQuery() is QueryTypeFixture)

        val missing = LibraryRefreshHost(mapSource("G5.f\$a" to SongsBuilderFixture::class.java))
        assertNull(missing.buildSongQuery())
    }

    @Test
    fun `host resolves the playlist chain and favorites query`() {
        val host = LibraryRefreshHost(fixtureSource())
        val builder = host.playlistBuilder()
        val favorites = host.playlistTypeEnum()
            ?.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "FAVORITES_PLAYLIST" }

        assertNotNull(builder)
        assertNotNull(favorites)
        assertNotNull(host.playlistDescriptorFactory())

        val favoritesQuery = host.buildFavoritesPlaylistQuery(builder!!, favorites!!)
        assertTrue(favoritesQuery is QueryTypeFixture)
        assertEquals(FAVORITES_MARKER, (favoritesQuery as QueryTypeFixture).marker)
    }

    @Test
    fun `missing playlist symbols degrade the playlist merge only`() {
        val source = mapSource(
            "com.apple.android.medialibrary.library.MediaLibrary" to MediaLibraryFixture::class.java,
            "G5.f\$a" to SongsBuilderFixture::class.java,
            "G5.a\$b" to AlbumsBuilderFixture::class.java,
        )
        val host = LibraryRefreshHost(source)
        assertNotNull(host.songsQuery())
        assertNotNull(host.albumsQuery())
        assertNotNull(host.playlistsQuery())
        assertNull(host.playlistItemsQuery())
        assertNull(host.playlistBuilder())
        assertNull(host.playlistTypeEnum())
        assertNull(host.playlistDescriptorFactory())
    }

    @Test
    fun `a builder without the favorites enum is rejected`() {
        val source = mapSource(
            "com.apple.android.medialibrary.library.MediaLibrary" to MediaLibraryFixture::class.java,
            "G5.i\$a" to BrokenPlaylistBuilderFixture::class.java,
            "G5.i\$b" to BrokenPlaylistTypeFixture::class.java,
        )
        val host = LibraryRefreshHost(source)
        assertNull(host.playlistBuilder())
        assertNull(host.playlistTypeEnum())
    }

    @Test
    fun `favorites and full playlist queries never share a builder`() {
        val host = LibraryRefreshHost(fixtureSource())
        val favorites = host.playlistTypeEnum()!!
            .enumConstants?.first { (it as? Enum<*>)?.name == "FAVORITES_PLAYLIST" }
            ?: error("FAVORITES_PLAYLIST missing")
        val favoritesQuery = host.buildFavoritesPlaylistQuery(
            host.playlistBuilder()!!,
            favorites,
        ) as QueryTypeFixture
        assertEquals(FAVORITES_MARKER, favoritesQuery.marker)
        val fullQuery = host.buildPlaylistQuery(host.playlistBuilder()!!) as QueryTypeFixture
        assertNull(fullQuery.marker)
    }

    @Test
    fun `callback subscription prefers the two parameter form and routes both callbacks`() {
        val operation = TwoCallbackOperationFixture()
        assertEquals(2, callbackSubscriptionMethod(operation)?.parameterCount)
        var successMessage: String? = null
        var errorMessage: String? = null
        val subscribed = subscribeCallbackOperation(
            operation,
            onSuccess = { successMessage = it?.firstOrNull()?.toString() },
            onError = { errorMessage = (it?.firstOrNull() as? Throwable)?.message },
            defaultValue = { null },
        )
        assertTrue(subscribed)
        assertEquals("double", successMessage)
        assertEquals("boom", errorMessage)
    }

    @Test
    fun `callback subscription keeps the single callback shape working`() {
        val operation = SingleCallbackOperationFixture()
        assertEquals(1, callbackSubscriptionMethod(operation)?.parameterCount)
        var successMessage: String? = null
        val subscribed = subscribeCallbackOperation(
            operation,
            onSuccess = { successMessage = it?.firstOrNull()?.toString() },
            onError = {},
            defaultValue = { null },
        )
        assertTrue(subscribed)
        assertEquals("single", successMessage)
    }

    @Test
    fun `callback subscription prefers the two parameter form when both exist`() {
        val operation = BothShapesOperationFixture()
        var successMessage: String? = null
        subscribeCallbackOperation(
            operation,
            onSuccess = { successMessage = it?.firstOrNull()?.toString() },
            onError = {},
            defaultValue = { null },
        )
        assertEquals("double", successMessage)
    }

    private fun fixtureSource(): FakeRefreshSource = mapSource(
        "com.apple.android.medialibrary.library.MediaLibrary" to MediaLibraryFixture::class.java,
        "G5.f\$a" to SongsBuilderFixture::class.java,
        "G5.a\$b" to AlbumsBuilderFixture::class.java,
        "G5.i\$a" to PlaylistBuilderFixture::class.java,
        "G5.i\$b" to PlaylistTypeFixture::class.java,
        "F5.d" to DescriptorFixture::class.java,
    )

    private fun mapSource(vararg entries: Pair<String, Class<*>>): FakeRefreshSource =
        FakeRefreshSource(entries.toMap())

    private class FakeRefreshSource(
        private val classes: Map<String, Class<*>>,
    ) : TargetClassSource {
        override fun classNames(): List<String> = classes.keys.toList()
        override fun loadClass(name: String): Class<*>? = classes[name]
    }

    private class QueryTypeFixture {
        var marker: String? = null
    }

    private class OperationTypeFixture

    private class MediaApiResponseFixture

    private class CatalogEntityFixture(private val id: String) {
        fun getId(): String = id
    }

    private class ImmediateCatalogRepositoryFixture(private val entity: Any) {
        @Suppress("UNUSED_PARAMETER")
        fun getEntitiesWithIds(
            type: String,
            ids: List<String>,
            queryParams: Map<String, String>,
            continuation: HostContinuationFixture,
        ): Any = com.apple.android.music.mediaapi.repository.MediaApiResponse(arrayOf(entity))
    }

    private class CoroutineSuspendedFixture

    private class SongsBuilderFixture {
        fun a(): QueryTypeFixture = QueryTypeFixture()
    }

    private class AlbumsBuilderFixture {
        fun a(): QueryTypeFixture = QueryTypeFixture()
    }

    private class PlaylistBuilderFixture {
        private var marker: String? = null
        fun a(): QueryTypeFixture = QueryTypeFixture().apply { marker = this@PlaylistBuilderFixture.marker }
        fun b(type: PlaylistTypeFixture) {
            if (type == PlaylistTypeFixture.FAVORITES_PLAYLIST) marker = FAVORITES_MARKER
        }
    }

    private enum class PlaylistTypeFixture {
        USER_CREATED_PLAYLISTS,
        FAVORITES_PLAYLIST,
    }

    private class BrokenPlaylistBuilderFixture {
        fun a(): QueryTypeFixture = QueryTypeFixture()
        fun b(type: String) = Unit
    }

    private enum class BrokenPlaylistTypeFixture {
        SOMETHING_ELSE,
    }

    private class DescriptorFixture {
        companion object {
            @JvmStatic
            fun b(entityType: MediaLibraryFixture.EntityTypeFixture, id: String): DescriptorFixture =
                DescriptorFixture()
        }
    }

    private class SongItemFixture(
        private val subscriptionStoreId: String,
        private val id: String,
    ) {
        fun getSubscriptionStoreId(): String = subscriptionStoreId
        fun getId(): String = id
    }

    private interface MediaLibraryFixture {
        fun g(query: QueryTypeFixture): OperationTypeFixture
        fun y(query: QueryTypeFixture): OperationTypeFixture
        fun C(query: QueryTypeFixture): OperationTypeFixture
        fun w(descriptor: DescriptorFixture, query: QueryTypeFixture): OperationTypeFixture

        enum class EntityTypeFixture {
            EntityTypeUnknown,
            EntityTypeContainer,
        }
    }

    private interface CallbackFixture {
        fun onSuccess(value: String)
        fun onError(error: Throwable)
    }

    private class TwoCallbackOperationFixture {
        fun b(success: CallbackFixture, error: CallbackFixture): OperationTypeFixture {
            success.onSuccess("double")
            error.onError(IllegalStateException("boom"))
            return OperationTypeFixture()
        }
    }

    private class SingleCallbackOperationFixture {
        fun b(callback: CallbackFixture) {
            callback.onSuccess("single")
        }
    }

    private class BothShapesOperationFixture {
        fun b(callback: CallbackFixture) {
            callback.onSuccess("single")
        }

        fun b(success: CallbackFixture, error: CallbackFixture): OperationTypeFixture {
            success.onSuccess("double")
            return OperationTypeFixture()
        }
    }
}


private const val FAVORITES_MARKER = "favorites-set"
