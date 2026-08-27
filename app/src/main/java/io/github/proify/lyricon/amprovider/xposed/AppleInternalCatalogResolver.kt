package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AppleInternalCatalogResolver(
    context: Context,
    private val classLoader: ClassLoader,
    private val hookResolver: AppleMusicHookResolver,
    private val mainHandler: Handler
) {
    /**
     * Catalog response parsing is pure CPU work once the host response has been snapshotted.
     * Keep this executor private to the resolver so no host object or UI callback escapes the
     * explicit main/CPU boundary.
     */
    private val catalogBackgroundExecutor: Executor = Executors.newFixedThreadPool(
        2,
    ) { task ->
        Thread(task, "AM++ Catalog CPU").apply { isDaemon = true }
    }
    private val catalogResponseDispatcher = AppleCatalogResponseWorkDispatcher(
        mainExecutor = Executor { runnable ->
            mainHandler.post(runnable)
        },
        backgroundExecutor = catalogBackgroundExecutor,
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
    )
    private val persistentLocalizedCache = AppleLocalizedMetadataCache(context, mainHandler)
    private val persistentOriginalCache = AppleOriginalMetadataCache(context, mainHandler)
    private val resolvedCatalogHolder by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
    }
    private val cache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > CACHE_SIZE
    }
    private val inFlight = mutableMapOf<String, MutableList<(OriginalResolution) -> Unit>>()
    private val originalCandidateCallbacks =
        mutableMapOf<String, MutableList<(Alias) -> Unit>>()
    private val catalogIdentityCache = ConcurrentHashMap<String, CatalogIdentity>()
    private val catalogIdentityInFlight =
        mutableMapOf<String, MutableList<(CatalogIdentity) -> Unit>>()
    /**
     * Artist-region evidence is shared only after a confirmed song resolution.
     * Storefront-localized artist display names are not original-region evidence.
     */
    private val originalArtistLanguageCache = ConcurrentHashMap<String, String>()
    private val originalEntityPending = LinkedHashMap<String, OriginalEntityRequest>()
    private var originalEntityBatchScheduled = false
    private var originalEntityBatchesRunning = 0
    private var originalEntityBackgroundBatchesRunning = 0
    private val localizedCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > LOCALIZED_CACHE_SIZE
    }
    private val localizedArtistAliasCache =
        object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Alias>?,
            ): Boolean = size > LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
        }
    private val localizedInFlight =
        mutableMapOf<String, MutableList<(Alias?) -> Unit>>()
    private val localizedPending = LinkedHashMap<String, LocalizedRequest>()
    private var localizedBatchScheduled = false
    private var localizedBatchesRunning = 0
    private var localizedBackgroundBatchesRunning = 0
    private val requestPriorityByMediaId =
        object : LinkedHashMap<String, RequestPriority>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, RequestPriority>?,
            ): Boolean = size > REQUEST_PRIORITY_CACHE_SIZE
        }
    @Volatile
    private var requestScopeActive = false
    private var requestScopeRevision = -1L
    private val warmedSelections = mutableSetOf<Int>()
    private val warmingSelections = mutableSetOf<Int>()
    @Volatile
    private var persistentLocalizedCacheEnabled = true
    private var catalogAccess: CatalogAccess? = null
    @Volatile
    private var accountStorefront: String? = null
    @Volatile
    private var accountStorefrontCaptured = false
    @Volatile
    private var lastAppliedConfiguredStorefront: String? = null
    private val activeCatalogRequest = ThreadLocal<CatalogRequestLocalization?>()
    private val pendingCatalogRequests = ConcurrentHashMap<String, CatalogRequestLocalization>()
    private val catalogRequestSequence = AtomicLong()
    private val catalogDiagnosticSequence = AtomicLong()
    @Volatile
    private var contentUiLanguageSelection =
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE

    /**
     * Applies the content storefront without changing the account's original value.
     * The MediaApi storefront is also used by Apple Music for localized content, so
     * keeping this value in one place covers both startup and post-query recovery.
     */
    fun applyContentUiLanguage(selection: Int) {
        contentUiLanguageSelection = selection
        warmPersistentLocalizedCache(selection)
        if (activeCatalogRequest.get() != null) return
        runCatching {
            val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
            restoreConfiguredStorefront(access)
        }.onFailure {
            ProviderLogger.error("Apple 内容 UI storefront 应用失败: selection=$selection", it)
        }
    }

    fun setPersistentLocalizedCacheEnabled(enabled: Boolean) {
        val wasEnabled = persistentLocalizedCacheEnabled
        persistentLocalizedCacheEnabled = enabled
        persistentLocalizedCache.setEnabled(enabled)
        persistentOriginalCache.setEnabled(enabled)
        if (enabled) {
            warmPersistentOriginalCache()
            if (!wasEnabled) {
                synchronized(warmedSelections) {
                    warmedSelections.remove(contentUiLanguageSelection)
                }
                synchronized(warmingSelections) {
                    warmingSelections.remove(contentUiLanguageSelection)
                }
            }
            warmPersistentLocalizedCache(contentUiLanguageSelection)
        }
    }

    private fun warmPersistentOriginalCache() {
        if (!persistentLocalizedCacheEnabled) return
        persistentOriginalCache.warmRecentAsync { count ->
            if (count != null) {
                ProviderLogger.info("Apple 原地区元数据缓存预热完成: entries=$count")
            } else {
                ProviderLogger.info("Apple 原地区元数据缓存预热延后")
            }
        }
    }

    fun cachedLocalizedArtist(selection: Int, artistKeys: Collection<String>): Alias? {
        val languageTags = languageTagsForContentUiLanguage(selection)
        val keys = artistKeys.flatMap { key ->
            languageTags.map { language -> artistCacheKey(selection, key, language) } +
                if (languageTags.size == 1) listOf(artistCacheKey(selection, key)) else emptyList()
        }
        return synchronized(localizedArtistAliasCache) {
            keys.firstNotNullOfOrNull(localizedArtistAliasCache::get)
        }
    }

    fun cachedLocalizedMetadata(
        selection: Int,
        entityType: LocalizedEntityType,
        mediaId: String,
    ): Alias? {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) return null
        val languageTags = languageTagsForContentUiLanguage(selection)
        val keys = languageTags.map { language ->
            localizedMetadataCacheKey(selection, entityType, normalizedId, language)
        } + if (languageTags.size == 1) {
            listOf(localizedMetadataCacheKey(selection, entityType, normalizedId))
        } else {
            emptyList()
        }
        return synchronized(localizedCache) {
            keys.firstNotNullOfOrNull(localizedCache::get)
        }
    }

    fun rememberLocalizedArtist(
        selection: Int,
        artistKeys: Collection<String>,
        localizedArtist: String,
        language: String? = null,
    ) {
        if (localizedArtist.isBlank()) return
        val alias = Alias(title = "", artist = localizedArtist, language = "", album = "")
        val entries = artistKeys
            .map { artistCacheKey(selection, it, language) }
            .distinct()
            .associateWith { alias }
        if (entries.isEmpty()) return
        val changedEntries = synchronized(localizedArtistAliasCache) {
            entries.filter { (key, value) -> localizedArtistAliasCache[key] != value }
                .also(localizedArtistAliasCache::putAll)
        }
        persistentLocalizedCache.putMany(changedEntries)
    }

    private fun warmPersistentLocalizedCache(selection: Int) {
        if (!persistentLocalizedCacheEnabled) return
        if (storefrontForContentUiLanguage(selection) == null) return
        val shouldWarm = synchronized(warmedSelections) {
            if (selection in warmedSelections) false
            else synchronized(warmingSelections) { warmingSelections.add(selection) }
        }
        if (!shouldWarm) return
        val prefix = "$selection:"
        persistentLocalizedCache.warmRecentAsync(prefix) { delayedAliases ->
            if (delayedAliases != null) {
                finishPersistentCacheWarm(selection, delayedAliases)
            } else {
                synchronized(warmingSelections) { warmingSelections.remove(selection) }
                ProviderLogger.info("Apple 地区元数据缓存预热延后: selection=$selection")
            }
        }
    }

    private fun finishPersistentCacheWarm(selection: Int, aliases: Map<String, Alias>) {
        val artistAliases = aliases.filterKeys(::isLocalizedArtistAliasCacheKey)
        val metadataAliases = aliases.filterKeys { key ->
            !isLocalizedArtistAliasCacheKey(key)
        }
        synchronized(localizedCache) { localizedCache.putAll(metadataAliases) }
        synchronized(localizedArtistAliasCache) {
            localizedArtistAliasCache.putAll(artistAliases)
        }
        synchronized(warmedSelections) { warmedSelections.add(selection) }
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
        ProviderLogger.info(
            "Apple 地区元数据缓存预热完成: selection=$selection, " +
                "metadata=${metadataAliases.size}, artistAlias=${artistAliases.size}, " +
                "entries=${aliases.size}"
        )
    }

    fun languageTagForCurrentRequest(selection: Int): String? =
        activeCatalogRequest.get()?.language ?: languageTagForContentUiLanguage(selection)

    fun catalogRequestLocalization(token: String?): CatalogRequestLocalization? =
        token?.let(pendingCatalogRequests::get)

    fun pendingCatalogRequestCount(): Int = pendingCatalogRequests.size

    fun cachedCatalogGenres(mediaId: String): List<String> =
        catalogIdentityCache[mediaId]?.genres.orEmpty()

    fun accountStorefrontForPlaybackRequest(): String? {
        accountStorefront?.let { return it }
        return runCatching {
            val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
            captureAccountStorefront(access)
            accountStorefront
        }.getOrNull()
    }

    private fun restoreConfiguredStorefront(access: CatalogAccess) {
        captureAccountStorefront(access)
        val selection = contentUiLanguageSelection
        val configuredStorefront = storefrontForContentUiLanguage(selection)
        if (configuredStorefront == null && !accountStorefrontCaptured) return
        val target = configuredStorefront ?: accountStorefront
        val previous = access.storefrontField.get(access.mediaApi) as? String
        access.storefrontField.set(access.mediaApi, target)
        lastAppliedConfiguredStorefront = configuredStorefront
        if (previous != target) {
            ProviderLogger.info(
                "Apple 内容 UI storefront 已应用: selection=$selection, " +
                    "previous=${previous ?: "unset"}, " +
                    "storefront=${target ?: "account-default"}, " +
                    "accountStorefront=${accountStorefront ?: "account-default"}"
            )
        }
    }

    private fun captureAccountStorefront(access: CatalogAccess) {
        val current = access.storefrontField.get(access.mediaApi) as? String ?: return
        if (!accountStorefrontCaptured || current != lastAppliedConfiguredStorefront) {
            accountStorefront = current
            accountStorefrontCaptured = true
        }
    }

    fun resolve(metadata: MediaMetadataCache.Metadata, onResolved: (Alias?) -> Unit) {
        resolveOriginalMetadata(metadata, RequestPriority.ACTIVE_PAGE) { resolution ->
            onResolved(resolution.alias)
        }
    }

    fun resolveOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (OriginalResolution) -> Unit,
    ) = resolveOriginalMetadata(
        metadata = metadata,
        onCandidate = null,
        priority = priority,
        onResolved = onResolved,
    )

    fun resolveOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
        onCandidate: ((Alias) -> Unit)?,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (OriginalResolution) -> Unit,
    ) {
        rememberRequestPriority(metadata.id, priority)
        if (!shouldResolve(metadata)) {
            onResolved(
                OriginalResolution(
                    alias = null,
                    language = null,
                    originKnown = false,
                    artistIds = emptyList(),
                )
            )
            return
        }
        synchronized(cache) {
            cache[metadata.id]?.let { alias ->
                canonicalCachedOriginalAlias(alias)?.takeIf { cachedAlias ->
                    isReusableOriginalSongAlias(
                        alias = cachedAlias,
                        localizedTitle = metadata.title.orEmpty(),
                        localizedArtist = metadata.artist.orEmpty(),
                        allowArtistOnly = true,
                    )
                }?.let { cachedAlias ->
                    if (cachedAlias != alias) cache[metadata.id] = cachedAlias
                    onResolved(
                        OriginalResolution(
                            alias = cachedAlias,
                            language = cachedAlias.language.takeIf(String::isNotBlank),
                            originKnown = true,
                            artistIds = emptyList(),
                            album = cachedAlias.album,
                            trustedArtistOnlyLanguages = setOf(
                                canonicalOriginalLanguage(cachedAlias.language),
                            ),
                        )
                    )
                    return
                }
                cache.remove(metadata.id)
            }
        }
        onCandidate?.let { callback ->
            registerOriginalCandidateCallback(metadata.id, callback)
        }
        synchronized(inFlight) {
            val callbacks = inFlight[metadata.id]
            if (callbacks != null) {
                callbacks.add(onResolved)
                return
            }
            inFlight[metadata.id] = mutableListOf(onResolved)
        }

        persistentOriginalCache.get(originalSongCacheKey(metadata.id)) { persistentAlias ->
            val reusableAlias = persistentAlias?.takeIf { alias ->
                isReusableOriginalSongAlias(
                    alias = alias,
                    localizedTitle = metadata.title.orEmpty(),
                    localizedArtist = metadata.artist.orEmpty(),
                    allowArtistOnly = true,
                )
            }
            if (reusableAlias != null) {
                synchronized(cache) { cache[metadata.id] = reusableAlias }
                finishCachedOriginalResolve(metadata.id, reusableAlias)
            } else {
                if (persistentAlias != null) {
                    persistentOriginalCache.remove(originalSongCacheKey(metadata.id))
                }
                persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(metadata.id))
                resolveOriginalMetadataFromCatalog(metadata, priority = priority)
            }
        }
    }

    private fun resolveOriginalMetadataFromCatalog(
        metadata: MediaMetadataCache.Metadata,
        allowEmptyIdentityRetry: Boolean = true,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    ) {
        val fallbackLanguages = if (AppleOriginalMetadataPolicy.isCjkGenre(metadata.genre)) {
            languageTagsForGenre(metadata.genre)
        } else {
            emptyList()
        }
        resolveCatalogIdentity(metadata.id, fallbackLanguages) { identity ->
            identity.fallbackAliases.firstOrNull()?.let { alias ->
                publishOriginalCandidate(metadata.id, alias)
            }
            if (allowEmptyIdentityRetry && shouldRetryEmptyCatalogIdentity(
                    mediaId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artist,
                    genre = metadata.genre,
                    isrc = identity.isrc,
                    catalogGenres = identity.genres,
                )
            ) {
                ProviderLogger.info(
                    "Apple 内部歌曲空身份重试: id=${metadata.id}, " +
                        "title=${metadata.title}, artist=${metadata.artist}"
                )
                mainHandler.post {
                    resolveOriginalMetadataFromCatalog(
                        metadata = metadata,
                        allowEmptyIdentityRetry = false,
                        priority = currentRequestPriority(metadata.id, priority),
                    )
                }
                return@resolveCatalogIdentity
            }
            val results = identity.fallbackAliases.toMutableList()
            val isrc = identity.isrc
            val singleArtistId = identity.artistIds.singleOrNull()
                ?.trim()
                ?.takeIf { artistId ->
                    artistId.isNotEmpty() &&
                        artistId.all(Char::isDigit) &&
                        !isCollaborationArtistName(metadata.artist.orEmpty())
                }
            val cachedArtistLanguage = singleArtistId?.let { artistId ->
                cachedOriginalArtistRegion(listOf("id:$artistId"))
            }
            val originKnownFromArtist = cachedArtistLanguage != null
            val trustedArtistOnlyLanguages = (
                languageTagsForIsrc(isrc) + listOfNotNull(cachedArtistLanguage)
            ).map(::canonicalOriginalLanguage).toSet()
            val languages = languageTagsForOriginalMetadata(
                genre = metadata.genre,
                catalogGenres = identity.genres,
                isrc = isrc,
                artistLanguages = listOfNotNull(cachedArtistLanguage),
            )
            if (languages.isEmpty()) {
                finishResolve(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = isrc != null || originKnownFromArtist,
                    artistIds = identity.artistIds,
                    trustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                )
                return@resolveCatalogIdentity
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishResolve(
                        metadata = metadata,
                        languages = languages,
                        results = results,
                        originKnown = isrc != null || originKnownFromArtist,
                        artistIds = identity.artistIds,
                        trustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                    )
                    return
                }
                val language = languages[index]
                selectExactIdentityAlias(identity.fallbackAliases, language)?.let { exactAlias ->
                    finishResolve(
                        metadata = metadata,
                        languages = listOf(language),
                        results = listOf(exactAlias),
                        originKnown = true,
                        artistIds = identity.artistIds,
                        trustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                    )
                    return
                }
                resolveOriginalEntityForLanguage(
                    mediaId = metadata.id,
                    lookupIds = listOf(metadata.id),
                    entityType = LocalizedEntityType.SONG,
                    language = language,
                    priority = currentRequestPriority(metadata.id, priority),
                ) { resolvedAlias ->
                    val exactAlias = resolvedAlias?.takeIf { alias ->
                        (alias.title.isNotBlank() || alias.artist.isNotBlank()) &&
                            isConfidentOriginalSongAlias(
                                alias = alias,
                                localizedTitle = metadata.title.orEmpty(),
                                localizedArtist = metadata.artist.orEmpty(),
                                allowArtistOnly =
                                    canonicalOriginalLanguage(language) in
                                        trustedArtistOnlyLanguages,
                            )
                    }
                    if (exactAlias != null) {
                        val regionalArtistIds =
                            catalogIdentityCache[metadata.id]?.artistIds.orEmpty()
                        finishResolve(
                            metadata = metadata,
                            languages = listOf(language),
                            results = listOf(exactAlias),
                            originKnown = true,
                            artistIds = (
                                identity.artistIds + regionalArtistIds
                            ).distinct(),
                            trustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                        )
                    } else {
                        if (resolvedAlias != null) {
                            invalidateOriginalEntity(metadata.id, LocalizedEntityType.SONG)
                        }
                        if (isrc == null) {
                            queryNext(index + 1)
                        } else {
                            queryByIsrc(isrc, language) { song ->
                                song?.alias?.let(results::add)
                                queryNext(index + 1)
                            }
                        }
                    }
                }
            }
            queryNext(0)
        }
    }

    fun resolveOriginalEntityForLanguage(
        mediaId: String,
        lookupIds: Collection<String>,
        entityType: LocalizedEntityType,
        language: String,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (Alias?) -> Unit,
    ) {
        rememberRequestPriority(mediaId, priority)
        val targetLanguage = supportedOriginalLanguageOrNull(language)
        if (targetLanguage == null) {
            ProviderLogger.info(
                "Apple 原地区元数据查询忽略: id=$mediaId, entityType=$entityType, " +
                    "reason=unsupported_language, language=$language"
            )
            onResolved(null)
            return
        }
        val storefront = storefrontForLanguage(targetLanguage)
        val ids = (listOf(mediaId) + lookupIds)
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
        if (ids.isEmpty()) {
            onResolved(null)
            return
        }
        val directCacheKey = originalDirectEntityCacheKey(entityType, mediaId)
        persistentOriginalCache.getFirst(
            keys = originalEntityCacheLookupKeys(
                entityType = entityType,
                mediaId = mediaId,
                lookupIds = ids,
                languages = listOf(targetLanguage),
            ),
            accept = { alias -> isAcceptableOriginalAlias(alias, targetLanguage) },
            onResult = { hit ->
                if (hit != null) {
                    if (hit.key != directCacheKey) {
                        // Promote old language/alternate-ID entries into the current direct key.
                        persistentOriginalCache.put(directCacheKey, hit.alias)
                    }
                    onResolved(hit.alias)
                    return@getFirst
                }
                if (entityType == LocalizedEntityType.SONG) {
                    persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(mediaId))
                }
                enqueueOriginalEntityRequest(
                    OriginalEntityRequest(
                        requestKey = "$entityType:$targetLanguage:$mediaId:${ids.joinToString(",")}",
                        mediaId = mediaId,
                        lookupIds = ids,
                        entityType = entityType,
                        language = targetLanguage,
                        storefront = storefront,
                        directCacheKey = directCacheKey,
                        priority = currentRequestPriority(mediaId, priority),
                        callbacks = listOf(onResolved),
                    )
                )
            },
        )
    }

    fun invalidateOriginalEntity(mediaId: String, entityType: LocalizedEntityType) {
        persistentOriginalCache.remove(originalDirectEntityCacheKey(entityType, mediaId))
    }

    private fun enqueueOriginalEntityRequest(request: OriginalEntityRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(originalEntityPending) {
            val existing = originalEntityPending[prioritized.requestKey]
            originalEntityPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                    callbacks = existing.callbacks + prioritized.callbacks,
                )
            }
            if (
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processOriginalEntityBatch, ORIGINAL_ENTITY_BATCH_DELAY_MS)
        }
    }

    private fun processOriginalEntityBatch() {
        val batch = synchronized(originalEntityPending) {
            originalEntityBatchScheduled = false
            if (
                originalEntityPending.isEmpty() ||
                !canStartOriginalEntityBatchLocked()
            ) return
            val pendingValues = originalEntityPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(OriginalEntityRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<OriginalEntityRequest>()
            val selectedIds = linkedSetOf<String>()
            originalEntityPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (
                        selected.isNotEmpty() &&
                        selectedIds.size + newIds.size > ORIGINAL_ENTITY_BATCH_SIZE
                    ) return@forEach
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { originalEntityPending.remove(it.requestKey) }
            originalEntityBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                originalEntityBackgroundBatchesRunning += 1
            }
            selected
        }

        val first = batch.first()
        queryByConfiguredRegion(
            mediaIds = batch.flatMap(OriginalEntityRequest::lookupIds).distinct(),
            entityType = first.entityType,
            storefront = first.storefront,
            language = first.language,
        ) { resolved ->
            // Matching IDs and validating language/alias candidates are pure CPU work.  Keep the
            // mutable caches and resolver callbacks on the main executor after this pre-processing.
            catalogBackgroundExecutor.execute {
                val matches = runCatching {
                    val resolvedAliases = resolved.mapValues { it.value.alias }
                    batch.map { request ->
                        request to selectExactOriginalEntityAlias(
                            mediaId = request.mediaId,
                            lookupIds = request.lookupIds,
                            resolved = resolvedAliases,
                            sourceLanguage = request.language,
                        )
                    }
                }.onFailure { error ->
                    ProviderLogger.error("Apple 原地区实体后台候选匹配失败", error)
                }.getOrElse {
                    batch.map { request -> request to null }
                }
                mainHandler.post {
                    matches.forEach { (request, alias) ->
                        if (alias != null) {
                            persistentOriginalCache.put(request.directCacheKey, alias)
                        }
                        ProviderLogger.info(
                            "Apple 原地区实体查询完成: id=${request.mediaId}, " +
                                "entityType=${request.entityType}, language=${request.language}, " +
                                "batch=${batch.size}, priority=${request.priority}, hit=${alias != null}, " +
                                "value=${alias?.title}/${alias?.artist}/${alias?.album}"
                        )
                        request.callbacks.forEach { callback -> callback(alias) }
                    }
                    synchronized(originalEntityPending) {
                        originalEntityBatchesRunning -= 1
                        if (first.priority == RequestPriority.BACKGROUND) {
                            originalEntityBackgroundBatchesRunning -= 1
                        }
                    }
                    scheduleOriginalEntityBatchIfCapacity()
                }
            }
        }
        scheduleOriginalEntityBatchIfCapacity()
    }

    private fun scheduleOriginalEntityBatchIfCapacity() {
        val shouldSchedule = synchronized(originalEntityPending) {
            if (
                originalEntityPending.isEmpty() ||
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processOriginalEntityBatch)
    }

    private fun canStartOriginalEntityBatchLocked(): Boolean {
        val nextPriority = originalEntityPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = originalEntityBatchesRunning,
            backgroundRunning = originalEntityBackgroundBatchesRunning,
            maxRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING,
        )
    }

    fun resolveCachedOriginalEntity(
        mediaId: String,
        entityType: LocalizedEntityType,
        onResolved: (Alias?) -> Unit,
        lookupIds: Collection<String> = emptyList(),
    ) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) {
            onResolved(null)
            return
        }
        val directKey = originalDirectEntityCacheKey(entityType, normalizedId)
        persistentOriginalCache.getFirst(
            keys = originalEntityCacheLookupKeys(
                entityType = entityType,
                mediaId = normalizedId,
                lookupIds = lookupIds,
            ),
            onResult = { hit ->
                val validAlias = hit?.alias?.takeIf {
                    isAcceptableOriginalAlias(it, canonicalOriginalLanguage(it.language))
                }
                if (validAlias != null && hit.key != directKey) {
                    // Promote compatibility/alternate-ID hits so subsequent home builders use the
                    // same fast direct path as the library page.
                    persistentOriginalCache.put(directKey, validAlias)
                }
                if (entityType == LocalizedEntityType.SONG) {
                    persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(normalizedId))
                }
                onResolved(validAlias)
            },
        )
    }

    /**
     * Reads only the warmed/visited memory entries for the current ID and compatibility IDs.
     * SQLite remains asynchronous in [resolveCachedOriginalEntity].
     */
    fun cachedOriginalEntity(
        mediaId: String,
        entityType: LocalizedEntityType,
        lookupIds: Collection<String> = emptyList(),
    ): Alias? {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) return null
        val keys = originalEntityCacheLookupKeys(
            entityType = entityType,
            mediaId = normalizedId,
            lookupIds = lookupIds,
        )
        val directKey = originalDirectEntityCacheKey(entityType, normalizedId)
        val hit = keys.firstNotNullOfOrNull { key ->
            persistentOriginalCache.cached(key)?.let { alias ->
                alias.takeIf {
                    isAcceptableOriginalAlias(it, canonicalOriginalLanguage(it.language))
                }?.let { valid -> AppleOriginalMetadataCache.CacheHit(key, valid) }
            }
        }
        if (hit != null && hit.key != directKey) {
            persistentOriginalCache.put(directKey, hit.alias)
        }
        if (entityType == LocalizedEntityType.SONG) {
            persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(normalizedId))
        }
        return hit?.alias
    }

    fun cachedOriginalArtistRegion(artistKeys: Collection<String>): String? {
        val keys = artistKeys.map(String::trim).filter(String::isNotEmpty).distinct()
        keys.firstNotNullOfOrNull(originalArtistLanguageCache::get)?.let { return it }
        val persisted = persistentOriginalCache.cachedArtistRegion(keys) ?: return null
        val canonical = supportedOriginalLanguageOrNull(persisted) ?: return null
        keys.forEach { key -> originalArtistLanguageCache[key] = canonical }
        return canonical
    }

    fun rememberOriginalArtistRegion(artistKeys: Collection<String>, language: String) {
        val canonical = supportedOriginalLanguageOrNull(language) ?: return
        val keys = artistKeys.map(String::trim).filter(String::isNotEmpty).distinct()
        if (keys.isEmpty()) return
        keys.forEach { key -> originalArtistLanguageCache[key] = canonical }
        persistentOriginalCache.rememberArtistRegion(keys, canonical)
    }

    fun resolveForContentUiLanguage(
        mediaId: String,
        selection: Int,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (Alias?) -> Unit,
    ) = resolveForContentUiLanguage(
        mediaId = mediaId,
        lookupIds = listOf(mediaId),
        entityType = LocalizedEntityType.SONG,
        selection = selection,
        priority = priority,
        onResolved = onResolved,
    )

    fun resolveForContentUiLanguage(
        mediaId: String,
        lookupIds: Collection<String>,
        entityType: LocalizedEntityType,
        selection: Int,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (Alias?) -> Unit,
    ) {
        resolveManyForContentUiLanguage(
            lookups = listOf(LocalizedLookup(mediaId, lookupIds, entityType)),
            selection = selection,
            priority = priority,
        ) { resolvedId, alias ->
            if (resolvedId == mediaId) onResolved(alias)
        }
    }

    fun resolveManyForContentUiLanguage(
        lookups: Collection<LocalizedLookup>,
        selection: Int,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
        onResolved: (mediaId: String, alias: Alias?) -> Unit,
    ) {
        val storefront = storefrontForContentUiLanguage(selection)
        val languages = languageTagsForContentUiLanguage(selection)
        if (storefront == null || languages.isEmpty()) {
            lookups.forEach { onResolved(it.mediaId, null) }
            return
        }

        val invalidLookups = lookups.filterNot { lookup ->
            lookup.mediaId.trim().let { it.isNotEmpty() && it.all(Char::isDigit) }
        }
        invalidLookups.forEach { onResolved(it.mediaId, null) }
        val validLookups = lookups.filterNot { lookup -> lookup in invalidLookups }
        if (validLookups.isEmpty()) return
        if (languages.size == 1) {
            resolveManyForContentUiLanguageSingleLanguage(
                lookups = validLookups,
                selection = selection,
                priority = priority,
                storefront = storefront,
                language = languages.first(),
                onResolved = onResolved,
            )
            return
        }

        val resolvedKeys = mutableSetOf<String>()
        fun lookupKey(mediaId: String, entityType: LocalizedEntityType): String =
            "${entityType.name}:${mediaId.trim()}"

        fun resolveLanguage(index: Int, pending: List<LocalizedLookup>) {
            if (pending.isEmpty()) return
            if (index >= languages.size) {
                pending.forEach { lookup ->
                    if (resolvedKeys.add(lookupKey(lookup.mediaId, lookup.entityType))) {
                        onResolved(lookup.mediaId, null)
                    }
                }
                return
            }
            val language = languages[index]
            resolveManyForContentUiLanguageSingleLanguage(
                lookups = pending,
                selection = selection,
                priority = priority,
                storefront = storefront,
                language = language,
                onResolved = { mediaId, alias ->
                    if (alias != null) {
                        pending
                            .filter { it.mediaId.trim() == mediaId.trim() }
                            .forEach { lookup ->
                                if (resolvedKeys.add(lookupKey(mediaId, lookup.entityType))) {
                                    onResolved(mediaId, alias)
                                }
                            }
                    }
                },
                onComplete = {
                    val unresolved = pending.filter { lookup ->
                        lookupKey(lookup.mediaId, lookup.entityType) !in resolvedKeys
                    }
                    resolveLanguage(index + 1, unresolved)
                },
            )
        }
        resolveLanguage(0, validLookups)
    }

    private fun resolveManyForContentUiLanguageSingleLanguage(
        lookups: Collection<LocalizedLookup>,
        selection: Int,
        priority: RequestPriority,
        storefront: String,
        language: String,
        onResolved: (mediaId: String, alias: Alias?) -> Unit,
        onComplete: () -> Unit = {},
    ) {
        val requests = lookups.asSequence()
            .mapNotNull { lookup ->
                val mediaId = lookup.mediaId.trim()
                if (mediaId.isEmpty() || !mediaId.all(Char::isDigit)) return@mapNotNull null
                val normalizedLookupIds = sequenceOf(mediaId)
                    .plus(lookup.lookupIds.asSequence())
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                    .distinct()
                    .take(LOCALIZED_BATCH_SIZE)
                    .toList()
                val cacheKey = localizedMetadataCacheKey(
                    selection,
                    lookup.entityType,
                    mediaId,
                    language,
                )
                rememberRequestPriority(mediaId, priority)
                LocalizedRequest(
                    cacheKey = cacheKey,
                    requestKey = "$cacheKey:${normalizedLookupIds.joinToString(",")}".trim(),
                    mediaId = mediaId,
                    lookupIds = normalizedLookupIds,
                    entityType = lookup.entityType,
                    selection = selection,
                    storefront = storefront,
                    language = language,
                    priority = currentRequestPriority(mediaId, priority),
                )
            }
            .distinctBy(LocalizedRequest::requestKey)
            .toList()
        if (requests.isEmpty()) {
            onComplete()
            return
        }

        val requestCompletionCount = AtomicLong(requests.size.toLong())
        val complete: (LocalizedRequest, Alias?) -> Unit = { request, alias ->
            onResolved(request.mediaId, alias)
            if (requestCompletionCount.decrementAndGet() == 0L) onComplete()
        }
        val uncached = mutableListOf<LocalizedRequest>()
        requests.forEach { request ->
            val cached = synchronized(localizedCache) { localizedCache[request.cacheKey] }
            if (cached != null) {
                complete(request, cached)
                return@forEach
            }
            val ownsRequest = synchronized(localizedInFlight) {
                val callbacks = localizedInFlight[request.requestKey]
                if (callbacks != null) {
                    callbacks += { alias -> complete(request, alias) }
                    promotePendingRequests(listOf(request.mediaId), request.priority)
                    false
                } else {
                    localizedInFlight[request.requestKey] =
                        mutableListOf({ alias -> complete(request, alias) })
                    true
                }
            }
            if (ownsRequest) uncached += request
        }
        if (uncached.isEmpty()) return
        persistentLocalizedCache.getMany(uncached.map(LocalizedRequest::cacheKey)) { cached ->
            uncached.forEach { request ->
                val alias = cached[request.cacheKey]
                if (alias != null) finishLocalizedCacheHit(request, alias)
                else enqueueLocalizedRequest(request)
            }
        }
    }

    private fun finishLocalizedCacheHit(request: LocalizedRequest, alias: Alias) {
        synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
            "Apple 地区元数据持久缓存命中: id=${request.mediaId}, " +
                "entityType=${request.entityType}, selection=${request.selection}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

    private fun enqueueLocalizedRequest(request: LocalizedRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(localizedPending) {
            val existing = localizedPending[prioritized.requestKey]
            localizedPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                )
            }
            if (
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processLocalizedBatch, LOCALIZED_BATCH_DELAY_MS)
        }
    }

    private fun processLocalizedBatch() {
        val batch = synchronized(localizedPending) {
            localizedBatchScheduled = false
            if (
                localizedPending.isEmpty() ||
                !canStartLocalizedBatchLocked()
            ) return
            val pendingValues = localizedPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(LocalizedRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<LocalizedRequest>()
            val selectedIds = linkedSetOf<String>()
            localizedPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (selected.isNotEmpty() && selectedIds.size + newIds.size > LOCALIZED_BATCH_SIZE) {
                        return@forEach
                    }
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { localizedPending.remove(it.requestKey) }
            localizedBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                localizedBackgroundBatchesRunning += 1
            }
            selected
        }

        queryByConfiguredRegion(
            mediaIds = batch.flatMap(LocalizedRequest::lookupIds).distinct(),
            entityType = batch.first().entityType,
            storefront = batch.first().storefront,
            language = batch.first().language,
        ) { songs ->
            // Candidate ID matching and empty-alias filtering do not touch host objects.  Keep
            // them off the main thread, then publish the bounded cache/callback mutations there.
            catalogBackgroundExecutor.execute {
                val matches = runCatching {
                    batch.map { request ->
                        val resolvedEntry = request.lookupIds.firstNotNullOfOrNull { lookupId ->
                            songs?.get(lookupId)?.let { lookupId to it }
                        }
                        val alias = resolvedEntry?.second?.alias?.takeIf {
                            it.title.isNotBlank() || it.artist.isNotBlank()
                        }
                        Triple(request, resolvedEntry?.first, alias)
                    }
                }.onFailure { error ->
                    ProviderLogger.error("Apple 地区批量元数据后台候选匹配失败", error)
                }.getOrElse {
                    batch.map { request -> Triple(request, null, null) }
                }
                mainHandler.post {
                    matches.forEach { (request, resolvedLookupId, alias) ->
                        finishLocalizedRequest(request, resolvedLookupId, alias)
                    }
                    synchronized(localizedPending) {
                        localizedBatchesRunning -= 1
                        if (batch.first().priority == RequestPriority.BACKGROUND) {
                            localizedBackgroundBatchesRunning -= 1
                        }
                    }
                    scheduleLocalizedBatchIfCapacity()
                }
            }
        }
        scheduleLocalizedBatchIfCapacity()
    }

    private fun scheduleLocalizedBatchIfCapacity() {
        val shouldSchedule = synchronized(localizedPending) {
            if (
                localizedPending.isEmpty() ||
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processLocalizedBatch)
    }

    private fun canStartLocalizedBatchLocked(): Boolean {
        val nextPriority = localizedPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = localizedBatchesRunning,
            backgroundRunning = localizedBackgroundBatchesRunning,
            maxRunning = MAX_LOCALIZED_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING,
        )
    }

    fun promotePendingRequests(
        mediaIds: Collection<String>,
        priority: RequestPriority,
    ) {
        val normalizedIds = mediaIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (normalizedIds.isEmpty()) return
        if (requestScopeActive) {
            val scopedPriorities = normalizedIds.associateWith(::currentScopedPriority)
            updatePendingRequestPriorities(
                scopedPriorities = scopedPriorities,
                onlyMediaIds = normalizedIds,
            )
            return
        }
        if (priority == RequestPriority.BACKGROUND) return
        normalizedIds.forEach { mediaId -> rememberRequestPriority(mediaId, priority) }
        var localizedPromoted = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (request.mediaId in normalizedIds && request.priority.ordinal < priority.ordinal) {
                    entry.setValue(request.copy(priority = priority))
                    localizedPromoted += 1
                }
            }
        }
        var originalPromoted = 0
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (request.mediaId in normalizedIds && request.priority.ordinal < priority.ordinal) {
                    entry.setValue(request.copy(priority = priority))
                    originalPromoted += 1
                }
            }
        }
        if (BuildConfig.DEBUG && (localizedPromoted > 0 || originalPromoted > 0)) {
            ProviderLogger.info(
                "Apple 元数据请求优先级提升: priority=$priority, ids=$normalizedIds, " +
                    "localized=$localizedPromoted, original=$originalPromoted"
            )
        }
        scheduleLocalizedBatchIfCapacity()
        scheduleOriginalEntityBatchIfCapacity()
    }

    fun updateRequestScope(
        revision: Long,
        visibleMediaIds: Collection<String>,
        activePageMediaIds: Collection<String>,
    ) {
        val visible = normalizeRequestScopeIds(visibleMediaIds)
        val activePage = normalizeRequestScopeIds(activePageMediaIds) - visible
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive && requestScopeRevision == revision) return
            requestScopeActive = true
            requestScopeRevision = revision
            requestPriorityByMediaId.clear()
            activePage.forEach { mediaId ->
                requestPriorityByMediaId[mediaId] = RequestPriority.ACTIVE_PAGE
            }
            visible.forEach { mediaId ->
                requestPriorityByMediaId[mediaId] = RequestPriority.VISIBLE
            }
        }
        val scopedPriorities = (visible + activePage).associateWith { mediaId ->
            priorityForRequestScope(mediaId, visible, activePage)
        }
        val changed = updatePendingRequestPriorities(scopedPriorities)
        if (BuildConfig.DEBUG && changed > 0) {
            ProviderLogger.info(
                "Apple 元数据请求作用域同步: revision=$revision, " +
                    "visible=${visible.size}, page=${activePage.size}, changed=$changed"
            )
        }
        scheduleLocalizedBatchIfCapacity()
        scheduleOriginalEntityBatchIfCapacity()
    }

    private fun updatePendingRequestPriorities(
        scopedPriorities: Map<String, RequestPriority>,
        onlyMediaIds: Set<String>? = null,
    ): Int {
        var changed = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        return changed
    }

    private fun currentScopedPriority(mediaId: String): RequestPriority =
        synchronized(requestPriorityByMediaId) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        }

    private fun rememberRequestPriority(mediaId: String, priority: RequestPriority) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty()) return
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive) {
                requestPriorityByMediaId.putIfAbsent(
                    normalizedId,
                    RequestPriority.BACKGROUND,
                )
                return
            }
            requestPriorityByMediaId[normalizedId] = higherPriority(
                requestPriorityByMediaId[normalizedId] ?: RequestPriority.BACKGROUND,
                priority,
            )
        }
    }

    private fun currentRequestPriority(
        mediaId: String,
        fallback: RequestPriority,
    ): RequestPriority = synchronized(requestPriorityByMediaId) {
        if (requestScopeActive) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        } else {
            higherPriority(requestPriorityByMediaId[mediaId.trim()] ?: fallback, fallback)
        }
    }

    private fun finishLocalizedRequest(
        request: LocalizedRequest,
        resolvedLookupId: String?,
        alias: Alias?,
    ) {
        if (alias != null) {
            synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
            persistentLocalizedCache.put(request.cacheKey, alias)
        }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
                "Apple 播放元数据地区查询完成: id=${request.mediaId}, " +
                "lookupIds=${request.lookupIds}, resolvedBy=$resolvedLookupId, " +
                "entityType=${request.entityType}, " +
                "selection=${request.selection}, storefront=${request.storefront}, " +
                "language=${request.language}, priority=${request.priority}, " +
                "value=${alias?.title}/${alias?.artist}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

    private fun finishResolve(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        originKnown: Boolean,
        artistIds: List<String>,
        trustedArtistOnlyLanguages: Set<String>,
    ) {
        // Canonical language filtering and alias confidence checks are pure operations over
        // immutable DTOs.  Keep cache mutation and completion publication on the host main thread,
        // but do not make the UI wait for this potentially multi-candidate selection pass.
        catalogBackgroundExecutor.execute {
            val prepared = runCatching {
                prepareOriginalResolution(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = originKnown,
                    artistIds = artistIds,
                    trustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                )
            }.onFailure { error ->
                ProviderLogger.error(
                    "Apple 内部原名候选后台匹配失败: id=${metadata.id}",
                    error,
                )
            }.getOrElse {
                PreparedOriginalResolution(
                    canonicalLanguages = languages.map(String::trim).filter(String::isNotEmpty),
                    canonicalTrustedArtistOnlyLanguages = trustedArtistOnlyLanguages,
                    sourceLanguage = languages.singleOrNull(),
                    originalAlias = null,
                    resolvedAlbum = null,
                    originKnown = originKnown,
                    artistIds = artistIds,
                )
            }
            mainHandler.post {
                publishOriginalResolution(metadata, prepared)
            }
        }
    }

    private fun prepareOriginalResolution(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        originKnown: Boolean,
        artistIds: List<String>,
        trustedArtistOnlyLanguages: Set<String>,
    ): PreparedOriginalResolution {
        val canonicalLanguages = languages.map(::canonicalOriginalLanguage).distinct()
        val canonicalTrustedArtistOnlyLanguages = trustedArtistOnlyLanguages
            .map(::canonicalOriginalLanguage)
            .toSet()
        val sourceLanguage = canonicalLanguages.singleOrNull()
        val acceptableResults = regionalOriginalAliases(results, canonicalLanguages)
        fun isTrustedArtistOnlyAlias(alias: Alias): Boolean =
            canonicalOriginalLanguage(alias.language) in canonicalTrustedArtistOnlyLanguages &&
                isConfidentOriginalArtistOnlyAlias(
                    alias = alias,
                    localizedTitle = metadata.title.orEmpty(),
                    localizedArtist = metadata.artist.orEmpty(),
                )
        val selected = selectOriginalAlias(
            variants = acceptableResults,
            localizedTitle = metadata.title.orEmpty(),
            localizedArtist = metadata.artist.orEmpty()
        ) ?: acceptableResults.lastOrNull(::isTrustedArtistOnlyAlias)
        val confirmedRegionalAlias = if (originKnown) {
            acceptableResults.lastOrNull { alias ->
                canonicalOriginalLanguage(alias.language) in canonicalLanguages &&
                    isConfidentOriginalSongAlias(
                        alias = alias,
                        localizedTitle = metadata.title.orEmpty(),
                        localizedArtist = metadata.artist.orEmpty(),
                        allowArtistOnly = isTrustedArtistOnlyAlias(alias),
                    )
            }
        } else {
            null
        }
        val originalAlias = selected ?: confirmedRegionalAlias
        val resolvedAlbum = originalAlbumFromResolution(
            alias = originalAlias,
            acceptableResults = acceptableResults,
        )
        return PreparedOriginalResolution(
            canonicalLanguages = canonicalLanguages,
            canonicalTrustedArtistOnlyLanguages = canonicalTrustedArtistOnlyLanguages,
            sourceLanguage = sourceLanguage,
            originalAlias = originalAlias,
            resolvedAlbum = resolvedAlbum,
            originKnown = originKnown,
            artistIds = artistIds,
        )
    }

    private fun publishOriginalResolution(
        metadata: MediaMetadataCache.Metadata,
        prepared: PreparedOriginalResolution,
    ) {
        val originalAlias = prepared.originalAlias
        if (originalAlias != null) {
            synchronized(cache) { cache[metadata.id] = originalAlias }
            persistentOriginalCache.put(originalSongCacheKey(metadata.id), originalAlias)
        }
        discardOriginalCandidates(metadata.id)
        val callbacks = synchronized(inFlight) { inFlight.remove(metadata.id).orEmpty() }
        ProviderLogger.info(
            "Apple 内部原名查询完成: id=${metadata.id}, genre=${metadata.genre}, " +
                "languages=${prepared.canonicalLanguages}, " +
                "selected=${originalAlias?.title}/${originalAlias?.artist}"
        )
        val resolution = OriginalResolution(
            alias = originalAlias,
            language = originalAlias?.language?.takeIf(String::isNotBlank)
                ?: prepared.sourceLanguage,
            originKnown = prepared.originKnown,
            artistIds = prepared.artistIds,
            album = prepared.resolvedAlbum,
            trustedArtistOnlyLanguages = prepared.canonicalTrustedArtistOnlyLanguages,
        )
        callbacks.forEach { callback -> callback(resolution) }
    }

    private fun finishCachedOriginalResolve(mediaId: String, alias: Alias) {
        discardOriginalCandidates(mediaId)
        val callbacks = synchronized(inFlight) { inFlight.remove(mediaId).orEmpty() }
        ProviderLogger.info(
            "Apple 原地区元数据缓存命中: id=$mediaId, language=${alias.language}"
        )
        val resolution = OriginalResolution(
            alias = alias,
            language = alias.language.takeIf(String::isNotBlank),
            originKnown = true,
            artistIds = emptyList(),
            album = alias.album,
            trustedArtistOnlyLanguages = setOf(canonicalOriginalLanguage(alias.language)),
        )
        callbacks.forEach { callback -> callback(resolution) }
    }

    private fun registerOriginalCandidateCallback(
        mediaId: String,
        callback: (Alias) -> Unit,
    ) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.getOrPut(mediaId) { mutableListOf() }.add(callback)
        }
        catalogIdentityCache[mediaId]
            ?.fallbackAliases
            ?.firstOrNull()
            ?.let { alias -> publishOriginalCandidate(mediaId, alias) }
    }

    private fun publishOriginalCandidate(mediaId: String, alias: Alias) {
        if (alias.title.isBlank() && alias.artist.isBlank()) return
        val callbacks = synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId).orEmpty()
        }
        callbacks.forEach { callback -> callback(alias) }
    }

    private fun discardOriginalCandidates(mediaId: String) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId)
        }
    }

    private fun resolveCatalogIdentity(
        mediaId: String,
        languages: List<String>,
        onResult: (CatalogIdentity) -> Unit
    ) {
        catalogIdentityCache[mediaId]?.takeIf(::isUsefulCatalogIdentity)?.let {
            onResult(it)
            return
        }
        catalogIdentityCache.remove(mediaId)
        val ownsRequest = synchronized(catalogIdentityInFlight) {
            val callbacks = catalogIdentityInFlight[mediaId]
            if (callbacks != null) {
                callbacks += onResult
                false
            } else {
                catalogIdentityInFlight[mediaId] = mutableListOf(onResult)
                true
            }
        }
        if (!ownsRequest) return

        queryById(mediaId, null) { currentSong ->
            currentSong?.isrc?.let { isrc ->
                ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
                finishCatalogIdentity(
                    mediaId,
                    CatalogIdentity(
                        isrc = isrc,
                        fallbackAliases = listOfNotNull(currentSong.alias),
                        genres = currentSong.genres,
                        artistIds = currentSong.artistIds,
                    ),
                )
                return@queryById
            }

            val fallbackAliases = mutableListOf<Alias>().apply {
                currentSong?.alias?.let(::add)
            }
            val fallbackGenres = mutableListOf<String>().apply {
                currentSong?.genres?.let(::addAll)
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishCatalogIdentity(
                        mediaId,
                        CatalogIdentity(
                            isrc = null,
                            fallbackAliases = fallbackAliases,
                            genres = fallbackGenres,
                            artistIds = currentSong?.artistIds.orEmpty(),
                        ),
                    )
                    return
                }
                val language = languages[index]
                queryById(mediaId, language) { song ->
                    song?.alias?.let(fallbackAliases::add)
                    song?.genres?.let(fallbackGenres::addAll)
                    val isrc = song?.isrc
                    if (isrc != null) {
                        ProviderLogger.info(
                            "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                        )
                        finishCatalogIdentity(
                            mediaId,
                            CatalogIdentity(
                                isrc = isrc,
                                fallbackAliases = fallbackAliases,
                                genres = fallbackGenres.distinct(),
                                artistIds = (
                                    currentSong?.artistIds.orEmpty() + song.artistIds
                                ).distinct(),
                            ),
                        )
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            queryNext(0)
        }
    }

    private fun rememberCatalogIdentity(mediaId: String, song: CatalogSong) {
        val isrc = song.isrc ?: return
        val identity = CatalogIdentity(
            isrc = isrc,
            fallbackAliases = listOfNotNull(song.alias),
            genres = song.genres,
            artistIds = song.artistIds,
        )
        finishCatalogIdentity(mediaId, identity)
    }

    private fun finishCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
        val merged = synchronized(catalogIdentityCache) {
            val previous = catalogIdentityCache[mediaId]
            val next = if (previous == null) identity else {
                CatalogIdentity(
                    isrc = previous.isrc ?: identity.isrc,
                    fallbackAliases = (previous.fallbackAliases + identity.fallbackAliases).distinct(),
                    genres = (previous.genres + identity.genres).distinct(),
                    artistIds = (previous.artistIds + identity.artistIds).distinct(),
                )
            }
            if (isUsefulCatalogIdentity(next)) {
                catalogIdentityCache[mediaId] = next
            } else {
                catalogIdentityCache.remove(mediaId)
            }
            next
        }
        val cacheable = isUsefulCatalogIdentity(merged)
        MediaMetadataCache.updateCatalogGenres(mediaId, merged.genres)
        // Remove and drain the in-flight callbacks on main together with their publication. This
        // prevents a new request from observing a removed key while the background preprocessing
        // result is still waiting in the main queue.
        fun publish() {
            val callbacks = synchronized(catalogIdentityInFlight) {
                catalogIdentityInFlight.remove(mediaId).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                ProviderLogger.info(
                    "Apple 内部歌曲身份已就绪: id=$mediaId, isrc=${merged.isrc}, " +
                        "genres=${merged.genres}, " +
                        "artistIds=${merged.artistIds}, candidates=${merged.fallbackAliases.size}, " +
                        "cached=$cacheable"
                )
                callbacks.forEach { callback -> callback(merged) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish() else mainHandler.post(::publish)
    }

    private fun isUsefulCatalogIdentity(identity: CatalogIdentity): Boolean =
        shouldCacheCatalogIdentity(identity.isrc, identity.genres)

    private fun queryById(
        mediaId: String,
        language: String?,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaId,
            "platform" to "android",
            "include[songs]" to "artists"
        )
        language?.let { queryParams["l"] = it }
        query(
            storefront = language?.let(::storefrontForLanguage),
            language = language,
            description = "id=$mediaId",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }

    private fun queryByConfiguredRegion(
        mediaIds: List<String>,
        entityType: LocalizedEntityType,
        storefront: String,
        language: String,
        onResult: (Map<String, CatalogSong>) -> Unit,
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaIds.joinToString(","),
            "l" to language,
            "platform" to "android",
        )
        if (entityType != LocalizedEntityType.ARTIST) {
            queryParams["include[${entityType.path}]"] = "artists"
        }
        queryResponse(
            storefront = storefront,
            language = language,
            description = "localized-${entityType.path}-ids=${mediaIds.size}",
            path = entityType.path,
            queryParams = queryParams,
            snapshotEntityType = entityType,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot
                        ?.let { parseCatalogEntities(it, language, entityType) }
                        .orEmpty()
                        .mapNotNull { song -> song.id?.let { it to song } }
                        .toMap()
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据响应解析失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }.getOrDefault(emptyMap())
            },
        ) { songs ->
            val byId = songs.orEmpty()
            // Identity cache writes are bounded but may fan out over a 50-item response.  Keep
            // that preprocessing off the UI thread and publish only the immutable map/callback.
            catalogBackgroundExecutor.execute {
                runCatching {
                    byId.forEach(::rememberCatalogIdentity)
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据缓存预处理失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }
                mainHandler.post {
                    ProviderLogger.info(
                        "Apple 地区批量元数据候选: entityType=$entityType, " +
                            "requested=${mediaIds.size}, resolved=${byId.size}, " +
                            "storefront=$storefront, language=$language"
                    )
                    onResult(byId)
                }
            }
        }
    }

    private fun queryByIsrc(
        isrc: String,
        language: String,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "filter[isrc]" to isrc,
            "l" to language,
            "platform" to "android",
            "include[songs]" to "artists",
            "limit" to "1"
        )
        query(
            storefront = storefrontForLanguage(language),
            language = language,
            description = "isrc=$isrc",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }

    private fun query(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        onResult: (CatalogSong?) -> Unit
    ) {
        queryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = path,
            queryParams = queryParams,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot?.let {
                        parseCatalogSong(it, language ?: CURRENT_LANGUAGE)
                    }
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 内部原名响应解析失败: $description, language=$language",
                        error,
                    )
                }.getOrNull()
            },
        ) { song ->
            ProviderLogger.info(
                "Apple 内部原名候选: $description, storefront=$storefront, " +
                    "language=$language, value=${song?.alias?.title}/${song?.alias?.artist}, " +
                    "isrc=${song?.isrc}"
            )
            onResult(song)
        }
    }

    /**
     * The host query itself intentionally remains in the main adapter: Apple Music's MediaApi,
     * storefront mutation, and Continuation contract are not proven thread-safe.  After a result
     * arrives, [AppleCatalogResponseWorkDispatcher] copies host values on main and runs
     * [transformOffMain] on the Catalog CPU executor before publishing the existing main-thread
     * callback.
     */
    private fun <Result> queryResponse(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        snapshotEntityType: LocalizedEntityType = LocalizedEntityType.SONG,
        transformOffMain: (CatalogResponseSnapshot?) -> Result,
        onResult: (Result?) -> Unit,
    ) {
        val diagnosticRequestId = catalogDiagnosticSequence.incrementAndGet().toString(36)
        val queuedAtMs = SystemClock.uptimeMillis()
        logCatalogRequestDiagnostic(
            requestId = diagnosticRequestId,
            event = "queued",
            description = description,
            storefront = storefront,
            language = language,
            elapsedMs = 0L,
        )
        mainHandler.post {
            var requestToken: String? = null
            val completed = AtomicBoolean(false)
            var slowResponse: Runnable? = null
            var timeout: Runnable? = null

            val responseDiagnostic = AtomicReference<String?>(null)
            val responseTask = catalogResponseDispatcher.newTask<Any, CatalogResponseSnapshot, Result>(
                snapshotOnMain = { response ->
                    val snapshot = response?.let {
                        snapshotCatalogResponse(it, snapshotEntityType)
                    }
                    responseDiagnostic.set(catalogResponseDiagnostic(response, snapshot))
                    snapshot
                },
                transformOffMain = transformOffMain,
                publishOnMain = { result ->
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = responseDiagnostic.get(),
                    )
                    onResult(result)
                },
                failOnMain = { error ->
                    ProviderLogger.error(
                        "Apple 内部目录响应后台处理失败: $description, language=$language",
                        error,
                    )
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "transform_failed",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    onResult(null)
                },
            )

            fun finish(response: Any?, event: String = "response") {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "late_response_callback",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                if (!responseTask.submit(response)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "response_task_rejected",
                    )
                }
            }

            fun fail(event: String, error: Throwable) {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                ProviderLogger.error(
                    "Apple 内部目录直连查询失败: $description, language=$language",
                    error,
                )
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = event,
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "error=${error.javaClass.name}:${error.message}",
                )
                // Continuations may resume from an Apple network thread.  Preserve the resolver
                // callback contract by publishing failures on the host main executor as well.
                mainHandler.post { onResult(null) }
            }

            runCatching {
                val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
                val localization = if (storefront != null && language != null) {
                    CatalogRequestLocalization(storefront, language)
                } else {
                    null
                }
                if (localization != null) {
                    captureAccountStorefront(access)
                    requestToken = catalogRequestSequence.incrementAndGet().toString(36)
                    pendingCatalogRequests[requestToken] = localization
                }
                val directQueryParams = LinkedHashMap(queryParams)
                requestToken?.let { token ->
                    directQueryParams[CATALOG_REQUEST_TOKEN_PARAM] = token
                }
                slowResponse = Runnable {
                    if (completed.get()) return@Runnable
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "slow_response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, slowMs=$QUERY_SLOW_RESPONSE_MS",
                    )
                }.also { mainHandler.postDelayed(it, QUERY_SLOW_RESPONSE_MS) }
                timeout = Runnable {
                    if (!completed.compareAndSet(false, true)) return@Runnable
                    responseTask.cancel()
                    requestToken?.let(pendingCatalogRequests::remove)
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "timeout",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, timeoutMs=$QUERY_TIMEOUT_MS",
                    )
                    onResult(null)
                }.also { mainHandler.postDelayed(it, QUERY_TIMEOUT_MS) }

                val continuation = createDirectCatalogContinuation(
                    access = access,
                    onSuccess = { response -> finish(response) },
                    onFailure = { error -> fail("request_failed", error) },
                )
                val previousStorefront = access.storefrontField.get(access.mediaApi) as? String
                val directResult = try {
                    activeCatalogRequest.set(localization)
                    localization?.let {
                        access.storefrontField.set(access.mediaApi, it.storefront)
                    }
                    access.directQueryMethod.invoke(
                        access.mediaApi,
                        path,
                        directQueryParams,
                        continuation,
                    )
                } finally {
                    activeCatalogRequest.remove()
                    if (localization != null) {
                        access.storefrontField.set(access.mediaApi, previousStorefront)
                    }
                }
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "observing",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, path=$path, " +
                        "suspended=${isCoroutineSuspended(directResult)}",
                )
                if (!isCoroutineSuspended(directResult)) {
                    finish(directResult)
                }
            }.onFailure { error ->
                activeCatalogRequest.remove()
                fail("start_failed", error)
            }
        }
    }

    private fun createDirectCatalogContinuation(
        access: CatalogAccess,
        onSuccess: (Any?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Any = Proxy.newProxyInstance(
        access.continuationType.classLoader ?: classLoader,
        arrayOf(access.continuationType),
    ) { proxy, method, args ->
        when (method.name) {
            "getContext" -> access.emptyCoroutineContext
            "resumeWith" -> {
                val result = args?.firstOrNull()
                val failure = coroutineResultFailure(result)
                if (failure != null) onFailure(failure) else onSuccess(result)
                null
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "AppleCatalogContinuation"
            else -> null
        }
    }

    private fun coroutineResultFailure(result: Any?): Throwable? {
        if (result is Throwable) return result
        val value = result ?: return null
        // Kotlin's Result.Failure is the only wrapper whose field we need to inspect.  Do not
        // reflect arbitrary Apple response objects from a continuation/network thread.
        if (value.javaClass.name != "kotlin.Result\$Failure") return null
        val fields = value.javaClass.declaredFields.filterNot { field ->
            Modifier.isStatic(field.modifiers)
        }
        if (fields.size != 1) return null
        val field = fields.single()
        if (!Throwable::class.java.isAssignableFrom(field.type)) return null
        field.isAccessible = true
        return field.get(value) as? Throwable
    }

    private fun createCatalogAccess(): CatalogAccess {
        val resolvedHolder = resolvedCatalogHolder
        val holderClass = resolvedHolder.clazz
        val companionField = holderClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type.name == "${holderClass.name}\$Companion"
        } ?: error("MediaApiRepositoryHolder companion unavailable")
        companionField.isAccessible = true
        val companion = requireNotNull(companionField.get(null))
        val mediaApi = AppleReflection.call(
            companion,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD
            ),
        )
            ?: error("Apple MediaApi without HTTP cache unavailable")
        val storefrontField = findField(
            mediaApi,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD
            ),
        ).also { field ->
            if (field.type != String::class.java) {
                error("Apple MediaApi storefront field has unexpected type")
            }
        }
        val directQueryMethod = findDirectCatalogQueryMethod(
            clazz = mediaApi.javaClass,
            methodName = resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD
            ),
        )
        val continuationType = directQueryMethod.parameterTypes[2]
        val coroutineContextType = continuationType.methods.firstOrNull { method ->
            method.name == "getContext" && method.parameterCount == 0
        }?.returnType ?: error("Apple Continuation context type unavailable")
        val emptyCoroutineContext = createEmptyCoroutineContext(coroutineContextType)
        return CatalogAccess(
            mediaApi = mediaApi,
            storefrontField = storefrontField,
            directQueryMethod = directQueryMethod,
            continuationType = continuationType,
            emptyCoroutineContext = emptyCoroutineContext,
        ).also(::captureAccountStorefront)
    }

    private fun findDirectCatalogQueryMethod(clazz: Class<*>, methodName: String): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName &&
                    method.parameterTypes.let { types ->
                        types.size == 3 &&
                            types[0] == String::class.java &&
                            Map::class.java.isAssignableFrom(types[1]) &&
                            types[2].name == "kotlin.coroutines.Continuation"
                    }
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$methodName(String,Map,Continuation)")
    }

    private fun createEmptyCoroutineContext(contextType: Class<*>): Any =
        Proxy.newProxyInstance(
            contextType.classLoader ?: classLoader,
            arrayOf(contextType),
        ) { proxy, method, args ->
            when (method.name) {
                "fold" -> args?.firstOrNull()
                "get" -> null
                "minusKey" -> proxy
                "plus" -> args?.firstOrNull()
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> 0
                "toString" -> "EmptyCoroutineContext"
                else -> null
            }
        }

    private fun findField(instance: Any, name: String): Field {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { field -> field.name == name }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        error("${instance.javaClass.name}#$name unavailable")
    }

    private fun logCatalogRequestDiagnostic(
        requestId: String,
        event: String,
        description: String,
        storefront: String?,
        language: String?,
        elapsedMs: Long,
        requestToken: String? = null,
        detail: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val localizedState = synchronized(localizedPending) {
            "${localizedPending.size}/$localizedBatchesRunning"
        }
        val originalState = synchronized(originalEntityPending) {
            "${originalEntityPending.size}/$originalEntityBatchesRunning"
        }
        ProviderLogger.diagnostic(
            "AppleCatalogRequest: id=$requestId, token=${requestToken ?: "none"}, " +
                "event=$event, description=$description, storefront=$storefront, " +
                "language=$language, elapsedMs=$elapsedMs, " +
                "localizedPendingRunning=$localizedState, " +
                "originalPendingRunning=$originalState" +
                detail?.let { ", $it" }.orEmpty()
        )
    }

    private fun catalogResponseDiagnostic(
        response: Any?,
        snapshot: CatalogResponseSnapshot?,
    ): String {
        if (response == null) return "value=null"
        // This diagnostic intentionally avoids touching the host response.  The snapshot has
        // already copied the only useful cardinality while all reflection was on the main thread.
        return "valueClass=${response.javaClass.name}, " +
            "dataSize=${snapshot?.entities?.size ?: "unknown"}"
    }

    private fun storefrontForLanguage(language: String): String =
        storefrontForOriginalLanguage(language)
            ?: error("Unsupported Apple storefront language: $language")

    /**
     * Copies the host response while still on the host's required main thread.  Nothing returned
     * from this method retains a reference to an Apple Music response/entity/attributes object;
     * parsing, language selection, and candidate matching consume only these immutable values on
     * the Catalog CPU executor.
     */
    private fun snapshotCatalogResponse(
        response: Any,
        entityType: LocalizedEntityType,
    ): CatalogResponseSnapshot {
        val data = AppleReflection.call(
            response,
            catalogMember(AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD),
        )
        return CatalogResponseSnapshot(
            entities = collectionValues(data).mapNotNull { entity ->
                snapshotCatalogEntity(entity, entityType)
            }.toList(),
        )
    }

    private fun snapshotCatalogEntity(
        entity: Any,
        entityType: LocalizedEntityType,
    ): CatalogEntitySnapshot? {
        val id = (AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
        ) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val attributes = AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
        ) ?: return null
        val rawAttributes = AppleMediaApiAttributeSnapshots.get(attributes)
        val title = if (rawAttributes != null) {
            rawAttributes.name?.trim().orEmpty()
        } else {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }
        val attributeArtist = if (rawAttributes != null) {
            rawAttributes.artistName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val albumName = if (entityType == LocalizedEntityType.ARTIST) {
            ""
        } else if (rawAttributes != null) {
            rawAttributes.albumName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val relationshipArtists = if (entityType == LocalizedEntityType.ARTIST) {
            emptyList()
        } else runCatching {
            @Suppress("UNCHECKED_CAST")
            val relationships = AppleReflection.call(
                entity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD),
            ) as? Map<String, Any?>
            val artistRelationship = relationships?.get("artists")
                ?: relationships?.get("artist")
            val artistEntities = collectionValues(
                artistRelationship?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(
                            AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD,
                        ),
                    ) ?: AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD),
                    )
                },
            )
            artistEntities.mapNotNull { artistEntity ->
                val artistAttributes = AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
                )
                val rawArtistAttributes = artistAttributes?.let(
                    AppleMediaApiAttributeSnapshots::get,
                )
                val artistName = if (rawArtistAttributes != null) {
                    rawArtistAttributes.name
                } else artistAttributes?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
                    ) as? String
                }
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val artistId = (AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                if (artistName == null && artistId == null) null
                else CatalogArtistSnapshot(id = artistId, name = artistName)
            }
        }.getOrDefault(emptyList())
        val isrc = if (entityType == LocalizedEntityType.SONG) {
            runCatching {
                (AppleReflection.call(
                    attributes,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }.getOrNull()
        } else {
            null
        }
        val genres = if (entityType != LocalizedEntityType.ARTIST) {
            runCatching {
                collectionValues(
                    AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD),
                    ),
                ).mapNotNull { value ->
                    value.toString().trim().takeIf(String::isNotEmpty)
                }
            }.getOrDefault(emptyList()).ifEmpty {
                runCatching {
                    (AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD),
                    ) as? String)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::listOf)
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        if (title.isEmpty() && attributeArtist.isEmpty() && isrc == null) return null
        return CatalogEntitySnapshot(
            id = id,
            title = title,
            attributeArtist = attributeArtist,
            albumName = albumName,
            isrc = isrc,
            genres = genres.toList(),
            relationshipArtists = relationshipArtists.toList(),
        )
    }

    private fun parseCatalogSong(response: CatalogResponseSnapshot, language: String): CatalogSong? =
        parseCatalogSongs(response, language).firstOrNull()

    private fun parseCatalogSongs(
        response: CatalogResponseSnapshot,
        language: String,
    ): List<CatalogSong> = parseCatalogEntities(response, language, LocalizedEntityType.SONG)

    private fun parseCatalogEntities(
        response: CatalogResponseSnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): List<CatalogSong> = response.entities.mapNotNull { entity ->
        parseCatalogEntity(entity, language, entityType)
    }

    /** Pure conversion from the immutable host snapshot; safe to run off the main thread. */
    private fun parseCatalogEntity(
        entity: CatalogEntitySnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): CatalogSong? {
        val album = when (entityType) {
            LocalizedEntityType.SONG -> entity.albumName
            LocalizedEntityType.ALBUM -> entity.title
            LocalizedEntityType.ARTIST -> ""
        }
        val relationshipArtists = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::name)
        val relationshipArtistIds = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::id)
            .distinct()
        val artist = when (entityType) {
            LocalizedEntityType.ARTIST -> entity.title
            else -> selectLocalizedArtistName(
                attributeArtist = entity.attributeArtist,
                relationshipArtists = relationshipArtists,
                language = language,
            )
        }
        if (relationshipArtists.isNotEmpty() && artist != entity.attributeArtist) {
            ProviderLogger.info(
                "Apple 歌手关系名称已优先: attributes=${entity.attributeArtist}, relationship=$artist",
            )
        }
        val isrc = entity.isrc.takeIf { entityType == LocalizedEntityType.SONG }
        if (entity.title.isEmpty() && artist.isEmpty() && isrc == null) return null
        return CatalogSong(
            id = entity.id,
            alias = Alias(entity.title, artist, language, album),
            isrc = isrc,
            genres = if (entityType == LocalizedEntityType.ARTIST) emptyList() else entity.genres,
            artistIds = if (entityType == LocalizedEntityType.ARTIST) {
                emptyList()
            } else {
                relationshipArtistIds
            },
        )
    }

    private fun collectionValues(value: Any?): List<Any> = when (value) {
        is Array<*> -> value.filterNotNull()
        is Iterable<*> -> value.filterNotNull()
        is Map<*, *> -> value.values.filterNotNull()
        else -> emptyList()
    }

    private fun catalogMember(member: AppleMusicRuntimeMember): String =
        resolvedCatalogHolder.target.runtimeMemberName(member)

    private data class CatalogAccess(
        val mediaApi: Any,
        val storefrontField: Field,
        val directQueryMethod: Method,
        val continuationType: Class<*>,
        val emptyCoroutineContext: Any,
    )

    /** Immutable values copied from Apple Music's reflective response on the main thread. */
    private data class CatalogResponseSnapshot(
        val entities: List<CatalogEntitySnapshot>,
    )

    private data class CatalogEntitySnapshot(
        val id: String?,
        val title: String,
        val attributeArtist: String,
        val albumName: String,
        val isrc: String?,
        val genres: List<String>,
        val relationshipArtists: List<CatalogArtistSnapshot>,
    )

    private data class CatalogArtistSnapshot(
        val id: String?,
        val name: String?,
    )

    private data class CatalogIdentity(
        val isrc: String?,
        val fallbackAliases: List<Alias>,
        val genres: List<String>,
        val artistIds: List<String>,
    )

    private data class PreparedOriginalResolution(
        val canonicalLanguages: List<String>,
        val canonicalTrustedArtistOnlyLanguages: Set<String>,
        val sourceLanguage: String?,
        val originalAlias: Alias?,
        val resolvedAlbum: String?,
        val originKnown: Boolean,
        val artistIds: List<String>,
    )

    private data class CatalogSong(
        val id: String?,
        val alias: Alias,
        val isrc: String?,
        val genres: List<String>,
        val artistIds: List<String>,
    )

    private data class LocalizedRequest(
        val cacheKey: String,
        val requestKey: String,
        val mediaId: String,
        val lookupIds: List<String>,
        val entityType: LocalizedEntityType,
        val selection: Int,
        val storefront: String,
        val language: String,
        val priority: RequestPriority,
    )

    private data class OriginalEntityRequest(
        val requestKey: String,
        val mediaId: String,
        val lookupIds: List<String>,
        val entityType: LocalizedEntityType,
        val language: String,
        val storefront: String,
        val directCacheKey: String,
        val priority: RequestPriority,
        val callbacks: List<(Alias?) -> Unit>,
    )

    data class CatalogRequestLocalization(
        val storefront: String,
        val language: String,
    )

    data class Alias(
        val title: String,
        val artist: String,
        val language: String,
        val album: String = "",
    )

    data class OriginalResolution(
        val alias: Alias?,
        val language: String?,
        val originKnown: Boolean,
        val artistIds: List<String>,
        val album: String? = null,
        /**
         * Original-region evidence that is strong enough to permit an alias
         * whose title stays English while only its artist becomes CJK.
         */
        val trustedArtistOnlyLanguages: Set<String> = emptySet(),
    )

    data class LocalizedLookup(
        val mediaId: String,
        val lookupIds: Collection<String>,
        val entityType: LocalizedEntityType,
    )

    enum class RequestPriority {
        BACKGROUND,
        ACTIVE_PAGE,
        VISIBLE,
    }

    enum class LocalizedEntityType(val path: String) {
        SONG("songs"),
        ALBUM("albums"),
        ARTIST("artists"),
    }

    companion object {
        private const val CURRENT_LANGUAGE = "current"
        private const val CACHE_SIZE = 64
        private const val LOCALIZED_CACHE_SIZE = 4_096
        private const val LOCALIZED_ARTIST_ALIAS_CACHE_SIZE = 2_048
        private const val REQUEST_PRIORITY_CACHE_SIZE = 2_048
        private const val ORIGINAL_METADATA_CACHE_SCHEMA = "V2"

        internal fun isCoroutineSuspended(value: Any?): Boolean =
            value is Enum<*> && value.name == "COROUTINE_SUSPENDED"

        internal fun originalSongCacheKey(mediaId: String): String =
            "$ORIGINAL_METADATA_CACHE_SCHEMA:VERIFIED_SONG:${mediaId.trim()}"

        internal fun originalDirectEntityCacheKey(
            entityType: LocalizedEntityType,
            mediaId: String,
        ): String = when (entityType) {
            LocalizedEntityType.SONG ->
                "$ORIGINAL_METADATA_CACHE_SCHEMA:ENTITY_SONG:${mediaId.trim()}"
            else ->
                "$ORIGINAL_METADATA_CACHE_SCHEMA:$entityType:${mediaId.trim()}"
        }

        private fun legacyAmbiguousSongCacheKey(mediaId: String): String =
            "SONG:${mediaId.trim()}"

        internal fun originalEntityCacheKey(
            entityType: LocalizedEntityType,
            language: String,
            mediaId: String,
        ): String =
            "$ORIGINAL_METADATA_CACHE_SCHEMA:$entityType:${language.trim()}:${mediaId.trim()}"

        /**
         * Direct keys are always tried first. The remaining keys cover aliases written before
         * direct entity keys were introduced and equivalent catalog IDs collected from the same
         * Media API object.
         */
        internal fun originalEntityCacheLookupKeys(
            entityType: LocalizedEntityType,
            mediaId: String,
            lookupIds: Collection<String> = emptyList(),
            languages: Collection<String> = ORIGINAL_LANGUAGE_PROBE_ORDER,
        ): List<String> {
            val ids = sequenceOf(mediaId)
                .plus(lookupIds.asSequence())
                .map(String::trim)
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .distinct()
                .toList()
            if (ids.isEmpty()) return emptyList()
            val directKeys = ids.map { id ->
                originalDirectEntityCacheKey(entityType, id)
            }
            val legacyKeys = ids.flatMap { id ->
                languages.flatMap { language ->
                    originalLanguageCacheKeyVariants(language).map { variant ->
                        originalEntityCacheKey(entityType, variant, id)
                    }
                }
            }
            return (directKeys + legacyKeys).distinct()
        }

        internal fun localizedMetadataCacheKey(
            selection: Int,
            entityType: LocalizedEntityType,
            mediaId: String,
            language: String? = null,
        ): String {
            val normalizedLanguage = language
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.replace('_', '-')
                ?.lowercase()
            return if (normalizedLanguage == null) {
                "$selection:$entityType:${mediaId.trim()}"
            } else {
                "$selection:$entityType:$normalizedLanguage:${mediaId.trim()}"
            }
        }

        internal fun isLocalizedArtistAliasCacheKey(key: String): Boolean =
            ":ARTIST_ALIAS:" in key

        private const val LOCALIZED_BATCH_SIZE = 50
        private const val MAX_LOCALIZED_BATCHES_RUNNING = 4
        private const val MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING = 2
        private const val LOCALIZED_BATCH_DELAY_MS = 32L
        private const val ORIGINAL_ENTITY_BATCH_SIZE = 50
        private const val MAX_ORIGINAL_ENTITY_BATCHES_RUNNING = 3
        private const val MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING = 2
        private const val ORIGINAL_ENTITY_BATCH_DELAY_MS = 32L
        private const val QUERY_SLOW_RESPONSE_MS = 6_000L
        private const val QUERY_TIMEOUT_MS = 30_000L
        private const val ARTIST_ALIAS_CACHE_SCHEMA = "V2"
        internal const val CATALOG_REQUEST_TOKEN_PARAM = "hle_catalog_request"
        private val ORIGINAL_LANGUAGE_PROBE_ORDER = listOf(
            "ja-JP",
            "ko-KR",
            "zh-Hans-CN",
            "th-TH",
            "ru-RU",
            "uk-UA",
            "ar-SA",
            "he-IL",
            "hi-IN",
            "el-GR",
            "bg-BG",
        )

        private fun originalLanguageCacheKeyVariants(language: String): List<String> = when (
            canonicalOriginalLanguage(language)
        ) {
            "zh-Hans-CN" -> listOf("zh-Hans-CN", "zh-CN", "zh-cn", "zh-hans-cn")
            else -> listOf(language, language.lowercase())
        }.distinct()

        internal fun selectNextRequestIndex(
            priorities: List<RequestPriority>,
        ): Int? {
            var selectedIndex: Int? = null
            var selectedPriority = RequestPriority.BACKGROUND
            priorities.forEachIndexed { index, priority ->
                if (selectedIndex == null || priority.ordinal > selectedPriority.ordinal) {
                    selectedIndex = index
                    selectedPriority = priority
                }
            }
            return selectedIndex
        }

        internal fun canStartRequest(
            priority: RequestPriority,
            totalRunning: Int,
            backgroundRunning: Int,
            maxRunning: Int,
            maxBackgroundRunning: Int,
        ): Boolean = totalRunning < maxRunning &&
            (
                priority != RequestPriority.BACKGROUND ||
                    backgroundRunning < maxBackgroundRunning
                )

        internal fun higherPriority(
            first: RequestPriority,
            second: RequestPriority,
        ): RequestPriority = if (first.ordinal >= second.ordinal) first else second

        internal fun priorityForRequestScope(
            mediaId: String,
            visibleMediaIds: Set<String>,
            activePageMediaIds: Set<String>,
        ): RequestPriority = when (mediaId.trim()) {
            in visibleMediaIds -> RequestPriority.VISIBLE
            in activePageMediaIds -> RequestPriority.ACTIVE_PAGE
            else -> RequestPriority.BACKGROUND
        }

        private fun normalizeRequestScopeIds(mediaIds: Collection<String>): Set<String> =
            mediaIds.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .toSet()

        internal fun languageTagsForGenre(genre: String?): List<String> {
            return knownLanguageTagsForGenre(genre).ifEmpty {
                listOf("ja-JP", "ko-KR", "zh-Hans-CN")
            }
        }

        private fun knownLanguageTagsForGenre(genre: String?): List<String> {
            val normalized = genre.orEmpty().trim().lowercase()
            return when {
                "j-pop" in normalized || "japanese" in normalized ||
                    "日本流行" in normalized || "日语流行" in normalized ||
                    "日語流行" in normalized -> listOf("ja-JP")
                "k-pop" in normalized || "korean" in normalized ||
                    "韩国流行" in normalized || "韓國流行" in normalized ||
                    "韩语流行" in normalized || "韓語流行" in normalized -> listOf("ko-KR")
                "mandopop" in normalized || "chinese" in normalized ||
                    "国语流行" in normalized || "國語流行" in normalized ||
                    "华语流行" in normalized || "華語流行" in normalized ||
                    "中文流行" in normalized ->
                    listOf("zh-Hans-CN")
                "cantopop" in normalized || "hong kong" in normalized ||
                    "粤语流行" in normalized || "粵語流行" in normalized ->
                    listOf("zh-Hans-CN")
                "thai" in normalized -> listOf("th-TH")
                "russian" in normalized -> listOf("ru-RU")
                "ukrain" in normalized -> listOf("uk-UA")
                "arab" in normalized -> listOf("ar-SA")
                "israel" in normalized || "hebrew" in normalized -> listOf("he-IL")
                "indian" in normalized || "bollywood" in normalized -> listOf("hi-IN")
                "greek" in normalized -> listOf("el-GR")
                "bulgar" in normalized -> listOf("bg-BG")
                else -> emptyList()
            }
        }

        internal fun languageTagsForIsrc(isrc: String?): List<String> {
            val country = isrc.orEmpty().trim().take(2).uppercase()
            return when (country) {
                "JP" -> listOf("ja-JP")
                "KR" -> listOf("ko-KR")
                "CN", "HK", "MO", "TW" -> listOf("zh-Hans-CN")
                else -> emptyList()
            }
        }

        internal fun languageTagsForOriginalMetadata(
            genre: String?,
            isrc: String?,
        ): List<String> = languageTagsForOriginalMetadata(
            genre = genre,
            catalogGenres = emptyList(),
            isrc = isrc,
        )

        internal fun languageTagsForOriginalMetadata(
            genre: String?,
            catalogGenres: Collection<String>,
            isrc: String?,
            artistLanguages: Collection<String> = emptyList(),
        ): List<String> {
            val genreLanguages = sequenceOf(genre)
                .plus(catalogGenres.asSequence())
                .filterNotNull()
                .map(::knownLanguageTagsForGenre)
                .firstOrNull(List<String>::isNotEmpty)
                .orEmpty()
            if (genreLanguages.isNotEmpty()) return genreLanguages
            val isrcLanguages = languageTagsForIsrc(isrc)
            if (isrcLanguages.isNotEmpty()) return isrcLanguages
            return artistLanguages.mapNotNull(::supportedOriginalLanguageOrNull).distinct()
        }

        internal fun canonicalOriginalLanguage(language: String): String {
            val normalized = language.trim()
            return when (normalized.lowercase()) {
                "zh-hans-cn", "zh-hant-hk", "zh-hant-tw", "zh-hk", "zh-mo", "zh-tw", "zh-cn" ->
                    "zh-Hans-CN"
                else -> normalized
            }
        }

        internal fun supportedOriginalLanguageOrNull(language: String): String? =
            canonicalOriginalLanguage(language).takeIf { canonical ->
                storefrontForOriginalLanguage(canonical) != null
            }

        internal fun storefrontForOriginalLanguage(language: String): String? = when (
            canonicalOriginalLanguage(language)
        ) {
            "ja-JP" -> "jp"
            "ko-KR" -> "kr"
            "zh-Hans-CN" -> "cn"
            "th-TH" -> "th"
            "ru-RU" -> "ru"
            "uk-UA" -> "ua"
            "ar-SA" -> "sa"
            "he-IL" -> "il"
            "hi-IN" -> "in"
            "el-GR" -> "gr"
            "bg-BG" -> "bg"
            else -> null
        }

        internal fun isLegacyTraditionalChineseLanguage(language: String): Boolean =
            language.trim().lowercase() in setOf(
                "zh-hant-hk",
                "zh-hant-tw",
                "zh-hk",
                "zh-mo",
                "zh-tw",
            )

        internal fun canonicalCachedOriginalAlias(alias: Alias): Alias? {
            if (isLegacyTraditionalChineseLanguage(alias.language)) return null
            val canonicalLanguage = supportedOriginalLanguageOrNull(alias.language) ?: return null
            if (!isAcceptableOriginalAlias(alias, canonicalLanguage)) return null
            return if (canonicalLanguage == alias.language) alias
            else alias.copy(language = canonicalLanguage)
        }

        internal fun regionalOriginalAliases(
            aliases: Collection<Alias>,
            languages: Collection<String>,
        ): List<Alias> {
            val canonicalLanguages = languages.mapNotNull(::supportedOriginalLanguageOrNull).toSet()
            if (canonicalLanguages.isEmpty()) return emptyList()
            return aliases.filter { alias ->
                val aliasLanguage = supportedOriginalLanguageOrNull(alias.language)
                aliasLanguage in canonicalLanguages &&
                    isAcceptableOriginalAlias(alias, requireNotNull(aliasLanguage))
            }
        }

        internal fun isAcceptableOriginalAlias(alias: Alias, sourceLanguage: String): Boolean {
            if (alias.title.isBlank() && alias.artist.isBlank()) return false
            if (canonicalOriginalLanguage(sourceLanguage) != "zh-Hans-CN") return true
            return containsHanCharacters(alias.title) || containsHanCharacters(alias.artist)
        }

        internal fun selectExactOriginalEntityAlias(
            mediaId: String,
            lookupIds: Collection<String>,
            resolved: Map<String, Alias>,
            sourceLanguage: String,
        ): Alias? {
            resolved[mediaId.trim()]?.takeIf { alias ->
                alias.title.isNotBlank() || alias.artist.isNotBlank()
            }?.let { return it }
            return lookupIds.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { it == mediaId.trim() }
                .distinct()
                .firstNotNullOfOrNull { id ->
                resolved[id]?.takeIf { isAcceptableOriginalAlias(it, sourceLanguage) }
            }
        }

        internal fun selectExactIdentityAlias(
            aliases: Collection<Alias>,
            sourceLanguage: String,
        ): Alias? {
            val canonicalLanguage = canonicalOriginalLanguage(sourceLanguage)
            return aliases.firstOrNull { alias ->
                canonicalOriginalLanguage(alias.language) == canonicalLanguage &&
                    (alias.title.isNotBlank() || alias.artist.isNotBlank())
            }
        }

        private fun containsHanCharacters(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    return true
                }
                index += Character.charCount(codePoint)
            }
            return false
        }

        /** Script evidence used only for the artist-region probe. */
        internal fun hasCjkArtistScript(value: String, language: String): Boolean {
            val canonical = canonicalOriginalLanguage(language)
            return when (canonical) {
                "ja-JP" -> containsJapaneseKana(value)
                "ko-KR" -> containsHangul(value)
                "zh-Hans-CN" ->
                    containsHanCharacters(value) &&
                        !containsJapaneseKana(value) &&
                        !containsHangul(value)
                else -> false
            }
        }

        private fun containsJapaneseKana(value: String): Boolean = value.any { character ->
            character.code in 0x3040..0x30ff
        }

        private fun containsHangul(value: String): Boolean = value.any { character ->
            character.code in 0xac00..0xd7af
        }

        internal fun storefrontForContentUiLanguage(selection: Int): String? = when (selection) {
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> "cn"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US -> "us"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> "hk"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> "tw"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> "kr"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> "jp"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE -> null
            else -> null
        }

        internal fun languageTagsForContentUiLanguage(selection: Int): List<String> = when (selection) {
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> listOf("zh-CN")
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US ->
                listOf("zh-Hans", "zh-CN")
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> listOf("zh-HK")
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> listOf("zh-TW")
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> listOf("ko-KR")
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> listOf("ja-JP")
            else -> emptyList()
        }

        internal fun languageTagForContentUiLanguage(selection: Int): String? =
            languageTagsForContentUiLanguage(selection).firstOrNull()

        internal fun localizedStorefrontHeaderValue(
            storefront: String,
            currentValue: String?,
        ): String? {
            val storefrontId = when (storefront.trim().lowercase()) {
                "us" -> "143441"
                "gr" -> "143448"
                "jp" -> "143462"
                "hk" -> "143463"
                "cn" -> "143465"
                "kr" -> "143466"
                "in" -> "143467"
                "ru" -> "143469"
                "tw" -> "143470"
                "th" -> "143475"
                "sa" -> "143479"
                "il" -> "143491"
                "ua" -> "143492"
                "bg" -> "143526"
                else -> null
            } ?: return null
            val suffix = currentValue.orEmpty().dropWhile(Char::isDigit)
            return storefrontId + suffix
        }

        internal fun normalizedArtistNameKey(value: String): String = value
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")

        private fun artistCacheKey(
            selection: Int,
            key: String,
            language: String? = null,
        ): String {
            val normalizedLanguage = language
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.replace('_', '-')
                ?.lowercase()
            return if (normalizedLanguage == null) {
                "$selection:ARTIST_ALIAS:$ARTIST_ALIAS_CACHE_SCHEMA:${key.trim()}"
            } else {
                "$selection:ARTIST_ALIAS:$ARTIST_ALIAS_CACHE_SCHEMA:" +
                    "$normalizedLanguage:${key.trim()}"
            }
        }

        internal fun storefrontFromContentPath(pathSegments: List<String>): String? = when {
            pathSegments.size >= 3 && pathSegments[0] == "v1" &&
                pathSegments[1] in setOf("catalog", "editorial", "recommendations") ->
                pathSegments[2].takeIf { it.length == 2 }
            else -> null
        }

        internal fun isAccountScopedPlaybackPath(pathSegments: List<String>): Boolean =
            pathSegments.any { segment ->
                segment.equals("radio", ignoreCase = true) ||
                    segment.equals("station", ignoreCase = true) ||
                    segment.equals("stations", ignoreCase = true)
            }

        internal fun selectLocalizedArtistName(
            attributeArtist: String,
            relationshipArtists: List<String>,
            language: String,
        ): String {
            val names = relationshipArtists
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            if (names.isEmpty()) return attributeArtist
            val separator = if (language.startsWith("zh-")) "、" else ", "
            return names.joinToString(separator)
        }

        internal fun selectOriginalAlias(
            variants: List<Alias>,
            localizedTitle: String,
            localizedArtist: String
        ): Alias? {
            val localizedKey = "${normalize(localizedTitle)}|${normalize(localizedArtist)}"
            val candidates = variants
                .filter { alias ->
                    val aliasKey = "${normalize(alias.title)}|${normalize(alias.artist)}"
                    aliasKey != localizedKey &&
                        nonLatinLetterCount(alias.title) + nonLatinLetterCount(alias.artist) > 0
                }
                .distinctBy { alias -> "${normalize(alias.title)}|${normalize(alias.artist)}" }
            val score = compareBy<Alias> { alias -> nonLatinLetterCount(alias.title) }
                .thenBy { alias -> nonLatinLetterCount(alias.artist) }
            val originalTitle = candidates
                .filter { alias -> isOriginalTitle(alias, localizedTitle) }
                .maxWithOrNull(score)
            if (originalTitle != null) return originalTitle
            if (nonLatinLetterCount(localizedTitle) == 0) return null
            if (isCollaborationArtistName(localizedArtist)) return null
            return candidates.maxWithOrNull(score)
        }

        internal fun isConfidentOriginalSongAlias(
            alias: Alias,
            localizedTitle: String,
            localizedArtist: String,
            allowArtistOnly: Boolean = false,
        ): Boolean = isOriginalTitle(alias, localizedTitle) ||
            nonLatinLetterCount(localizedTitle) > 0 ||
            (
                allowArtistOnly &&
                    isConfidentOriginalArtistOnlyAlias(
                        alias = alias,
                        localizedTitle = localizedTitle,
                        localizedArtist = localizedArtist,
                    )
                )

        /**
         * An English title is not by itself regional evidence. This narrow
         * shape is only enabled by a trusted source language (ISRC or a
         * script-confirmed single artist) at the caller.
         */
        internal fun isConfidentOriginalArtistOnlyAlias(
            alias: Alias,
            localizedTitle: String,
            localizedArtist: String,
        ): Boolean = normalize(alias.title) == normalize(localizedTitle) &&
            nonLatinLetterCount(localizedTitle) == 0 &&
            alias.artist.isNotBlank() &&
            localizedArtist.isNotBlank() &&
            normalize(alias.artist) != normalize(localizedArtist) &&
            !isCollaborationArtistName(localizedArtist) &&
            !isCollaborationArtistName(alias.artist) &&
            hasCjkArtistScript(alias.artist, alias.language)

        internal fun isReusableOriginalSongAlias(
            alias: Alias,
            localizedTitle: String,
            localizedArtist: String,
            allowArtistOnly: Boolean = false,
        ): Boolean = isAcceptableOriginalAlias(
            alias = alias,
            sourceLanguage = canonicalOriginalLanguage(alias.language),
        ) && isConfidentOriginalSongAlias(
            alias = alias,
            localizedTitle = localizedTitle,
            localizedArtist = localizedArtist,
            allowArtistOnly = allowArtistOnly,
        )

        internal fun isCollaborationArtistName(artist: String): Boolean {
            val normalized = artist.trim()
            if (normalized.isEmpty()) return false
            return COLLABORATION_ARTIST_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
        }

        internal fun isOriginalTitle(alias: Alias, localizedTitle: String): Boolean =
            normalize(alias.title) != normalize(localizedTitle) &&
                nonLatinLetterCount(alias.title) > 0

        internal fun shouldResolve(metadata: MediaMetadataCache.Metadata): Boolean =
            AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
                mediaId = metadata.id,
                title = metadata.title,
                artist = metadata.artist,
                genre = metadata.genre,
            )

        private fun nonLatinLetterCount(value: String): Int {
            var count = 0
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                if (Character.isLetter(codePoint) &&
                    Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
                ) {
                    count++
                }
                index += Character.charCount(codePoint)
            }
            return count
        }

        private fun normalize(value: String): String = value
            .trim()
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}]+"), "")

        private val COLLABORATION_ARTIST_PATTERNS = listOf(
            Regex(
                "(?:^|[\\s(\\[])(?:feat\\.?|ft\\.?|featuring|with)(?:\\s|[:.)\\]])",
                RegexOption.IGNORE_CASE,
            ),
            Regex("\\s[&×]\\s"),
            Regex("\\s[xX]\\s"),
            Regex("[,、;/／]"),
        )

        internal fun shouldCacheCatalogIdentity(
            isrc: String?,
            genres: Collection<String>,
        ): Boolean = !isrc.isNullOrBlank() ||
            languageTagsForOriginalMetadata(
                genre = null,
                catalogGenres = genres,
                isrc = null,
            ).isNotEmpty()

        internal fun shouldRetryEmptyCatalogIdentity(
            mediaId: String?,
            title: String?,
            artist: String?,
            genre: String?,
            isrc: String?,
            catalogGenres: Collection<String>,
        ): Boolean = !shouldCacheCatalogIdentity(isrc, catalogGenres) &&
            AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
                mediaId = mediaId,
                title = title,
                artist = artist,
                genre = genre,
            )
    }
}

/**
 * 原名专辑与标题/歌手别名置信度解耦：即使整首歌曲别名因标题相同或
 * 合作署名被拒绝，也保留已解析的原地区专辑名，供桥接元数据与在线检索使用。
 */
internal fun originalAlbumFromResolution(
    alias: AppleInternalCatalogResolver.Alias?,
    acceptableResults: List<AppleInternalCatalogResolver.Alias>,
): String? = alias?.album?.takeIf(String::isNotBlank)
    ?: acceptableResults.firstNotNullOfOrNull { result ->
        result.album.takeIf(String::isNotBlank)
    }
