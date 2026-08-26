package dev.amenhancer.module.hook

import android.media.MediaMetadata
import android.os.SystemClock
import io.github.proify.lyricon.amprovider.xposed.*
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires the complete HLE metadata surface without bringing HLE's lyric
 * provider lifecycle into AM++. The surface modules are the original HLE
 * implementations; this class only supplies the host callbacks that connect
 * them to the transplanted resolver, cache and AM++ process lifecycle.
 */
internal class HleMetadataSurfaceBridge(
    private val runtime: AppleMusicProviderRuntime,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val metadataStore: AppleMetadataOverrideStore,
    private val playbackCoordinator: ApplePlaybackMetadataCoordinator,
    private val playbackHooks: io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks,
    private val frameworkHooks: AppleFrameworkMetadataHooks,
    private val contentItemHooks: AppleContentItemMetadataHooks,
    private val queueMetadataHooks: AppleQueueMetadataHooks,
    private val actionSheetMetadataHooks: AppleActionSheetMetadataHooks,
) {
    private val traceSequence = AtomicLong(0L)
    private val registry = AppleInAppMetadataRegistry()

    private lateinit var surfaceRuntime: AppleMetadataSurfaceRuntime
    private lateinit var librarySurfaceHooks: AppleLibrarySurfaceHooks
    private lateinit var dataBindingHooks: AppleDataBindingMetadataHooks
    private lateinit var collectionSurfaceHooks: AppleCollectionSurfaceHooks
    private lateinit var artistSurfaceHooks: AppleArtistSurfaceHooks
    private lateinit var listenNowHooks: AppleListenNowHooks
    private lateinit var mediaApiMetadataCoordinator: AppleMediaApiMetadataCoordinator
    private lateinit var resolutionCoordinator: AppleInAppMetadataResolutionCoordinator
    private lateinit var metadataApplier: AppleInAppMetadataApplier
    private lateinit var metadataRegistrationCoordinator: AppleInAppMetadataRegistrationCoordinator
    private lateinit var metadataOverrideApplicationCoordinator:
        AppleMetadataOverrideApplicationCoordinator
    private lateinit var media3MetadataCoordinator: AppleMedia3MetadataCoordinator
    private lateinit var playbackItemConversionHooks: ApplePlaybackItemConversionHooks
    private lateinit var inAppArtworkContinuityHooks: AppleInAppArtworkContinuityHooks
    private lateinit var visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics

    fun install() {
        surfaceRuntime = AppleMetadataSurfaceRuntime(
            runtime = runtime,
            host = proxy { name, args -> surfaceValue(name, args) },
        )
        librarySurfaceHooks = AppleLibrarySurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            host = proxy { name, args -> libraryValue(name, args) },
        )
        dataBindingHooks = AppleDataBindingMetadataHooks(
            runtime = runtime,
            host = proxy { name, args -> dataBindingValue(name, args) },
        )
        collectionSurfaceHooks = AppleCollectionSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = proxy { name, args -> collectionValue(name, args) },
        )
        artistSurfaceHooks = AppleArtistSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = proxy { name, args -> artistValue(name, args) },
        )
        mediaApiMetadataCoordinator = AppleMediaApiMetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            librarySurfaceHooks = librarySurfaceHooks,
            artistSurfaceHooks = artistSurfaceHooks,
            host = proxy { name, args -> mediaApiValue(name, args) },
        )
        resolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = proxy { name, args -> resolutionValue(name, args) },
        )
        listenNowHooks = AppleListenNowHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = proxy { name, args -> listenNowValue(name, args) },
        )
        metadataApplier = AppleInAppMetadataApplier(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            contentItemMetadataHooks = contentItemHooks,
            librarySurfaceHooks = librarySurfaceHooks,
            collectionSurfaceHooks = collectionSurfaceHooks,
            artistSurfaceHooks = artistSurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            listenNowHooks = listenNowHooks,
            queueMetadataHooks = queueMetadataHooks,
            traceSequence = traceSequence,
            logMetadataIdentity = { event, details ->
                ProviderLogger.diagnostic("$event: $details")
            },
        )
        media3MetadataCoordinator = AppleMedia3MetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            resolutionCoordinator = resolutionCoordinator,
            frameworkMetadataHooks = frameworkHooks,
            queueMetadataHooks = queueMetadataHooks,
            playbackMetadataCoordinator = playbackCoordinator,
            traceSequence = traceSequence,
        )
        metadataRegistrationCoordinator = AppleInAppMetadataRegistrationCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            resolutionCoordinator = resolutionCoordinator,
            catalogResolver = catalogResolver,
            contentItemMetadataHooks = contentItemHooks,
            metadataApplier = metadataApplier,
            surfaceRuntime = surfaceRuntime,
            dataBindingHooks = dataBindingHooks,
            configuredContentUiLanguage = { 0 },
        )
        visibleMetadataDiagnostics = AppleVisibleMetadataDiagnostics(
            runtime = runtime,
            host = proxy { name, args -> visibleDiagnosticsValue(name, args) },
        )
        metadataOverrideApplicationCoordinator = AppleMetadataOverrideApplicationCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            resolutionCoordinator = resolutionCoordinator,
            catalogResolver = catalogResolver,
            surfaceRuntime = surfaceRuntime,
            metadataApplier = metadataApplier,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            listenNowHooks = listenNowHooks,
            actionSheetMetadataHooks = actionSheetMetadataHooks,
            playbackMetadataCoordinator = playbackCoordinator,
            frameworkMetadataHooks = frameworkHooks,
            visibleMetadataDiagnostics = visibleMetadataDiagnostics,
            media3MetadataCoordinator = media3MetadataCoordinator,
            configuredContentUiLanguage = { 0 },
            traceSequence = traceSequence,
        )
        playbackItemConversionHooks = ApplePlaybackItemConversionHooks(
            runtime = runtime,
            host = proxy { name, args -> playbackItemValue(name, args) },
        )
        inAppArtworkContinuityHooks = AppleInAppArtworkContinuityHooks(
            runtime = runtime,
            host = proxy { name, args -> artworkContinuityValue(name, args) },
        )

        installSafely("metadata-surface-lifecycle") { surfaceRuntime.installLifecycleHooks() }
        installSafely("library-entity") { librarySurfaceHooks.installEntityHooks() }
        installSafely("library-compose") { librarySurfaceHooks.installComposeHooks() }
        installSafely("library-epoxy") { librarySurfaceHooks.installEpoxyHooks() }
        installSafely("data-binding") { dataBindingHooks.installDataBindingHooks() }
        installSafely("recycler") { dataBindingHooks.installRecyclerHooks() }
        installSafely("collection") { collectionSurfaceHooks.installHooks() }
        installSafely("artist-top-songs") { artistSurfaceHooks.installTopSongHooks() }
        installSafely("artist-profile") { artistSurfaceHooks.installProfileHooks() }
        installSafely("listen-now-artwork") { listenNowHooks.installArtworkContinuityHooks() }
        installSafely("listen-now-binding") { listenNowHooks.installMetadataBindingHooks() }
        installSafely("recently-searched") { mediaApiMetadataCoordinator.installRecentlySearchedHooks() }
        installSafely("in-app-artwork-continuity") { inAppArtworkContinuityHooks.installHooks() }
        installSafely("playback-item-conversion") { playbackItemConversionHooks.installHooks() }
    }

    fun ensureOverride(
        mediaId: String,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
    ) = resolutionCoordinator.ensureOverride(mediaId, preBind, priority)

    fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        originalResolutionLimit: Int = mediaIds.size,
    ) = resolutionCoordinator.ensureOverrides(mediaIds, preBind, originalResolutionLimit)

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) = metadataRegistrationCoordinator.registerMetadata(
        mediaId = mediaId,
        metadata = metadata,
        requestResolution = requestResolution,
        preBind = preBind,
        priority = priority,
    )

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    ) = metadataRegistrationCoordinator.registerPlaybackItem(
        mediaId = mediaId,
        playbackItem = playbackItem,
        notifyChange = notifyChange,
        analyzeMetadata = analyzeMetadata,
    )

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    ) = metadataApplier.applyAliasToPlaybackItem(playbackItem, alias, notifyChange)

    /**
     * Keep playback resolution publication on the same HLE coordinator path as
     * the original provider.  The coordinator owns the in-app rebind policy,
     * album/artist propagation, and persistent original-region bookkeeping;
     * writing only the two stores here leaves library rows stale until playback.
     */
    fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) = metadataOverrideApplicationCoordinator.apply(
        mediaId = mediaId,
        alias = alias,
        forceInAppRebind = forceInAppRebind,
        rememberLocalizedArtist = rememberLocalizedArtist,
        originalMetadata = originalMetadata,
        originalMetadataConfirmed = originalMetadataConfirmed,
        artistOnly = artistOnly,
        propagateArtistEntity = propagateArtistEntity,
    )

    fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) = metadataApplier.applyAliasToContainerItem(containerItem, kind, alias, notifyChange)

    fun markMetadataVisible(mediaIds: Collection<String>) = surfaceRuntime.markVisible(mediaIds)

    fun setPlaybackMediaId(mediaId: String) = surfaceRuntime.setPlaybackMediaId(mediaId)

    fun requestPriority(mediaId: String): AppleInternalCatalogResolver.RequestPriority =
        surfaceRuntime.requestContext(mediaId).priority

    fun contentItemLocalizedEntityType(contentItem: Any): AppleInternalCatalogResolver.LocalizedEntityType? =
        metadataRegistrationCoordinator.contentItemLocalizedEntityType(contentItem)

    fun recordComposeMediaId(mediaId: String) = librarySurfaceHooks.recordComposeMediaId(mediaId)

    fun recordCurrentRecyclerMediaId(mediaId: String) {
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
    }

    fun shouldRequestOverride(mediaId: String): Boolean =
        resolutionCoordinator.shouldRequestOverride(mediaId)

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: AppleInternalCatalogResolver.Alias?,
    ): Boolean = resolutionCoordinator.shouldShareOriginalSongLanguage(
        localizedTitle = localizedTitle,
        localizedArtist = localizedArtist,
        alias = alias,
    )

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String) =
        resolutionCoordinator.rememberOriginalLanguageForArtist(mediaId, language)

    fun containerNavigationBinding(containerItem: Any): InAppContainerNavigationRef? =
        metadataRegistrationCoordinator.containerNavigationBinding(containerItem)

    fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) = metadataRegistrationCoordinator.registerContainerItem(mediaId, containerItem, kind)

    fun rawContentItemValue(contentItem: Any, runtimeMember: AppleMusicRuntimeMember): Any? =
        metadataRegistrationCoordinator.rawContentItemValue(contentItem, runtimeMember)

    fun knownValues(mediaId: String, field: VisibleTextField): Set<String> = buildSet {
        metadataStore.accountMetadata(mediaId)?.let { addAll(listOfNotNull(it.title, it.artist)) }
        alias(mediaId)?.let { value ->
            when (field) {
                VisibleTextField.TITLE -> add(value.title)
                VisibleTextField.ARTIST -> add(value.artist)
                VisibleTextField.ALBUM -> add(value.album)
            }
        }
    }

    fun hasLivePlaybackItem(mediaId: String): Boolean = registry.hasLivePlaybackItem(mediaId)

    fun markPlaybackItemHistory(playbackItem: Any) = registry.markPlaybackItemContract(
        playbackItem,
        InAppPlaybackItemContract.HISTORY,
    )

    fun recordArtistAssociation(mediaId: String, item: Any, rawTitle: String?) {
        val artistKeys = metadataRegistrationCoordinator.contentItemArtistCacheKeys(item, rawTitle)
        if (artistKeys.isNotEmpty()) {
            metadataStore.mergeArtistKeys(mediaId, artistKeys)
        }
        resolutionCoordinator.mergePlaybackAssociatedArtistIds(
            mediaId = mediaId,
            artistIds = io.github.proify.lyricon.amprovider.xposed.artistIdsFromAssociationKeys(artistKeys) +
                metadataRegistrationCoordinator.contentItemCatalogLookupIds(item, mediaId = "")
                    .filterNot { it == mediaId },
        )
    }

    private fun installSafely(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            ProviderLogger.error("HLE $name surface hook unavailable", it)
        }
    }

    private fun currentIdentity(): ActivePlaybackMediaIdentity = ActivePlaybackMediaIdentity(
        mediaId = playbackCoordinator.currentMetadataId(),
        source = "ampp_hle",
        candidates = playbackCoordinator.currentMetadataId().orEmpty(),
    )

    private fun alias(mediaId: String?): AppleInternalCatalogResolver.Alias? =
        mediaId?.let { metadataStore.originalMetadata(it) ?: metadataStore.configuredMetadata(it) }

    private fun markVisible(ids: Collection<String>) {
        surfaceRuntime.markVisible(ids)
    }

    private fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        io.github.proify.lyricon.amprovider.xposed.shouldRetryOriginalMetadataCacheProbe(
            originalResolved = metadataStore.isOriginalResolved(mediaId),
            lastMissUptimeMillis = metadataStore.originalCacheMissUptimeMillis(mediaId),
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

    private fun ensureOverride(
        mediaId: String,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
    ) {
        if (::resolutionCoordinator.isInitialized && resolutionCoordinator.shouldRequestOverride(mediaId)) {
            resolutionCoordinator.ensureOverride(mediaId, preBind = false, priority = priority)
        }
    }

    private fun applyAliasToObject(
        target: Any,
        value: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    ) {
        val writes = listOf(
            "setTitle" to value.title,
            "setArtistName" to value.artist,
            "setCollectionName" to value.album,
        )
        writes.forEach { (method, text) ->
            if (text.isBlank()) return@forEach
            runCatching { AppleReflection.call(target, method, text) }
        }
        if (notifyChange) {
            runCatching { AppleReflection.call(target, "notifyChange") }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (String, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, args ->
        runCatching { handler(method.name, args ?: emptyArray()) }
            .getOrElse { error ->
                ProviderLogger.debug("HLE host callback ${method.name} failed: ${error.message}")
                defaultValue(method.returnType)
            }
    } as T

    private fun defaultValue(type: Class<*>): Any? = when {
        type == Boolean::class.javaPrimitiveType -> false
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type.isEnum -> type.enumConstants?.firstOrNull()
        Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
        Collection::class.java.isAssignableFrom(type) -> emptyList<Any>()
        List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        else -> null
    }

    private fun arg(args: Array<out Any?>, index: Int): Any? = args.getOrNull(index)

    private fun surfaceValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "catalogResolver" -> catalogResolver
        "associatedArtistIds" -> metadataStore.associatedArtistIds(arg(args, 0) as String)
        "hasVisibleExactConsumer" -> dataBindingHooks.hasVisibleExactConsumer(arg(args, 0) as String)
        "hasGenericRecyclerConsumer" -> dataBindingHooks.hasGenericRecyclerRefs(arg(args, 0) as String)
        "detachController" -> {
            val owner = arg(args, 0) ?: return null
            val removed = librarySurfaceHooks.detachController(owner)
            collectionSurfaceHooks.clearController(owner)
            artistSurfaceHooks.clearController(owner)
            removed
        }
        "describeView" -> (arg(args, 0) as? android.view.View)?.toString().orEmpty()
        "logMetadataIdentity" -> ProviderLogger.diagnostic(
            "${arg(args, 0)}: ${arg(args, 1)}",
        )
        else -> null
    }

    private fun libraryValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "contentItemMediaId" -> (arg(args, 0) as? Any)?.let { contentItemHooks.mediaId(it) }
        "primeLibrarySource" -> mediaApiMetadataCoordinator.primeLibrarySource(arg(args, 0))
        "mediaApiEntityAttributes" -> mediaApiMetadataCoordinator.entityAttributes(arg(args, 0)!!)
        "mediaApiEntityCatalogId" -> mediaApiMetadataCoordinator.entityCatalogId(arg(args, 0)!!, arg(args, 1))
        "mediaApiEntityLookupIds" -> mediaApiMetadataCoordinator.entityLookupIds(arg(args, 0)!!, arg(args, 1))
        "mergePlaybackAccountMetadata" -> {
            metadataStore.mergeAccountMetadata(
                arg(args, 0) as String,
                AccountMetadata(arg(args, 1) as String?, arg(args, 2) as String?),
            )
        }
        "requestPriorityForMediaId" -> requestPriority(arg(args, 0) as String)
        "enrichEntityAssociations" -> mediaApiMetadataCoordinator.enrichLibraryEntityAssociations(
            mediaId = arg(args, 0) as String,
            entity = arg(args, 1)!!,
            kind = arg(args, 2) as InAppLibraryEntityKind,
            attributes = arg(args, 3)!!,
            originalName = arg(args, 4) as String?,
            originalArtist = arg(args, 5) as String?,
            originalAlbum = arg(args, 6) as String?,
        )
        "recordCurrentRecyclerMediaId" -> dataBindingHooks.recordCurrentRecyclerMediaId(arg(args, 0) as String)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "normalizeMediaIds" -> normalizedRecyclerBindingMediaIds(arg(args, 0) as Collection<String>).toList()
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "applyAliasToMetadataRefs" -> metadataApplier.applyAliasToMetadataRefs(
            mediaId = arg(args, 0) as String,
            alias = arg(args, 1) as AppleInternalCatalogResolver.Alias,
            forceRebind = true,
            notifyModelChange = true,
        )
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(
            mediaIds = arg(args, 0) as Collection<String>,
            priority = arg(args, 1) as AppleInternalCatalogResolver.RequestPriority,
        )
        "isRefreshableMediaId" -> surfaceRuntime.isRefreshable(arg(args, 0) as String)
        "nextMetadataTraceSequence" -> traceSequence.incrementAndGet()
        "debugStackSummary" -> visibleMetadataDiagnostics.stackSummary()
        "controllerBuildStrategy" -> inAppLibraryControllerBuildStrategy(
            hasAlbumBuildData = collectionSurfaceHooks.hasAlbumBuildData(arg(args, 0)!!),
            hasArtistBuildData = artistSurfaceHooks.hasBuildData(arg(args, 0)!!),
            isPlaylistPageController = collectionSurfaceHooks.isPlaylistController(arg(args, 0)!!),
        )
        "controllerAppliedAlias" -> collectionSurfaceHooks.controllerAppliedAlias(
            controller = arg(args, 0)!!,
            mediaId = arg(args, 1) as String,
            alias = arg(args, 2) as AppleInternalCatalogResolver.Alias,
        )
        "controllerAlbumTrackMediaIds" -> collectionSurfaceHooks.albumTrackMediaIds(arg(args, 0)!!)
        "requestControllerBuild" -> {
            metadataApplier.requestLibraryControllerBuild(
                controller = arg(args, 0)!!,
                strategy = arg(args, 1) as InAppLibraryControllerBuildStrategy,
            )
        }
        "logMetadataIdentity" -> ProviderLogger.diagnostic("${arg(args, 0)}: ${arg(args, 1)}")
        else -> null
    }

    private fun dataBindingValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "contentItemMediaId" -> contentItemHooks.mediaId(arg(args, 0)!!)
        "bindingCandidateMediaId" -> registry.metadataId(arg(args, 0)!!)
            ?: registry.playbackItemId(arg(args, 0)!!)
            ?: librarySurfaceHooks.entityMediaId(arg(args, 0)!!)
            ?: librarySurfaceHooks.attributeBindingMediaId(arg(args, 0)!!)
        "onBeginBindingModel" -> artistSurfaceHooks.onBeginBindingModel(arg(args, 0)!!)
        "onBindingMediaIdChanged" -> artistSurfaceHooks.onBindingMediaIdChanged(arg(args, 0)!!, arg(args, 2) as String)
        "originalResolutionMode" -> artistSurfaceHooks.originalResolutionMode(arg(args, 0)!!)
        "shouldInvalidateAppliedAlias" -> {
            val mediaId = arg(args, 1) as? String ?: return false
            val effective = alias(mediaId) ?: return false
            val values = metadataApplier.dataBindingAliasValues(
                mediaId = mediaId,
                alias = effective,
                binding = arg(args, 0),
            )
            artistSurfaceHooks.shouldInvalidateAppliedAlias(
                binding = arg(args, 0)!!,
                mediaId = mediaId,
                appliedAlias = arg(args, 2) as AppliedMetadataAlias,
                pendingAlias = arg(args, 3) as AppliedMetadataAlias?,
                effectiveAlias = effective,
                expectedTitle = values.title,
                renderedTexts = arg(args, 4) as Collection<String>,
            )
        }
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "aliasValues" -> {
            val value = arg(args, 1) as AppleInternalCatalogResolver.Alias
            metadataApplier.dataBindingAliasValues(
                mediaId = arg(args, 0) as String,
                alias = value,
                binding = arg(args, 2),
            )
        }
        "isCurrentSurfaceMediaId" -> arg(args, 0) == playbackCoordinator.currentMetadataId()
        "hasVisibleConsumer" -> surfaceRuntime.hasVisibleConsumer(arg(args, 0) as String)
        "isRefreshableMediaId" -> surfaceRuntime.isRefreshable(arg(args, 0) as String)
        "boundModelCandidates" -> registry.livePlaybackItems(arg(args, 0) as String) +
            librarySurfaceHooks.liveEntities(arg(args, 0) as String)
        "enrichEntitiesForResolution" -> mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(arg(args, 0) as Collection<String>)
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(arg(args, 0) as Collection<String>, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority, arg(args, 2) as InAppOriginalResolutionMode)
        "isAppleLyricsRecyclerAdapter" -> false
        "isQueueAdapter" -> queueMetadataHooks.isQueueAdapter(arg(args, 0)!!)
        "isArtistProfileRecyclerAdapter" -> artistSurfaceHooks.isRecyclerAdapter(arg(args, 0)!!)
        "nextMetadataTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    private fun collectionValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "mediaApiEntityAttributes" -> mediaApiMetadataCoordinator.entityAttributes(arg(args, 0)!!)
        "mediaApiEntityCatalogId" -> mediaApiMetadataCoordinator.entityCatalogId(arg(args, 0)!!, arg(args, 1))
        "mediaApiAttribute" -> mediaApiMetadataCoordinator.attribute(arg(args, 0)!!, arg(args, 1) as AppleMediaApiTextAttribute)
        "registerLibraryEntity" -> mediaApiMetadataCoordinator.registerLibraryEntity(
            mediaId = arg(args, 0) as String,
            entity = arg(args, 1)!!,
            kind = arg(args, 2) as InAppLibraryEntityKind,
            knownAttributes = arg(args, 3),
            requestResolution = arg(args, 4) as Boolean,
            retainEntityRef = arg(args, 5) as Boolean,
        )
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "enrichLibraryEntitiesForResolution" -> mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(arg(args, 0) as Collection<String>)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "applyAliasToMetadataRefs" -> metadataApplier.applyAliasToMetadataRefs(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.Alias, true, true)
        "shouldRequestOverride" -> resolutionCoordinator.shouldRequestOverride(arg(args, 0) as String)
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(arg(args, 0) as Collection<String>, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority)
        "dataBindingAliasValues" -> {
            val value = arg(args, 1) as AppleInternalCatalogResolver.Alias
            metadataApplier.dataBindingAliasValues(
                mediaId = arg(args, 0) as String,
                alias = value,
                binding = arg(args, 2),
            )
        }
        "sharedAssociatedArtistId" -> resolutionCoordinator.sharedAssociatedArtistId(arg(args, 0) as String)
        "onMetadataPageAttached" -> surfaceRuntime.onPageAttached(arg(args, 0)!!, arg(args, 1) as androidx.recyclerview.widget.RecyclerView)
        "onMetadataPageDetached" -> surfaceRuntime.onPageDetached(arg(args, 0)!!)
        "handleArtistFinalBinding" -> artistSurfaceHooks.handleFinalBinding(arg(args, 0)!!, arg(args, 1), arg(args, 2) as Int?)
        "nextMetadataTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    /**
     * Artist surface host has the intentionally smaller HLE contract: it does
     * not expose requestResolution/retainEntityRef.  Calling it through the
     * generic six-argument adapter makes Kotlin's default-argument bridge pass
     * nulls, which then fail while unboxing Boolean values in the proxy.
     */
    private fun registerArtistLibraryEntity(args: Array<out Any?>) {
        mediaApiMetadataCoordinator.registerLibraryEntity(
            mediaId = arg(args, 0) as String,
            entity = arg(args, 1)!!,
            kind = arg(args, 2) as InAppLibraryEntityKind,
            knownAttributes = arg(args, 3),
            requestResolution = false,
            retainEntityRef = true,
        )
    }

    private fun artistValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "mediaApiEntityAttributes" -> mediaApiMetadataCoordinator.entityAttributes(arg(args, 0)!!)
        "mediaApiEntityCatalogId" -> mediaApiMetadataCoordinator.entityCatalogId(arg(args, 0)!!, arg(args, 1))
        "mediaApiAttribute" -> mediaApiMetadataCoordinator.attribute(arg(args, 0)!!, arg(args, 1) as AppleMediaApiTextAttribute)
        "mediaApiEntityRelationshipEntities" -> mediaApiMetadataCoordinator.relationshipEntities(
            entity = arg(args, 0)!!,
            relationshipKey = arg(args, 1) as String,
        )
        "registerLibraryEntity" -> registerArtistLibraryEntity(args)
        "enrichLibraryEntity" -> librarySurfaceHooks.enrichEntity(arg(args, 0) as String, arg(args, 1)!!, arg(args, 2) as InAppLibraryEntityKind, arg(args, 3)!!)
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "enrichLibraryEntitiesForResolution" -> mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(arg(args, 0) as Collection<String>)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "applyAliasToMetadataRefs" -> metadataApplier.applyAliasToMetadataRefs(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.Alias, true, true)
        "shouldRequestOverride" -> resolutionCoordinator.shouldRequestOverride(arg(args, 0) as String)
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(arg(args, 0) as Collection<String>, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority, arg(args, 2) as InAppOriginalResolutionMode)
        "retryOriginalMetadata" -> resolutionCoordinator.retryOriginalMetadata(
            mediaIds = arg(args, 0) as Collection<String>,
            priority = arg(args, 1) as AppleInternalCatalogResolver.RequestPriority,
            originalResolutionMode = arg(args, 2) as InAppOriginalResolutionMode,
        )
        "activeMetadataPageOwner" -> surfaceRuntime.activePageOwner()
        "knownArtistProfileCredits" -> mediaApiMetadataCoordinator.knownArtistProfileCredits(arg(args, 0) as String)
        "onMetadataPageAttached" -> surfaceRuntime.onPageAttached(arg(args, 0)!!, arg(args, 1) as androidx.recyclerview.widget.RecyclerView)
        "onMetadataPageDetached" -> surfaceRuntime.onPageDetached(arg(args, 0)!!)
        "nextMetadataTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    private fun mediaApiValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "contentItemMediaId" -> contentItemHooks.mediaId(arg(args, 0)!!)
        "registerPlaybackItem" -> metadataRegistrationCoordinator.registerPlaybackItem(arg(args, 0) as String, arg(args, 1)!!, arg(args, 2) as Boolean, arg(args, 3) as Boolean)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "applyAliasToPlaybackItem" -> applyAliasToObject(arg(args, 0)!!, arg(args, 1) as AppleInternalCatalogResolver.Alias, arg(args, 2) as Boolean)
        "shouldShareOriginalSongLanguage" -> resolutionCoordinator.shouldShareOriginalSongLanguage(
            localizedTitle = arg(args, 0) as String?,
            localizedArtist = arg(args, 1) as String?,
            alias = arg(args, 2) as AppleInternalCatalogResolver.Alias?,
        )
        "rememberOriginalLanguageForArtist" -> resolutionCoordinator.rememberOriginalLanguageForArtist(
            mediaId = arg(args, 0) as String,
            language = arg(args, 1) as String,
        )
        "hydrateSharedArtistOverrides" -> resolutionCoordinator.hydrateSharedArtistOverrides(arg(args, 0) as String)
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "applyAliasToMetadataRefs" -> metadataApplier.applyAliasToMetadataRefs(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.Alias, true, true)
        "shouldRequestOverride" -> resolutionCoordinator.shouldRequestOverride(arg(args, 0) as String)
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(arg(args, 0) as Collection<String>, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority)
        "configuredContentUiLanguage" -> 0
        "nextTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    private fun resolutionValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "currentPlaybackMetadataId" -> playbackCoordinator.currentMetadataId()
        "configuredContentUiLanguage" -> 0
        "shouldOverrideAccountLanguage" -> false
        "isRestoreOriginalEnabled" -> true
        "refreshRequestScope" -> surfaceRuntime.refreshRequestScope()
        "enrichLibraryEntitiesForResolution" -> mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(arg(args, 0) as Collection<String>)
        "applyAliasToMetadataRefs" -> metadataApplier.applyAliasToMetadataRefs(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.Alias, arg(args, 2) as Boolean, arg(args, 3) as Boolean)
        "applyPlaybackMetadataOverride" -> applyPlaybackOverride(args)
        "logMetadataIdentity" -> ProviderLogger.diagnostic("${arg(args, 0)}: ${arg(args, 1)}")
        "nextTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    private fun applyPlaybackOverride(args: Array<out Any?>) {
        val mediaId = arg(args, 0) as String
        val value = arg(args, 1) as AppleInternalCatalogResolver.Alias
        if (::surfaceRuntime.isInitialized && ::metadataOverrideApplicationCoordinator.isInitialized) {
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = value,
                forceInAppRebind = arg(args, 2) as? Boolean ?: true,
                rememberLocalizedArtist = arg(args, 3) as? Boolean ?: true,
                originalMetadata = arg(args, 4) as? Boolean ?: false,
                originalMetadataConfirmed = arg(args, 5) as? Boolean ?: false,
                artistOnly = arg(args, 6) as? Boolean ?: false,
                propagateArtistEntity = arg(args, 7) as? Boolean ?: true,
            )
        } else {
            val original = arg(args, 4) as? Boolean ?: false
            val confirmed = arg(args, 5) as? Boolean ?: false
            if (original) metadataStore.rememberOriginalMetadata(mediaId, value, confirmed)
            else metadataStore.rememberConfiguredMetadata(mediaId, value)
            frameworkHooks.refreshMediaSessionMetadata(mediaId, value)
        }
    }

    private fun listenNowValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "mediaApiEntityAttributes" -> mediaApiMetadataCoordinator.entityAttributes(arg(args, 0)!!)
        "mediaApiEntityCatalogId" -> mediaApiMetadataCoordinator.entityCatalogId(arg(args, 0)!!, arg(args, 1))
        "registerLibraryEntity" -> librarySurfaceHooks.registerEntity(arg(args, 0) as String, arg(args, 1)!!, arg(args, 2) as InAppLibraryEntityKind, arg(args, 3), arg(args, 4) as Boolean, arg(args, 5) as Boolean)
        "enrichLibraryEntity" -> librarySurfaceHooks.enrichEntity(arg(args, 0) as String, arg(args, 1)!!, arg(args, 2) as InAppLibraryEntityKind, arg(args, 3)!!)
        "isRestoreOriginalMetadataEnabled" -> true
        "shouldRetryOriginalMetadataCacheProbe" -> shouldRetryOriginalMetadataCacheProbe(arg(args, 0) as String)
        "rememberOriginalMetadataOverride" -> metadataStore.rememberOriginalMetadata(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.Alias, arg(args, 2) as Boolean)
        "rememberOriginalLanguageForArtist" -> resolutionCoordinator.rememberOriginalLanguageForArtist(
            mediaId = arg(args, 0) as String,
            language = arg(args, 1) as String,
        )
        "resolveCachedOriginalEntityForInApp" -> resolutionCoordinator.resolveCachedOriginalEntity(arg(args, 0) as String, arg(args, 1) as AppleInternalCatalogResolver.LocalizedEntityType, arg(args, 2) as Boolean, arg(args, 3) as AppleInternalCatalogResolver.RequestPriority)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "applyAliasToLibraryEntity" -> librarySurfaceHooks.applyAliasToEntity(arg(args, 0)!!, arg(args, 1) as InAppLibraryEntityKind, arg(args, 2) as AppleInternalCatalogResolver.Alias)
        "shouldRequestOverride" -> resolutionCoordinator.shouldRequestOverride(arg(args, 0) as String)
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "scheduleMetadataResolution" -> resolutionCoordinator.schedule(arg(args, 0) as Collection<String>, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority, arg(args, 2) as InAppOriginalResolutionMode)
        "nextMetadataTraceSequence" -> traceSequence.incrementAndGet()
        "isDataBindingInstance" -> dataBindingHooks.isBindingInstance(arg(args, 0)!!)
        "dataBindingFromHolder" -> dataBindingHooks.bindingFromHolder(arg(args, 0))
        "beginDataBindingModelBind" -> dataBindingHooks.beginModelBind(arg(args, 0)!!)
        "clearDataBindingMediaId" -> dataBindingHooks.clearMediaId(arg(args, 0)!!)
        "dataBindingGeneration" -> dataBindingHooks.generation(arg(args, 0)!!)
        "captureDataBinding" -> dataBindingHooks.capture(arg(args, 0)!!)
        "registerDataBinding" -> dataBindingHooks.register(arg(args, 0) as String, arg(args, 1)!!)
        "aliasValues" -> metadataApplier.dataBindingAliasValues(
            mediaId = arg(args, 0) as String,
            alias = arg(args, 1) as AppleInternalCatalogResolver.Alias,
            binding = arg(args, 2),
        )
        "renderedTexts" -> dataBindingHooks.renderedTexts(arg(args, 0)!!)
        "appliedAlias" -> dataBindingHooks.appliedAlias(arg(args, 0)!!)
        "rememberAppliedAlias" -> dataBindingHooks.rememberAppliedAlias(arg(args, 0)!!, arg(args, 1) as AppliedMetadataAlias)
        "applyAliasVariables" -> dataBindingHooks.applyAliasVariables(
            binding = arg(args, 0)!!,
            values = arg(args, 1) as DataBindingAliasValues,
        )
        "invalidateDataBinding" -> dataBindingHooks.invalidate(arg(args, 0)!!)
        "executePendingDataBindings" -> dataBindingHooks.executePending(arg(args, 0)!!)
        "logMetadataIdentity" -> ProviderLogger.diagnostic("${arg(args, 0)}: ${arg(args, 1)}")
        else -> null
    }

    private fun playbackItemValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "containerKind" -> metadataRegistrationCoordinator.containerKind(arg(args, 0)!!)
        "metadataId" -> media3MetadataCoordinator.mediaId(arg(args, 0)!!, arg(args, 1) as String?, true)
        "activePlaybackIdentity" -> currentIdentity()
        "metadataDetails" -> arg(args, 0)?.javaClass?.name.orEmpty()
        "logMetadataIdentity" -> ProviderLogger.diagnostic("${arg(args, 0)}: ${arg(args, 2)}")
        "markContainerNavigationItem" -> metadataRegistrationCoordinator.markContainerNavigationItem(arg(args, 0)!!, arg(args, 1) as InAppContainerKind, arg(args, 2) as String)
        "markMetadataVisible" -> markVisible(arg(args, 0) as Collection<String>)
        "registerContainerItem" -> metadataRegistrationCoordinator.registerContainerItem(arg(args, 0) as String, arg(args, 1)!!, arg(args, 2) as InAppContainerKind)
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "applyAliasToContainerItem" -> metadataApplier.applyAliasToContainerItem(arg(args, 0)!!, arg(args, 1) as InAppContainerKind, arg(args, 2) as AppleInternalCatalogResolver.Alias)
        "contentItemMediaId" -> contentItemHooks.mediaId(arg(args, 0)!!)
        "registerPlaybackItem" -> metadataRegistrationCoordinator.registerPlaybackItem(arg(args, 0) as String, arg(args, 1)!!)
        "applyAliasToPlaybackItem" -> applyAliasToObject(arg(args, 0)!!, arg(args, 1) as AppleInternalCatalogResolver.Alias, true)
        "shouldRequestOverride" -> resolutionCoordinator.shouldRequestOverride(arg(args, 0) as String)
        "ensureOverride" -> resolutionCoordinator.ensureOverride(arg(args, 0) as String, false, arg(args, 1) as AppleInternalCatalogResolver.RequestPriority)
        else -> null
    }

    private fun visibleDiagnosticsValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "activePlaybackIdentity" -> currentIdentity()
        "effectiveAlias" -> alias(arg(args, 0) as String)
        "activeMetadataValues" -> buildSet {
            val mediaId = arg(args, 0) as String
            metadataStore.accountMetadata(mediaId)?.let { addAll(listOfNotNull(it.title, it.artist)) }
            alias(mediaId)?.let { addAll(listOf(it.title, it.artist, it.album)) }
        }
        "nextTraceSequence" -> traceSequence.incrementAndGet()
        else -> null
    }

    private fun artworkContinuityValue(name: String, args: Array<out Any?>): Any? = when (name) {
        "onArtworkDelegateResolved" -> listenNowHooks.onArtworkDelegateResolved(
            delegate = arg(args, 0)!!,
            liveData = arg(args, 1),
            urls = arg(args, 2) as List<String>,
        )
        "logMetadataIdentity" -> ProviderLogger.diagnostic("${arg(args, 0)}: ${arg(args, 1)}")
        else -> null
    }
}
