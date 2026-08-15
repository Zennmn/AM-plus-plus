package dev.amenhancer.module.hook

import android.app.Application
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cooperative cancellation for a running Catalog backfill. A batch captures
 * [generation] before its async request; when the response arrives it is
 * discarded (AMTool "丢弃迟到批次响应") if [isCancelled] or the generation moved on.
 */
internal interface CatalogRefreshCancellation {
    fun isCancelled(): Boolean
    fun generation(): Long

    companion object {
        val NONE = object : CatalogRefreshCancellation {
            override fun isCancelled(): Boolean = false
            override fun generation(): Long = 0L
        }
    }
}

/** AMTool feeds the batch query in slices of 100; the last slice keeps the remainder. */
internal const val CATALOG_BATCH_SIZE = 100

/** Chunk bounds for the Catalog backfill. */
internal fun backfillBatchRanges(total: Int, batchSize: Int = CATALOG_BATCH_SIZE): List<IntRange> {
    if (total <= 0) return emptyList()
    return generateSequence(0) { if (it + batchSize >= total) null else it + batchSize }
        .map { start -> start until (start + batchSize).coerceAtMost(total) }
        .toList()
}

/**
 * AMTool late-batch guard: a batch captured [capturedGeneration] before the
 * request was sent and must be dropped once the refresh was cancelled or a
 * newer refresh superseded it, even if the response itself is healthy.
 */
internal fun shouldDiscardBatch(
    cancelled: Boolean,
    capturedGeneration: Long,
    currentGeneration: Long,
): Boolean = cancelled || capturedGeneration != currentGeneration

/**
 * A suspend invocation may return the host's COROUTINE_SUSPENDED marker
 * immediately. Only a value matching the resolved response type is an
 * immediate Catalog response; every other non-null value belongs to the
 * continuation path.
 */
internal fun isCatalogImmediateResponse(
    responseType: Class<*>?,
    immediate: Any?,
): Boolean = responseType != null && responseType.isInstance(immediate)

/**
 * Resolves the host's EmptyCoroutineContext singleton for a generated
 * Continuation proxy.  Apple Music 6.5.1 keeps the Kotlin coroutine
 * interfaces' metadata names but R8-renames the implementation to `Bg.i`
 * with singleton field `a`; loading the module's
 * `kotlin.coroutines.EmptyCoroutineContext` therefore returns the wrong
 * type (or no class at all).
 */
internal fun resolveHostEmptyCoroutineContext(
    loader: ClassLoader,
    continuationType: Class<*>,
): Any? = runCatching {
    val contextType = continuationType.methods
        .firstOrNull {
            it.name == "getContext" && it.parameterCount == 0
        }
        ?.returnType
    val packageName = contextType?.name
        ?.substringBeforeLast('.', missingDelimiterValue = "")
        ?.takeIf(String::isNotBlank)
    val candidateNames = buildList {
        add("kotlin.coroutines.EmptyCoroutineContext")
        if (packageName != null) add("$packageName.EmptyCoroutineContext")
        if (packageName != null) add("$packageName.i")
        add("Bg.i")
    }.distinct()
    candidateNames.asSequence()
        .mapNotNull { name ->
            runCatching { Class.forName(name, false, loader) }.getOrNull()
        }
        .flatMap { type ->
            type.declaredFields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field ->
                    runCatching {
                        field.apply { isAccessible = true }.get(null)
                    }.getOrNull()
                }
        }
        .firstOrNull { value ->
            contextType != null &&
                contextType.isInstance(value) &&
                value.toString() == "EmptyCoroutineContext"
        }
}.getOrNull()

/** Result of the post-poll Catalog refresh. A failure never invalidates the native poll. */
internal data class CatalogBackfillResult(
    val songs: Int = 0,
    val albums: Int = 0,
    val songTotal: Int = 0,
    val albumTotal: Int = 0,
    val playlists: Int = 0,
    val playlistSongs: Int = 0,
    val attempted: Boolean = false,
    val skipped: Boolean = false,
    val batchFailures: Int = 0,
    val batchError: String? = null,
    val error: String? = null,
) {
    val successful: Boolean get() = error == null

    /** User-facing completion summary, modeled on AMTool's "刷新完成：歌曲 x/y，专辑 x/y". */
    fun completionMessage(): String = buildString {
        if (error != null) {
            append("Catalog 回填已降级：").append(error)
            return@buildString
        }
        append("刷新完成：歌曲 ").append(songs).append('/').append(songTotal)
        append("，专辑 ").append(albums).append('/').append(albumTotal)
        if (playlists > 0 || playlistSongs > 0) {
            append("（播放列表 ").append(playlists).append(" 个，并入歌曲 ").append(playlistSongs).append(" 首）")
        }
        if (batchFailures > 0) {
            append("；").append(batchFailures).append(" 个批次失败已跳过")
            batchError?.takeIf(String::isNotBlank)?.let { detail ->
                append("（").append(detail).append('）')
            }
        }
    }
}

/**
 * AMTool's small, deliberate refresh chain: MediaLibrary query methods ->
 * results.getItemCount/getItemAtIndex -> ids -> MediaApiRepository
 * getEntitiesWithIds batches -> CatalogTitleCache. Every reflective surface is
 * contract-verified against the host (the 6.5.1 names pinned in
 * [LibraryRefreshHost] were verified against that exact APK) and degrades
 * independently: missing playlist symbols only skip playlists, missing albums
 * only skip albums, and the caller can still report the native library poll as
 * completed when this coordinator degrades.
 */
internal class AppleMusicCatalogBackfill(
    private val application: Application,
    private val symbols: TargetSymbolResolver,
    private val targetLanguage: String,
    private val classLoader: ClassLoader,
    private val logger: (String) -> Unit = {},
    /** Reuse the display hook's cache so a refresh is visible immediately. */
    private val titleCache: CatalogTitleCache? = null,
    private val titleCacheProvider: CatalogTitleCacheProvider? = null,
    private val catalogLookup: CatalogEntityLookup? = null,
) {
    private val targetSource: TargetClassSource by lazy {
        val apk = ApkTargetClassSource(application, classLoader)
        object : TargetClassSource {
            override fun classNames(): List<String> = apk.classNames()
            override fun loadClass(name: String): Class<*>? =
                apk.loadClass(name) ?: runCatching {
                    Class.forName(name, false, classLoader)
                }.getOrNull()
        }
    }

    fun run(
        library: Any,
        cancellation: CatalogRefreshCancellation = CatalogRefreshCancellation.NONE,
    ): CatalogBackfillResult = runCatching {
        if (targetLanguage.isBlank()) {
            return CatalogBackfillResult(skipped = true)
        }
        val host = LibraryRefreshHost(targetSource)
        val songsQuery = host.songsQuery()
            ?: return CatalogBackfillResult(attempted = true, error = "歌曲查询符号不可用")
        val albumsQuery = host.albumsQuery()
            ?: return CatalogBackfillResult(attempted = true, error = "专辑查询符号不可用")
        val songIds = LinkedHashSet(
            enumerate(library, songsQuery, host, "songs", cancellation),
        )
        val playlists = host.playlistsQuery()?.let { playlistsQuery ->
            enumeratePlaylistSongs(library, host, playlistsQuery, songIds, cancellation)
        } ?: CatalogPlaylistOutcome().also {
            logger("播放列表枚举跳过：播放列表查询符号未确认")
        }
        val albumIds = enumerate(library, albumsQuery, host, "albums", cancellation)

        val cache = titleCache ?: titleCacheProvider?.get()
            ?: CatalogTitleCache(application, targetLanguage)
        val lookup = catalogLookup ?: AppleMusicCatalogEntityLookup(symbols, classLoader)
        val songs = backfill(lookup, songIds.toList(), "songs", cache, cancellation)
        val albums = backfill(lookup, albumIds, "albums", cache, cancellation)
        CatalogBackfillResult(
            songs = songs.written,
            albums = albums.written,
            songTotal = songs.total,
            albumTotal = albums.total,
            playlists = playlists.playlists,
            playlistSongs = playlists.merged,
            attempted = true,
            batchFailures = songs.batchFailures + albums.batchFailures,
            batchError = songs.error ?: albums.error,
        )
    }.getOrElse { error ->
        logger("catalog backfill degraded: $error")
        CatalogBackfillResult(attempted = true, error = error.message.orEmpty())
    }

    /** Songs/albums/playlist items are all extracted through the same results seam. */
    private fun enumerate(
        library: Any,
        queryMethod: Method,
        host: LibraryRefreshHost,
        mediaKind: String,
        cancellation: CatalogRefreshCancellation,
    ): List<String> {
        val query = host.buildQuery(mediaKind) ?: error("$mediaKind 查询参数构造失败")
        val operation = queryMethod.apply { isAccessible = true }.invoke(library, query)
            ?: error("$mediaKind 查询未返回异步结果")
        val results = host.awaitOperation(operation) ?: error("$mediaKind 查询结果超时或失败")
        return try {
            enumerateIds(results, mediaKind, cancellation)
        } finally {
            host.release(results)
        }
    }

    private fun enumerateIds(
        results: Any,
        mediaKind: String,
        cancellation: CatalogRefreshCancellation,
    ): List<String> {
        val countMethod = LibraryRefreshHost.findMethod(results, "getItemCount", 0)
            ?: error("查询结果缺少 getItemCount")
        val itemMethod = LibraryRefreshHost.findMethod(results, "getItemAtIndex", 1)
            ?: error("查询结果缺少 getItemAtIndex")
        val count = (countMethod.invoke(results) as? Number)?.toInt()
            ?.coerceAtMost(MAX_LIBRARY_ITEMS) ?: 0
        val ids = LinkedHashSet<String>(count)
        repeat(count) { index ->
            if (cancellation.isCancelled()) return@repeat
            val item = runCatching { itemMethod.invoke(results, index) }.getOrNull() ?: return@repeat
            val id = when (mediaKind) {
                "songs" -> LibraryRefreshHost.readString(item, "getSubscriptionStoreId")
                    ?: LibraryRefreshHost.readString(item, "getId")
                else -> LibraryRefreshHost.readString(item, "getId")
            } ?: return@repeat
            ids += id
        }
        return ids.toList()
    }

    /**
     * Confirmable playlist path: FAVORITES_PLAYLIST first, then the full
     * playlist list; every playlist's tracks are merged into [songIds] through
     * queryItemsFromPlaylist(descriptor, songs query). Each unresolved host
     * symbol skips playlists only; songs and albums keep their own paths.
     */
    private fun enumeratePlaylistSongs(
        library: Any,
        host: LibraryRefreshHost,
        playlistsQuery: Method,
        songIds: MutableSet<String>,
        cancellation: CatalogRefreshCancellation,
    ): CatalogPlaylistOutcome {
        val itemsQuery = host.playlistItemsQuery() ?: return CatalogPlaylistOutcome().also {
            logger("播放列表枚举跳过：播放列表条目查询符号未确认")
        }
        val playlistTypeEnum = host.playlistTypeEnum() ?: return CatalogPlaylistOutcome().also {
            logger("播放列表枚举跳过：播放列表类型枚举符号未确认")
        }
        val descriptorFactory = host.playlistDescriptorFactory() ?: return CatalogPlaylistOutcome().also {
            logger("播放列表枚举跳过：播放列表条目描述符工厂符号未确认")
        }

        val playlistIds = LinkedHashSet<String>()
        val favorites = playlistTypeEnum.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "FAVORITES_PLAYLIST" }
        if (favorites != null) {
            val favoritesBuilder = host.playlistBuilder() ?: return CatalogPlaylistOutcome().also {
                logger("播放列表枚举跳过：播放列表查询构造器符号未确认")
            }
            enumeratePlaylistList(
                library,
                host,
                playlistsQuery,
                host.buildFavoritesPlaylistQuery(favoritesBuilder, favorites),
                playlistIds,
                cancellation,
            )
        }
        val playlistsBuilder = host.playlistBuilder() ?: return CatalogPlaylistOutcome().also {
            logger("播放列表枚举跳过：播放列表查询构造器符号未确认")
        }
        enumeratePlaylistList(
            library,
            host,
            playlistsQuery,
            host.buildPlaylistQuery(playlistsBuilder),
            playlistIds,
            cancellation,
        )

        var merged = 0
        for (playlistId in playlistIds) {
            if (cancellation.isCancelled() || songIds.size >= MAX_PLAYLIST_TRACKS) break
            val descriptor = descriptorFactory.invoke(
                null,
                descriptorFactory.parameterTypes[0]
                    .enumConstants?.firstOrNull {
                        (it as? Enum<*>)?.name == "EntityTypeContainer"
                    },
                playlistId,
            ) ?: continue
            val query = host.buildSongQuery()
            val operation = itemsQuery.apply { isAccessible = true }
                .invoke(library, descriptor, query) ?: continue
            val results = host.awaitOperation(operation) ?: continue
            try {
                val ids = enumerateIds(results, "songs", cancellation)
                ids.forEach { id ->
                    if (songIds.size >= MAX_PLAYLIST_TRACKS) return@forEach
                    if (songIds.add(id)) merged++
                }
            } finally {
                host.release(results)
            }
        }
        logger(
            "播放列表枚举：播放列表 ${playlistIds.size} 个，并入歌曲 $merged 首，去重后歌曲合计 ${songIds.size} 首",
        )
        return CatalogPlaylistOutcome(playlists = playlistIds.size, merged = merged)
    }

    private fun enumeratePlaylistList(
        library: Any,
        host: LibraryRefreshHost,
        playlistsQuery: Method,
        query: Any?,
        playlistIds: MutableSet<String>,
        cancellation: CatalogRefreshCancellation,
    ) {
        if (query == null || cancellation.isCancelled()) return
        val operation = playlistsQuery.apply { isAccessible = true }.invoke(library, query)
            ?: return
        val results = host.awaitOperation(operation) ?: return
        try {
            enumerateIds(results, "playlists", cancellation).forEach { id ->
                if (playlistIds.size >= MAX_PLAYLISTS) return@forEach
                playlistIds += id
            }
        } finally {
            host.release(results)
        }
    }

    private fun backfill(
        lookup: CatalogEntityLookup,
        ids: List<String>,
        mediaKind: String,
        cache: CatalogTitleCache,
        cancellation: CatalogRefreshCancellation,
    ): BatchOutcome {
        if (ids.isEmpty()) return BatchOutcome(0, 0, 0)
        var written = 0
        var batchFailures = 0
        var firstFailure: String? = null
        for (range in backfillBatchRanges(ids.size)) {
            if (cancellation.isCancelled()) break
            val batch = ids.subList(range.first, range.last + 1)
            val capturedGeneration = cancellation.generation()
            runCatching {
                val entities = lookup.lookup(mediaKind, batch)
                if (shouldDiscardBatch(
                        cancellation.isCancelled(),
                        capturedGeneration,
                        cancellation.generation(),
                    )
                ) {
                    logger("刷新资料库：丢弃迟到批次响应（$mediaKind ${batch.size} 个 id）")
                    return@runCatching
                }
                val requested = batch.toHashSet()
                entities.filterNotNull().forEach { entity ->
                    val id = LibraryRefreshHost.readString(entity, "getId") ?: return@forEach
                    if (id !in requested) return@forEach
                    cache.captureCatalogMetadataForId(id, entity, mediaKind)
                    written++
                }
            }.onFailure { error ->
                val summary = catalogFailureSummary(error)
                logger("批量补查失败（$mediaKind，${batch.size} 个 id）：$summary")
                if (firstFailure == null) firstFailure = summary
                batchFailures++
            }
        }
        return BatchOutcome(written, ids.size, batchFailures, firstFailure)
    }

    private companion object {
        const val MAX_LIBRARY_ITEMS = 50_000
        const val MAX_PLAYLISTS = 100
        const val MAX_PLAYLIST_TRACKS = 50_000
    }
}

/**
 * Contract-verified host seam for the library enumeration chain. The pinned
 * names were verified against Apple Music 6.5.1 (1583): the query methods are
 * MediaLibrary.g / y / C and MediaLibrary.w, the query parameter type is G5.g,
 * the operation type is Vf.o, the builders are G5.f$a / G5.a$b / G5.i$a and
 * the playlist descriptor factory is F5.d.b(MediaLibrary.e, String). Every
 * method still re-verifies the full shape at runtime, so a version whose names
 * or shapes differ resolves null instead of silently calling something else.
 */
internal class LibraryRefreshHost(private val source: TargetClassSource) {
    fun load(name: String): Class<*>? = source.loadClass(name)

    private val mediaLibrary: Class<*>? by lazy {
        load("com.apple.android.medialibrary.library.MediaLibrary")
            ?.takeIf { it.isInterface }
    }

    private val songsQuery: Method? by lazy {
        mediaLibrary?.declaredMethods?.filter(::isSongsQueryMethod)?.singleOrNull()
    }

    private fun queryType(): Class<*>? = songsQuery?.parameterTypes?.singleOrNull()

    private fun operationType(): Class<*>? = songsQuery?.returnType

    fun songsQuery(): Method? = songsQuery

    fun albumsQuery(): Method? = mediaLibraryQueryMethod("y")

    fun playlistsQuery(): Method? = mediaLibraryQueryMethod("C")

    fun playlistItemsQuery(): Method? = runCatching {
        val query = queryType() ?: return null
        val operation = operationType() ?: return null
        val descriptor = load("F5.d") ?: return null
        mediaLibrary?.declaredMethods?.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == "w" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == descriptor &&
                method.parameterTypes[1] == query &&
                method.returnType == operation
        }?.singleOrNull()
    }.getOrNull()

    private fun mediaLibraryQueryMethod(name: String): Method? = runCatching {
        val query = queryType() ?: return null
        val operation = operationType() ?: return null
        mediaLibrary?.declaredMethods?.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == name &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == query &&
                method.returnType == operation
        }?.singleOrNull()
    }.getOrNull()

    fun buildQuery(mediaKind: String): Any? = when (mediaKind) {
        "songs" -> buildSongQuery()
        else -> buildAlbumQuery()
    }

    fun buildSongQuery(): Any? = buildQueryFromBuilder("G5.f\$a")

    fun buildAlbumQuery(): Any? = buildQueryFromBuilder("G5.a\$b")

    fun buildPlaylistQuery(builder: Any): Any? = runCatching {
        val query = queryType() ?: return null
        builder.javaClass.methods.firstOrNull {
            it.name == "a" && it.parameterCount == 0 && it.returnType == query
        }?.apply { isAccessible = true }?.invoke(builder)
    }.getOrNull()

    fun buildFavoritesPlaylistQuery(builder: Any, favorites: Any): Any? = runCatching {
        val setter = builder.javaClass.methods.firstOrNull {
            it.name == "b" && it.parameterCount == 1 &&
                it.parameterTypes.single() == favorites.javaClass
        }?.apply { isAccessible = true } ?: return null
        setter.invoke(builder, favorites)
        buildPlaylistQuery(builder)
    }.getOrNull()

    /** Instantiates the verified builder class and returns its built query. */
    private fun buildQueryFromBuilder(className: String): Any? = runCatching {
        val builderClass = load(className) ?: return null
        val query = queryType() ?: return null
        val build = builderClass.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterCount == 0 && it.returnType == query
        } ?: return null
        val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()
        build.apply { isAccessible = true }.invoke(builder)
    }.getOrNull()

    fun playlistBuilder(): Any? = runCatching {
        val builderClass = load("G5.i\$a") ?: return null
        val query = queryType() ?: return null
        val typeSetter = builderClass.declaredMethods.firstOrNull {
            it.name == "b" && it.parameterCount == 1 && it.parameterTypes.single().isEnum
        } ?: return null
        if (!builderClass.declaredMethods.any {
                it.name == "a" && it.parameterCount == 0 && it.returnType == query
            }
        ) {
            return null
        }
        if (typeSetter.parameterTypes.single().enumConstants
                ?.none { (it as? Enum<*>)?.name == "FAVORITES_PLAYLIST" } != false
        ) {
            return null
        }
        builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }.getOrNull()

    fun playlistTypeEnum(): Class<*>? = runCatching {
        load("G5.i\$b")?.takeIf { type ->
            type.isEnum && type.enumConstants?.any {
                (it as? Enum<*>)?.name == "FAVORITES_PLAYLIST"
            } == true
        }
    }.getOrNull()

    fun playlistDescriptorFactory(): Method? = runCatching {
        val descriptor = load("F5.d") ?: return null
        val entityType = mediaLibrary?.declaredClasses?.firstOrNull { type ->
            type.isEnum && type.enumConstants?.any {
                (it as? Enum<*>)?.name == "EntityTypeContainer"
            } == true
        } ?: return null
        descriptor.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "b" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == entityType &&
                method.parameterTypes[1] == String::class.java &&
                method.returnType == descriptor
        }?.apply { isAccessible = true }
    }.getOrNull()

    fun awaitOperation(operation: Any): Any? {
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val settled = AtomicBoolean(false)
        val done = CountDownLatch(1)
        if (!subscribeCallbackOperation(
                operation,
                onSuccess = { args ->
                    if (settled.compareAndSet(false, true)) {
                        result.set(args?.firstOrNull())
                        done.countDown()
                    }
                },
                onError = { args ->
                    if (settled.compareAndSet(false, true)) {
                        failure.set(args?.firstOrNull() as? Throwable)
                        done.countDown()
                    }
                },
                defaultValue = Companion::defaultValue,
            )
        ) {
            return null
        }
        if (!done.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        if (failure.get() != null) return null
        return result.get()
    }

    fun release(results: Any) = runCatching {
        findMethod(results, "release", 0)?.invoke(results)
    }

    companion object {
        fun findMethod(receiver: Any, name: String, parameterCount: Int): Method? =
            receiver.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == parameterCount
            }?.apply { isAccessible = true }

        fun readString(receiver: Any, methodName: String): String? = runCatching {
            findMethod(receiver, methodName, 0)?.invoke(receiver)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }
}

private fun isSongsQueryMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "g" &&
        method.parameterTypes.size == 1 &&
        method.returnType.name != "void"

/**
 * AMTool-confirmed async subscription shapes: the single-callback
 * `b(callback)` form (void) used today, and the two-parameter
 * `b(success, error)` form whose parameters share one callback interface and
 * whose return is non-void. The two-parameter form is preferred when an
 * operation exposes both.
 */
internal fun callbackSubscriptionMethod(operation: Any): Method? =
    operation.javaClass.methods.firstOrNull { method ->
        method.name == "b" &&
            method.parameterCount == 2 &&
            method.parameterTypes[0].isInterface &&
            method.parameterTypes[0] == method.parameterTypes[1] &&
            method.returnType != Void.TYPE
    } ?: operation.javaClass.methods.firstOrNull { method ->
        method.name == "b" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0].isInterface &&
            method.returnType == Void.TYPE
    }?.apply { isAccessible = true }

/**
 * Subscribes to an async operation through either callback shape and routes
 * onSuccess/onError to the given handlers: the two-parameter form receives a
 * success-only and an error-only proxy, the single-parameter form receives
 * one proxy that handles both. Callers guard duplicates on top of the
 * dispatch (the refresh task and the query latch both CAS on completion).
 */
internal fun subscribeCallbackOperation(
    operation: Any,
    onSuccess: (Array<Any>?) -> Unit,
    onError: (Array<Any>?) -> Unit,
    defaultValue: (Class<*>) -> Any?,
): Boolean {
    val subscribe = callbackSubscriptionMethod(operation) ?: return false
    val callbackType = subscribe.parameterTypes[0]
    val loader = callbackType.classLoader ?: AppleMusicCatalogBackfill::class.java.classLoader
    fun proxy(handler: (String, Array<Any>?) -> Unit): Any =
        Proxy.newProxyInstance(loader, arrayOf(callbackType)) { _, method, args ->
            handler(method.name, args)
            defaultValue(method.returnType)
        }
    val successOnly = proxy { name, args -> if (name == "onSuccess") onSuccess(args) }
    val errorOnly = proxy { name, args -> if (name == "onError") onError(args) }
    val both = proxy { name, args ->
        when (name) {
            "onSuccess" -> onSuccess(args)
            "onError" -> onError(args)
        }
    }
    if (subscribe.parameterCount == 2) {
        subscribe.invoke(operation, successOnly, errorOnly)
    } else {
        subscribe.invoke(operation, both)
    }
    return true
}

private const val QUERY_TIMEOUT_SECONDS = 30L

private data class CatalogPlaylistOutcome(
    val playlists: Int = 0,
    val merged: Int = 0,
)

private data class BatchOutcome(
    val written: Int,
    val total: Int,
    val batchFailures: Int,
    val error: String? = null,
)

/**
 * Keeps reflection/coroutine failures actionable without putting a full
 * InvocationTargetException stack or an unbounded server message in a Toast.
 */
internal fun catalogFailureSummary(error: Throwable): String {
    var current = error
    val seen = HashSet<Throwable>()
    while (seen.add(current)) {
        val cause = current.cause
        if (cause == null || cause === current) break
        if (
            current is InvocationTargetException ||
            current is java.lang.reflect.UndeclaredThrowableException ||
            current is java.util.concurrent.ExecutionException
        ) {
            current = cause
        } else {
            break
        }
    }
    val message = current.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val frame = current.stackTrace.firstOrNull()?.let {
        " @${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
    }.orEmpty()
    return (current.javaClass.simpleName + (message?.let { ": $it" } ?: "") + frame)
        .take(MAX_FAILURE_SUMMARY_LENGTH)
}

private const val MAX_FAILURE_SUMMARY_LENGTH = 180
