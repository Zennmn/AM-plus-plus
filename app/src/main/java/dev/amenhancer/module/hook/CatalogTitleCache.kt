package dev.amenhancer.module.hook

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import dev.amenhancer.module.config.CatalogLanguagePolicy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/**
 * A small synchronized access-order LRU map used by [CatalogTitleCache].
 *
 * `SharedPreferences.all` has no ordering contract, so it cannot provide a
 * meaningful eviction policy by itself.  The map owns recency while a cache
 * instance is alive; persisted entries are loaded in key order, making the
 * first trim deterministic after process restart.  [put] returns the keys
 * evicted by the capacity bound so the caller can remove those keys from
 * SharedPreferences in the same editor transaction.
 */
internal class AccessOrderLruMap<K, V>(private val maxEntries: Int) : Map<K, V> {
    private val delegate = LinkedHashMap<K, V>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    override val size: Int
        @Synchronized get() = delegate.size

    override fun containsKey(key: K): Boolean = synchronized(this) {
        if (!delegate.containsKey(key)) return@synchronized false
        // LinkedHashMap.containsKey does not update access order.  Treat a
        // successful lookup as a cache access, just like get().
        delegate[key]
        true
    }

    override fun containsValue(value: V): Boolean = synchronized(this) {
        delegate.containsValue(value)
    }

    override operator fun get(key: K): V? = synchronized(this) {
        delegate[key]
    }

    override fun isEmpty(): Boolean = synchronized(this) {
        delegate.isEmpty()
    }

    override val entries: Set<Map.Entry<K, V>>
        @Synchronized get() = LinkedHashMap(delegate).entries

    override val keys: Set<K>
        @Synchronized get() = LinkedHashSet(delegate.keys)

    override val values: Collection<V>
        @Synchronized get() = delegate.values.toList()

    /** Adds or replaces an entry and returns eldest keys removed by capacity. */
    fun put(key: K, value: V): List<K> = synchronized(this) {
        delegate[key] = value
        if (delegate.size <= maxEntries) return@synchronized emptyList()
        val evicted = mutableListOf<K>()
        val iterator = delegate.entries.iterator()
        while (delegate.size > maxEntries && iterator.hasNext()) {
            evicted += iterator.next().key
            iterator.remove()
        }
        evicted
    }

    fun remove(key: K): V? = synchronized(this) {
        delegate.remove(key)
    }

    /** Least-recently-used to most-recently-used keys, for deterministic tests. */
    fun orderedKeys(): List<K> = synchronized(this) {
        delegate.keys.toList()
    }
}

/** Lazily creates one process-local title cache shared by display and refresh paths. */
internal class CatalogTitleCacheProvider(
    private val factory: () -> CatalogTitleCache,
) {
    @Volatile
    private var instance: CatalogTitleCache? = null

    fun get(): CatalogTitleCache {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: factory().also { instance = it }
        }
    }
}

/** Receives a stable song Catalog ID only after cache and relationship lookup miss. */
internal fun interface CatalogTitleMissListener {
    fun onSongCacheMiss(id: String)

    companion object {
        val NONE = CatalogTitleMissListener {}
    }
}
/**
 * Locale/schema-scoped catalog title cache.
 *
 * Keys are `catalog-<kind>:<localeTag>:<appleMusicId>`.  The active locale
 * is still the only locale consulted by [TitleCorrectionPolicy], while all
 * cache keys are retained in the bounded LRU so the persisted store has one
 * global cap.  Accesses and writes are safe from concurrent display hooks;
 * preference writes are serialized across cache instances as well.
 */
internal class CatalogTitleCache(
    application: Application,
    configuredLanguage: String,
    private val missListener: CatalogTitleMissListener = CatalogTitleMissListener.NONE,
    private val observationCoordinator: CatalogObservationCoordinator? = null,
    private val mainThread: () -> Boolean = {
        runCatching {
            val mainLooper = Looper.getMainLooper() ?: return@runCatching false
            Looper.myLooper() === mainLooper
        }.getOrDefault(false)
    },
    private val observationScheduler: CatalogMissScheduler = InlineCatalogObservationScheduler,
) {
    private val localeTag = CatalogLanguagePolicy.resolveTag(configuredLanguage)
    private val application = application
    @Volatile
    private var preferences: SharedPreferences? = null
    private val storageLoaded = AtomicBoolean(false)
    private val storageLoadLock = Any()
    private val values = AccessOrderLruMap<String, String>(MAX_ENTRIES)
    private val captureGuard: ThreadLocal<MutableSet<Any>> = ThreadLocal.withInitial {
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    }
    /** Links immutable Attributes objects back to their owning MediaEntity. */
    private val attributesOwners = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<Any>>(),
    )
    /** Also covers Title objects retained by a page before Attributes is read again. */
    private val titleOwners = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<Any>>(),
    )
    /** Writes performed in memory by a main-thread direct-capture seam. */
    private val pendingDiskKeys = ConcurrentHashMap.newKeySet<String>()
    /** Coalesces worker flushes triggered by main-thread memory captures. */
    private val diskFlushScheduled = AtomicBoolean(false)
    private val backgroundServicesStarted = AtomicBoolean(false)
    private val backgroundExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "am++-catalog-title-background").apply { isDaemon = true }
        }
    }
    @Volatile
    private var schemaCurrent = false

    init {
        // A display getter may construct the provider on the host main thread.
        // Do not touch SharedPreferences or create a worker from that path.
        if (!isMainThread()) ensureStorageLoaded()
    }

    private fun loadPersistedEntries() {
        if (!isMainThread()) ensureStorageLoaded()
    }

    fun correctedTitle(entity: Any?, raw: String?): String? {
        if (entity == null || raw.isNullOrBlank()) return null
        if (!isMainThread()) ensureStorageLoaded()
        val identity = identitySnapshot(entity, preferCollectionId = false)
        val entityKind = identity.entityKind
        val kind = if (entityKind == TitleCorrectionPolicy.EntityKind.ALBUM) {
            TitleCorrectionPolicy.CacheKind.ALBUM
        } else {
            TitleCorrectionPolicy.CacheKind.SONG
        }
        // AMTool tries every stable identity because library/playback models
        // do not always expose the same id used by the Catalog response.
        val ids = identity.ids
        if (ids.isEmpty()) return null
        val candidate = ids.asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, entityKind) }
            .firstOrNull()
        if (candidate != null) {
            return candidate
        }
        if (ids.any { id -> hasCachedTitle(id, kind) }) return null
        if (isMainThread()) {
            // Cold display paths only snapshot identity, read the in-memory
            // LRU, and enqueue a bounded observation. Relationship traversal,
            // catalog lookup, and SharedPreferences writes stay on a worker.
            observation().observe(entity, identity, schedule = false)
            return null
        }
        // Keep synchronous behavior for background callers and local JVM
        // fixtures, where there is no Android main Looper. The coordinator's
        // adapter performs the relation traversal and persistence here.
        observation().captureNow(entity, identity)
        return ids.asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, entityKind) }
            .firstOrNull()
    }

    /**
     * Uses the source MediaEntity identity when a model conversion result does
     * not expose the catalog id.  The y8.B converter is one such path: its
     * model.Song carries display fields, but the source mediaapi Song is the
     * object that still owns the Apple Music id and catalog relationship.
     */
    fun correctedTitleFromSource(source: Any?, raw: String?): String? =
        // The converter can run before the deferred capture has completed.
        // Use the source entity's relation synchronously, just like the
        // MediaEntity getter path, so the converted Song's first render is
        // corrected instead of waiting for a second bind.
        correctedTitle(source, raw)

    fun correctedArtistFromSource(source: Any?, raw: String?): String? =
        correctedSourceMetadata(source, raw, TitleCorrectionPolicy.CacheKind.ARTIST)

    fun correctedAlbumNameFromSource(source: Any?, raw: String?): String? =
        correctedSourceMetadata(source, raw, TitleCorrectionPolicy.CacheKind.ALBUM_NAME)

    fun correctedArtist(entity: Any?, raw: String?): String? =
        correctedMetadata(entity, raw, TitleCorrectionPolicy.CacheKind.ARTIST)

    fun correctedAlbumName(entity: Any?, raw: String?): String? =
        correctedMetadata(entity, raw, TitleCorrectionPolicy.CacheKind.ALBUM_NAME)

    /**
     * Corrects a local-library/playback display item using the same identity
     * candidates AMTool uses for model.Song/model.Album.  These models do not
     * always expose the catalog id through getId(): a subscribed song can
     * expose it only through getSubscriptionStoreId(), while downloaded items
     * may expose persistent/cloud/asset ids instead.
     */
    fun correctedDisplayTitle(item: Any?, raw: String?): String? =
        correctedDisplayMetadata(item, raw, TitleCorrectionPolicy.CacheKind.TITLE)

    /** Corrects artist text on a local playback/display model. */
    fun correctedDisplayArtist(item: Any?, raw: String?): String? =
        correctedDisplayMetadata(item, raw, TitleCorrectionPolicy.CacheKind.ARTIST)

    /** Corrects an artist navigation row whose only stable identity is its map key. */
    fun correctedArtistById(artistId: String?, raw: String?): String? {
        if (artistId.isNullOrBlank() || raw.isNullOrBlank()) return null
        return correctedById(
            id = artistId,
            raw = raw,
            kind = TitleCorrectionPolicy.CacheKind.ARTIST,
            entityKind = TitleCorrectionPolicy.EntityKind.UNKNOWN,
        )
    }

    /**
     * The player action sheet navigates by artist ID, while Catalog song
     * metadata stores the localized artist under the song's identities. Copy
     * that authoritative value to the navigation identity before StorePlatform
     * refreshes the row.
     */
    fun aliasDisplayArtist(source: Any?, artistId: String?) {
        if (source == null || artistId.isNullOrBlank()) return
        val artist = identityCandidates(source, preferCollectionId = false)
            .asSequence()
            .mapNotNull { id ->
                values[TitleCorrectionPolicy.cacheKey(
                    TitleCorrectionPolicy.CacheKind.ARTIST,
                    localeTag,
                    id,
                )]
            }
            .firstOrNull()
            ?: return
        persist(
            listOf(
                TitleCorrectionPolicy.cacheKey(
                    TitleCorrectionPolicy.CacheKind.ARTIST,
                    localeTag,
                    artistId,
                ) to artist,
            ),
            writeToDisk = !isMainThread(),
        )
    }

    /**
     * Corrects an album/collection name.  [collectionOwner] is used by the
     * player action sheet because its song id and album collection id are
     * different identities; the collection id must win for this field.
     */
    fun correctedDisplayAlbumName(
        item: Any?,
        raw: String?,
        collectionOwner: Any? = null,
    ): String? = correctedDisplayMetadata(
        receiver = collectionOwner ?: item,
        raw = raw,
        kind = TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
        preferCollectionId = collectionOwner != null,
    )

    /** Registers the owner needed by pages that render Attributes.getTitle(). */
    fun registerAttributesOwner(entity: Any?, attributes: Any?) {
        if (entity == null || attributes == null) return
        attributesOwners[attributes] = WeakReference(entity)
        callObject(attributes, "getTitle")?.let { title ->
            titleOwners[title] = WeakReference(entity)
        }
    }

    /** Binds the exact Title instance returned by a later getTitle call. */
    fun registerTitleOwner(attributes: Any?, title: Any?) {
        if (attributes == null || title == null) return
        attributesOwners[attributes]?.get()?.let { owner ->
            titleOwners[title] = WeakReference(owner)
        }
    }

    /** AMTool reads Title.getStringForDisplay(), not only Attributes.getName(). */
    fun correctedAttributesTitle(attributes: Any?, raw: String?): String? {
        val owner = attributes?.let { attributesOwners[it]?.get() } ?: return null
        return correctedTitle(owner, raw)
    }

    /** Corrects a direct Attributes.getArtistName() render when its owner is known. */
    fun correctedAttributesArtist(attributes: Any?, raw: String?): String? {
        val owner = attributes?.let { attributesOwners[it]?.get() } ?: return null
        return correctedArtist(owner, raw)
    }

    /** Corrects a direct Attributes.getAlbumName() render when its owner is known. */
    fun correctedAttributesAlbumName(attributes: Any?, raw: String?): String? {
        val owner = attributes?.let { attributesOwners[it]?.get() } ?: return null
        return correctedAlbumName(owner, raw)
    }

    /** Corrects a retained immutable Title object rendered by a page. */
    fun correctedTitleObject(title: Any?, raw: String?): String? {
        val owner = title?.let { titleOwners[it]?.get() } ?: return null
        return correctedTitle(owner, raw)
    }

    private fun correctedMetadata(
        entity: Any?,
        raw: String?,
        kind: TitleCorrectionPolicy.CacheKind,
    ): String? {
        if (entity == null || raw.isNullOrBlank()) return null
        val entityKind = TitleCorrectionPolicy.entityKindOf(entity.javaClass.name)
        return identityCandidates(entity, preferCollectionId = false)
            .asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, entityKind) }
            .firstOrNull()
    }

    private fun correctedDisplayMetadata(
        receiver: Any?,
        raw: String?,
        kind: TitleCorrectionPolicy.CacheKind,
        preferCollectionId: Boolean = false,
    ): String? {
        if (receiver == null || raw.isNullOrBlank()) return null
        val entityKind = TitleCorrectionPolicy.entityKindOf(receiver.javaClass.name)
        return identityCandidates(receiver, preferCollectionId)
            .asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, entityKind) }
            .firstOrNull()
    }

    private fun correctedSourceMetadata(
        source: Any?,
        raw: String?,
        kind: TitleCorrectionPolicy.CacheKind,
    ): String? {
        if (source == null || raw.isNullOrBlank()) return null
        val entityKind = TitleCorrectionPolicy.entityKindOf(source.javaClass.name)
        return identityCandidates(source, preferCollectionId = false)
            .asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, entityKind) }
            .firstOrNull()
    }

    private fun correctedById(
        id: String,
        raw: String?,
        kind: TitleCorrectionPolicy.CacheKind,
        entityKind: TitleCorrectionPolicy.EntityKind,
    ): String? = TitleCorrectionPolicy.correctionCandidate(
        appleMusicId = id,
        raw = raw,
        kind = kind,
        values = values,
        localeTag = localeTag,
        entityKind = entityKind,
        schemaCurrent = schemaCurrent,
    )

    fun captureCatalogMetadata(entity: Any?) {
        if (entity == null) return
        val identity = identitySnapshot(entity, preferCollectionId = false)
        if (identity.primaryId.isNullOrBlank()) return
        if (isMainThread()) {
            observation().observe(entity, identity, schedule = false)
        } else {
            observation().captureNow(entity, identity)
        }
    }

    /**
     * Schedules a best-effort relation capture for a display miss.  Requests
     * are coalesced by locale/kind/id so a list rendering the same entity through
     * several getter seams creates at most one background task at a time.
     */
    fun captureCatalogMetadataDeferred(entity: Any?) {
        if (entity == null) return
        val identity = identitySnapshot(entity, preferCollectionId = false)
        if (identity.primaryId.isNullOrBlank()) return
        observation().observe(entity, identity, schedule = !isMainThread())
    }

    /** Persists a direct MediaApiResponse entity returned by the batch lookup. */
    fun captureCatalogMetadataForId(id: String, catalog: Any, mediaKind: String) {
        if (id.isBlank()) return
        val attributes = callObject(catalog, "getAttributes") ?: catalog
        val title = displayTitle(attributes) ?: callString(catalog, "getTitle")
        val artist = callString(attributes, "getArtistName")
        val albumName = callString(attributes, "getAlbumName")
        persist(
            listOf(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.TITLE, localeTag, id) to title,
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.SONG, localeTag, id) to
                    title.takeIf { mediaKind == "songs" },
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM, localeTag, id) to
                    title.takeIf { mediaKind == "albums" },
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ARTIST, localeTag, id) to artist,
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM_NAME, localeTag, id) to albumName,
            ),
            writeToDisk = !isMainThread(),
        )
    }

    /** Captures a direct Catalog entity using the same identity candidates as AMTool. */
    fun captureCatalogMetadataForEntity(entity: Any, mediaKind: String) {
        val id = identitySnapshot(entity, preferCollectionId = false).primaryId ?: return
        captureCatalogMetadataForId(id, entity, mediaKind)
    }

    /** Exposes the bounded observation seam to sibling adapters and JVM tests. */
    internal fun observationCoordinatorForTests(): CatalogObservationCoordinator = observation()

    /** Drains cold observations from a worker/test without touching the UI path. */
    internal fun drainCatalogObservations() = observation().drainNow()

    /** Starts storage loading and observation scheduling outside a display getter. */
    internal fun startBackgroundServices() {
        if (!backgroundServicesStarted.compareAndSet(false, true)) return
        backgroundExecutor.execute {
            ensureStorageLoaded()
            flushPendingDisk()
            observation().startBackgroundDrain()
        }
    }

    /** Deterministic worker seam used by JVM regression tests. */
    internal fun flushPendingDiskForTests() = flushPendingDisk()

    /**
     * Captures the catalog relationship entity.  Schema 1 stored a song's
     * album *name* under `catalog-album:` here; schema 2 writes it under
     * `catalog-album-name:` so the album key only ever holds album titles.
     */
    private fun captureCatalogMetadata(
        id: String,
        catalog: Any,
        entityKind: TitleCorrectionPolicy.EntityKind,
        writeToDisk: Boolean = true,
    ) {
        val attributes = callObject(catalog, "getAttributes") ?: catalog
        val title = displayTitle(attributes) ?: callString(catalog, "getTitle")
        val titleEntries = if (entityKind == TitleCorrectionPolicy.EntityKind.ALBUM) {
            listOf(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.TITLE, localeTag, id) to title,
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM, localeTag, id) to title,
            )
        } else {
            listOf(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.SONG, localeTag, id) to title,
            )
        }
        persist(
            titleEntries + listOf(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ARTIST, localeTag, id) to
                    callString(attributes, "getArtistName"),
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM_NAME, localeTag, id) to
                    callString(attributes, "getAlbumName"),
            ),
            writeToDisk = writeToDisk,
        )
    }

    /** Mirrors AMTool qt.w(): Title.getStringForDisplay() first, then name. */
    private fun displayTitle(attributes: Any): String? {
        val title = callObject(attributes, "getTitle")
        return title?.let { callString(it, "getStringForDisplay") }
            ?: callString(attributes, "getName")
    }

    private fun persist(
        valuesToPersist: List<Pair<String, String?>>,
        writeToDisk: Boolean = true,
    ) {
        var scheduleFlush = false
        synchronized(STORAGE_LOCK) {
            if (writeToDisk && isMainThread()) {
                // Never open SharedPreferences from a display getter. Keep the
                // in-memory capture and let a background adapter retry.
                persist(valuesToPersist, writeToDisk = false)
                return
            }
            val prefs = if (writeToDisk) ensureStorageLoaded() else null
            val editor = prefs?.edit()
            var changed = editor?.let(::markSchemaMigrated) ?: false
            val evicted = LinkedHashSet<String>()
            valuesToPersist.forEach { (key, value) ->
                if (!value.isNullOrBlank() && values[key] != value) {
                    evicted += values.put(key, value)
                    if (writeToDisk) pendingDiskKeys.remove(key)
                    editor?.putString(key, value)
                    changed = true
                } else if (!value.isNullOrBlank()) {
                    // A prior UI-thread memory capture may have no persisted
                    // counterpart yet; force one write on the worker.
                    values.put(key, value)
                    if (writeToDisk && (key in pendingDiskKeys || prefs?.getString(key, null) != value)) {
                        pendingDiskKeys.remove(key)
                        editor?.putString(key, value)
                        changed = true
                    }
                }
                if (!writeToDisk && !value.isNullOrBlank()) pendingDiskKeys += key
            }
            editor?.let { pendingEditor ->
                evicted.forEach(pendingEditor::remove)
                if (changed) pendingEditor.apply()
            }
            if (!writeToDisk) pendingDiskKeys += evicted
            scheduleFlush = !writeToDisk &&
                pendingDiskKeys.isNotEmpty() &&
                backgroundServicesStarted.get()
        }
        if (scheduleFlush) schedulePendingDiskFlush()
    }

    /** Flushes memory-only captures from a worker without touching the UI path. */
    private fun flushPendingDisk() {
        val prefs = ensureStorageLoaded() ?: return
        runCatching {
            synchronized(STORAGE_LOCK) {
                if (pendingDiskKeys.isEmpty()) return@synchronized
                val editor = prefs.edit()
                var changed = markSchemaMigrated(editor)
                val clearAfterApply = mutableListOf<String>()
                pendingDiskKeys.toList().forEach { key ->
                    val value = values[key]
                    if (value.isNullOrBlank()) {
                        if (prefs.contains(key)) {
                            editor.remove(key)
                            changed = true
                        }
                    } else if (prefs.getString(key, null) != value) {
                        editor.putString(key, value)
                        changed = true
                    }
                    clearAfterApply += key
                }
                if (changed) editor.apply()
                clearAfterApply.forEach(pendingDiskKeys::remove)
            }
        }.onFailure {
            // Keep pending keys for the next background lifecycle/capture.
        }
    }

    private fun schedulePendingDiskFlush() {
        if (!diskFlushScheduled.compareAndSet(false, true)) return
        runCatching {
            backgroundExecutor.execute {
                try {
                    flushPendingDisk()
                } finally {
                    diskFlushScheduled.set(false)
                }
            }
        }.onFailure {
            diskFlushScheduled.set(false)
        }
    }

    private fun markSchemaMigrated(editor: SharedPreferences.Editor): Boolean {
        if (schemaCurrent) return false
        val legacyAlbumPrefix = "catalog-album:"
        val prefs = preferences ?: return false
        prefs.all.keys
            .filter { it.startsWith(legacyAlbumPrefix) }
            .forEach(editor::remove)
        values.keys
            .filter { it.startsWith(legacyAlbumPrefix) }
            .forEach(values::remove)
        schemaCurrent = true
        editor.putInt(
            TitleCorrectionPolicy.schemaKey(),
            TitleCorrectionPolicy.SCHEMA_VERSION,
        )
        return true
    }

    private fun hasCachedTitle(id: String, kind: TitleCorrectionPolicy.CacheKind): Boolean {
        val keys = when (kind) {
            TitleCorrectionPolicy.CacheKind.ALBUM -> listOfNotNull(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.TITLE, localeTag, id),
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.ALBUM, localeTag, id)
                    .takeIf { schemaCurrent },
            )
            TitleCorrectionPolicy.CacheKind.SONG -> listOf(
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.TITLE, localeTag, id),
                TitleCorrectionPolicy.cacheKey(TitleCorrectionPolicy.CacheKind.SONG, localeTag, id),
            )
            else -> emptyList()
        }
        return keys.any(values::containsKey)
    }

    private fun catalogEntity(entity: Any): Any? = runCatching {
        val relationships = ReflectionMethodCache.find(
            owner = entity.javaClass,
            name = "getRelationships",
        )?.invoke(entity) as? Map<*, *>
        val relationship = relationships?.get("catalog") ?: return null
        val entities = ReflectionMethodCache.find(
            owner = relationship.javaClass,
            name = "getEntities",
        )?.invoke(relationship)
        when (entities) {
            is Array<*> -> entities.firstOrNull()
            is Iterable<*> -> entities.firstOrNull()
            else -> null
        }
    }.getOrNull()

    private fun callObject(receiver: Any, name: String): Any? = runCatching {
        ReflectionMethodCache.find(
            owner = receiver.javaClass,
            name = name,
        )?.invoke(receiver)
    }.getOrNull()

    private fun callString(receiver: Any, name: String): String? = runCatching {
        ReflectionMethodCache.find(
            owner = receiver.javaClass,
            name = name,
            returnType = String::class.java,
        )?.invoke(receiver) as? String
    }.getOrNull()?.takeIf(String::isNotBlank)

    /**
     * Mirrors AMTool qt.b(Object): subscription/id/persistent/cloud/asset.
     * Album owners put getId first; the player collection path puts
     * getCollectionId first so a song's id cannot select the wrong album.
     */
    private fun identityCandidates(receiver: Any, preferCollectionId: Boolean): List<String> {
        val result = LinkedHashSet<String>()
        val entityKind = TitleCorrectionPolicy.entityKindOf(receiver.javaClass.name)
        if (preferCollectionId) addIdentity(result, readIdentity(receiver, "getCollectionId"))
        if (entityKind == TitleCorrectionPolicy.EntityKind.SONG) {
            addIdentity(result, readIdentity(receiver, "getSubscriptionStoreId"))
            addIdentity(result, catalogIdFromPlayParams(receiver))
        }
        if (entityKind == TitleCorrectionPolicy.EntityKind.ALBUM) {
            addIdentity(result, readIdentity(receiver, "getId"))
        }
        addIdentity(result, readIdentity(receiver, "getId"))
        addIdentity(result, readIdentity(receiver, "getSubscriptionStoreId"))
        addIdentity(result, valueAsId(callObject(receiver, "getPersistentId")))
        addIdentity(result, valueAsId(callObject(receiver, "getCloudId")))
        addIdentity(result, valueAsId(callObject(receiver, "getAssetAdamId")))
        return result.toList()
    }

    private fun notifySongCacheMiss(entity: Any, entityKind: TitleCorrectionPolicy.EntityKind) {
        if (entityKind != TitleCorrectionPolicy.EntityKind.SONG) return
        val id = readIdentity(entity, "getSubscriptionStoreId")
            ?: catalogIdFromPlayParams(entity)
            ?: readIdentity(entity, "getId")
            ?: return
        runCatching { missListener.onSongCacheMiss(id) }
    }

    private fun catalogIdFromPlayParams(receiver: Any): String? {
        val attributes = callObject(receiver, "getAttributes") ?: return null
        val playParams = callObject(attributes, "getPlayParams") ?: return null
        return readIdentity(playParams, "getCatalogId")
    }

    /** Builds one immutable identity snapshot for a display seam. */
    private fun identitySnapshot(receiver: Any, preferCollectionId: Boolean): CatalogIdentitySnapshot =
        CatalogIdentitySnapshot(
            ids = identityCandidates(receiver, preferCollectionId),
            entityKind = TitleCorrectionPolicy.entityKindOf(receiver.javaClass.name),
            preferCollectionId = preferCollectionId,
        )

    /** Resolves identity once so a multi-field bind can reuse it. */
    internal fun identitySnapshotFor(
        receiver: Any?,
        preferCollectionId: Boolean = false,
    ): CatalogIdentitySnapshot? = receiver
        ?.let { identitySnapshot(it, preferCollectionId) }
        ?.takeIf { it.ids.isNotEmpty() }

    /** Cache-only correction using a precomputed identity snapshot. */
    internal fun correctedForIdentity(
        identity: CatalogIdentitySnapshot?,
        raw: String?,
        kind: TitleCorrectionPolicy.CacheKind,
    ): String? {
        if (identity == null || raw.isNullOrBlank()) return null
        return identity.ids.asSequence()
            .mapNotNull { id -> correctedById(id, raw, kind, identity.entityKind) }
            .firstOrNull()
    }

    internal fun correctedTitleForIdentity(
        identity: CatalogIdentitySnapshot?,
        raw: String?,
    ): String? = correctedForIdentity(
        identity = identity,
        raw = raw,
        kind = if (identity?.entityKind == TitleCorrectionPolicy.EntityKind.ALBUM) {
            TitleCorrectionPolicy.CacheKind.ALBUM
        } else {
            TitleCorrectionPolicy.CacheKind.SONG
        },
    )

    internal fun attributesOwner(attributes: Any?): Any? = attributes
        ?.let { attributesOwners[it]?.get() }

    /** Requests one cold display observation without relation/network work on the UI caller. */
    internal fun observeDisplayMiss(entity: Any?, identity: CatalogIdentitySnapshot?) {
        val snapshot = identity ?: return
        if (entity == null || snapshot.primaryId.isNullOrBlank()) return
        if (isMainThread()) {
            observation().observe(entity, snapshot, schedule = false)
        } else {
            observation().captureNow(entity, snapshot)
        }
    }

    /** Lazily creates the relation adapter; construction itself performs no I/O. */
    private val localObservationCoordinator: CatalogObservationCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        CatalogObservationCoordinator(
            adapter = CatalogObservationAdapter { request ->
                val id = request.identity.primaryId ?: return@CatalogObservationAdapter CatalogObservationResult(false)
                val catalog = catalogEntity(request.entity)
                if (catalog == null) {
                    if (request.identity.entityKind == TitleCorrectionPolicy.EntityKind.SONG) {
                        runCatching { missListener.onSongCacheMiss(id) }
                    }
                    return@CatalogObservationAdapter CatalogObservationResult(
                        captured = false,
                        missId = id.takeIf {
                            request.identity.entityKind == TitleCorrectionPolicy.EntityKind.SONG
                        },
                    )
                }
                captureCatalogMetadata(id, catalog, request.identity.entityKind)
                CatalogObservationResult(captured = true)
            },
            scheduler = observationScheduler,
        )
    }

    private fun observation(): CatalogObservationCoordinator =
        observationCoordinator ?: localObservationCoordinator

    /** Opens and loads SharedPreferences only from a non-main caller. */
    private fun ensurePreferences(): SharedPreferences? {
        preferences?.let { return it }
        if (isMainThread()) return null
        return synchronized(storageLoadLock) {
            preferences ?: runCatching {
                application.getSharedPreferences(PREFERENCES_NAME, 0)
            }.getOrNull()?.also { preferences = it }
        }
    }

    private fun ensureStorageLoaded(): SharedPreferences? {
        ensurePreferences()?.let { prefs ->
            if (storageLoaded.compareAndSet(false, true)) {
                synchronized(STORAGE_LOCK) {
                    schemaCurrent = TitleCorrectionPolicy.isCurrentSchema(
                        prefs.getInt(
                            TitleCorrectionPolicy.schemaKey(),
                            TitleCorrectionPolicy.LEGACY_SCHEMA,
                        ),
                    )
                    val evicted = prefs.all.entries
                        .asSequence()
                        .filter { (key, value) -> value is String && isCacheKey(key) }
                        .sortedBy { it.key }
                        .flatMap { (key, value) ->
                            if (values.containsKey(key)) {
                                emptySequence()
                            } else {
                                values.put(key, value as String).asSequence()
                            }
                        }
                        .toCollection(LinkedHashSet())
                    removePersisted(evicted, prefs)
                }
            }
            return prefs
        }
        return null
    }

    /** AMTool converts every identity candidate through toString(), not only String returns. */
    private fun readIdentity(receiver: Any, methodName: String): String? =
        valueAsId(callObject(receiver, methodName))

    private fun addIdentity(target: MutableSet<String>, value: String?) {
        value?.takeIf(String::isNotBlank)?.let(target::add)
    }

    private fun valueAsId(value: Any?): String? = when (value) {
        is Number -> value.toLong().toString()
        is String -> value
        else -> value?.toString()
    }?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    private fun removePersisted(keys: Collection<String>, prefs: SharedPreferences? = preferences) {
        if (keys.isEmpty()) return
        prefs?.edit()?.let { editor ->
            keys.forEach(editor::remove)
            editor.apply()
        }
    }

    private fun isMainThread(): Boolean = mainThread()

    private fun isCacheKey(key: String): Boolean = CACHE_KEY_PREFIXES.any(key::startsWith)

    private companion object {
        const val PREFERENCES_NAME = "am++-catalog-title-cache"
        const val MAX_ENTRIES = 8_192
        val STORAGE_LOCK = Any()
        val CACHE_KEY_PREFIXES = TitleCorrectionPolicy.CacheKind.values().map { kind ->
            TitleCorrectionPolicy.cacheKey(kind, "", "").substringBefore(":") + ":"
        }
    }
}
