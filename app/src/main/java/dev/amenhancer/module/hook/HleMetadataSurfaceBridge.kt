package dev.amenhancer.module.hook

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.*
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
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
    private lateinit var refreshQueue: AppleInAppMetadataRefreshQueue

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
        refreshQueue = AppleInAppMetadataRefreshQueue(
            postToMain = { callback -> runtime.mainHandler.post { callback() } },
            diagnostics = if (BuildConfig.DEBUG) {
                { stats ->
                    ProviderLogger.diagnostic(
                        "HLE metadata refresh frame: enqueued=${stats.enqueued}, " +
                            "merged=${stats.merged}, executed=${stats.executed}, " +
                            "failed=${stats.failed}, maxDepth=${stats.maxDepth}, " +
                            "durationMs=${stats.durationNanos / 1_000_000.0}",
                    )
                }
            } else {
                null
            },
        )
        val hosts = createHostAdapters()
        surfaceRuntime = AppleMetadataSurfaceRuntime(
            runtime = runtime,
            host = hosts.surface,
        )
        librarySurfaceHooks = AppleLibrarySurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            host = hosts.library,
            refreshQueue = refreshQueue,
        )
        dataBindingHooks = AppleDataBindingMetadataHooks(
            runtime = runtime,
            host = hosts.dataBinding,
            refreshQueue = refreshQueue,
        )
        collectionSurfaceHooks = AppleCollectionSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = hosts.collection,
            refreshQueue = refreshQueue,
        )
        artistSurfaceHooks = AppleArtistSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = hosts.artist,
            refreshQueue = refreshQueue,
        )
        mediaApiMetadataCoordinator = AppleMediaApiMetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            librarySurfaceHooks = librarySurfaceHooks,
            artistSurfaceHooks = artistSurfaceHooks,
            host = hosts.mediaApi,
            refreshQueue = refreshQueue,
        )
        resolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = hosts.resolution,
        )
        listenNowHooks = AppleListenNowHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = hosts.listenNow,
            refreshQueue = refreshQueue,
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
            host = hosts.visibleDiagnostics,
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
            host = hosts.playbackItem,
        )
        inAppArtworkContinuityHooks = AppleInAppArtworkContinuityHooks(
            runtime = runtime,
            host = hosts.artworkContinuity,
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

    private fun applyPlaybackMetadataOverrideFromHost(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
        artistOnly: Boolean,
        propagateArtistEntity: Boolean,
    ) {
        if (::metadataOverrideApplicationCoordinator.isInitialized) {
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = artistOnly,
                propagateArtistEntity = propagateArtistEntity,
            )
        } else {
            if (originalMetadata) {
                metadataStore.rememberOriginalMetadata(mediaId, alias, originalMetadataConfirmed)
            } else {
                metadataStore.rememberConfiguredMetadata(mediaId, alias)
            }
            frameworkHooks.refreshMediaSessionMetadata(mediaId, alias)
        }
    }

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

    /**
     * Concrete adapters for the HLE host interfaces.  These objects are built once during
     * installation and keep all compatibility/fail-open handling at the seam.  Hot callbacks
     * therefore use normal virtual dispatch instead of method-name/argument-array lookup on
     * every invocation.
     */
    private data class HostAdapters(
        val surface: AppleMetadataSurfaceHost,
        val library: AppleLibrarySurfaceHost,
        val dataBinding: AppleDataBindingMetadataHost,
        val collection: AppleCollectionSurfaceHost,
        val artist: AppleArtistSurfaceHost,
        val mediaApi: AppleMediaApiMetadataHost,
        val resolution: AppleInAppMetadataResolutionHost,
        val listenNow: AppleListenNowHost,
        val visibleDiagnostics: AppleVisibleMetadataDiagnosticsHost,
        val playbackItem: ApplePlaybackItemConversionHost,
        val artworkContinuity: AppleInAppArtworkContinuityHost,
    )

    private inline fun <T> hostCall(
        name: String,
        fallback: T,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (error: Throwable) {
            runCatching {
                ProviderLogger.debug("HLE typed host callback $name failed: ${error.message}")
            }
            fallback
        }
    }

    private fun createHostAdapters(): HostAdapters {
        val bridge = this
        return HostAdapters(
            surface = object : AppleMetadataSurfaceHost {
                override fun catalogResolver(): AppleInternalCatalogResolver? =
                    bridge.hostCall("surface.catalogResolver", null) { bridge.catalogResolver }

                override fun associatedArtistIds(mediaId: String): Collection<String> =
                    bridge.hostCall("surface.associatedArtistIds", emptyList()) {
                        bridge.metadataStore.associatedArtistIds(mediaId)
                    }

                override fun hasVisibleExactConsumer(mediaId: String): Boolean =
                    bridge.hostCall("surface.hasVisibleExactConsumer", false) {
                        bridge.dataBindingHooks.hasVisibleExactConsumer(mediaId)
                    }

                override fun hasGenericRecyclerConsumer(mediaId: String): Boolean =
                    bridge.hostCall("surface.hasGenericRecyclerConsumer", false) {
                        bridge.dataBindingHooks.hasGenericRecyclerRefs(mediaId)
                    }

                override fun detachController(owner: Any): Int =
                    bridge.hostCall("surface.detachController", 0) {
                        val removed = bridge.librarySurfaceHooks.detachController(owner)
                        bridge.collectionSurfaceHooks.clearController(owner)
                        bridge.artistSurfaceHooks.clearController(owner)
                        removed
                    }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("surface.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }

                override fun describeView(view: android.view.View): String =
                    bridge.hostCall("surface.describeView", "") { view.toString() }
            },
            library = object : AppleLibrarySurfaceHost {
                override fun contentItemMediaId(source: Any): String? =
                    bridge.hostCall("library.contentItemMediaId", null) {
                        bridge.contentItemHooks.mediaId(source)
                    }

                override fun primeLibrarySource(source: Any?) =
                    bridge.hostCall("library.primeLibrarySource", Unit) {
                        bridge.mediaApiMetadataCoordinator.primeLibrarySource(source)
                    }

                override fun mediaApiEntityAttributes(entity: Any): Any? =
                    bridge.hostCall("library.mediaApiEntityAttributes", null) {
                        bridge.mediaApiMetadataCoordinator.entityAttributes(entity)
                    }

                override fun mediaApiEntityCatalogId(
                    entity: Any,
                    knownAttributes: Any?,
                ): String? = bridge.hostCall("library.mediaApiEntityCatalogId", null) {
                    bridge.mediaApiMetadataCoordinator.entityCatalogId(entity, knownAttributes)
                }

                override fun mediaApiEntityLookupIds(
                    entity: Any,
                    knownAttributes: Any?,
                ): Set<String> = bridge.hostCall("library.mediaApiEntityLookupIds", emptySet()) {
                    bridge.mediaApiMetadataCoordinator.entityLookupIds(entity, knownAttributes)
                }

                override fun mergePlaybackAccountMetadata(
                    mediaId: String,
                    title: String?,
                    artist: String?,
                ) = bridge.hostCall("library.mergePlaybackAccountMetadata", Unit) {
                    bridge.metadataStore.mergeAccountMetadata(
                        mediaId,
                        AccountMetadata(title, artist),
                    )
                    Unit
                }

                override fun requestPriorityForMediaId(
                    mediaId: String,
                ): AppleInternalCatalogResolver.RequestPriority =
                    bridge.hostCall(
                        "library.requestPriorityForMediaId",
                        AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
                    ) { bridge.requestPriority(mediaId) }

                override fun enrichEntityAssociations(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    attributes: Any,
                    originalName: String?,
                    originalArtist: String?,
                    originalAlbum: String?,
                ) = bridge.hostCall("library.enrichEntityAssociations", Unit) {
                    bridge.mediaApiMetadataCoordinator.enrichLibraryEntityAssociations(
                        mediaId = mediaId,
                        entity = entity,
                        kind = kind,
                        attributes = attributes,
                        originalName = originalName,
                        originalArtist = originalArtist,
                        originalAlbum = originalAlbum,
                    )
                }

                override fun recordCurrentRecyclerMediaId(mediaId: String) =
                    bridge.hostCall("library.recordCurrentRecyclerMediaId", Unit) {
                        bridge.dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
                        Unit
                    }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("library.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun normalizeMediaIds(mediaIds: Collection<String>): List<String> =
                    bridge.hostCall("library.normalizeMediaIds", emptyList()) {
                        normalizedRecyclerBindingMediaIds(mediaIds).toList()
                    }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("library.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun applyAliasToMetadataRefs(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                ) = bridge.hostCall("library.applyAliasToMetadataRefs", Unit) {
                    bridge.metadataApplier.applyAliasToMetadataRefs(
                        mediaId = mediaId,
                        alias = alias,
                        forceRebind = true,
                        notifyModelChange = true,
                    )
                }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridge.hostCall("library.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority)
                }

                override fun isRefreshableMediaId(mediaId: String): Boolean =
                    bridge.hostCall("library.isRefreshableMediaId", false) {
                        bridge.surfaceRuntime.isRefreshable(mediaId)
                    }

                override fun nextMetadataTraceSequence(): Long =
                    bridge.hostCall("library.nextMetadataTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("library.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }

                override fun debugStackSummary(): String =
                    bridge.hostCall("library.debugStackSummary", "") {
                        bridge.visibleMetadataDiagnostics.stackSummary()
                    }

                override fun controllerBuildStrategy(
                    controller: Any,
                ): InAppLibraryControllerBuildStrategy =
                    bridge.hostCall(
                        "library.controllerBuildStrategy",
                        InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD,
                    ) {
                        inAppLibraryControllerBuildStrategy(
                            hasAlbumBuildData = bridge.collectionSurfaceHooks.hasAlbumBuildData(controller),
                            hasArtistBuildData = bridge.artistSurfaceHooks.hasBuildData(controller),
                            isPlaylistPageController = bridge.collectionSurfaceHooks.isPlaylistController(controller),
                        )
                    }

                override fun controllerAppliedAlias(
                    controller: Any,
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                ): AppliedMetadataAlias = bridge.hostCall(
                    "library.controllerAppliedAlias",
                    AppliedMetadataAlias(mediaId, alias),
                ) {
                    bridge.collectionSurfaceHooks.controllerAppliedAlias(controller, mediaId, alias)
                }

                override fun controllerAlbumTrackMediaIds(controller: Any): Collection<String> =
                    bridge.hostCall("library.controllerAlbumTrackMediaIds", emptyList()) {
                        bridge.collectionSurfaceHooks.albumTrackMediaIds(controller)
                    }

                override fun requestControllerBuild(
                    controller: Any,
                    strategy: InAppLibraryControllerBuildStrategy,
                ) = bridge.hostCall("library.requestControllerBuild", Unit) {
                    bridge.metadataApplier.requestLibraryControllerBuild(controller, strategy)
                }
            },
            dataBinding = object : AppleDataBindingMetadataHost {
                override fun contentItemMediaId(contentItem: Any): String? =
                    bridge.hostCall("dataBinding.contentItemMediaId", null) {
                        bridge.contentItemHooks.mediaId(contentItem)
                    }

                override fun bindingCandidateMediaId(value: Any): String? =
                    bridge.hostCall("dataBinding.bindingCandidateMediaId", null) {
                        bridge.registry.metadataId(value)
                            ?: bridge.registry.playbackItemId(value)
                            ?: bridge.librarySurfaceHooks.entityMediaId(value)
                            ?: bridge.librarySurfaceHooks.attributeBindingMediaId(value)
                    }

                override fun onBeginBindingModel(binding: Any) =
                    bridge.hostCall("dataBinding.onBeginBindingModel", Unit) {
                        bridge.artistSurfaceHooks.onBeginBindingModel(binding)
                    }

                override fun onBindingMediaIdChanged(
                    binding: Any,
                    previousMediaId: String?,
                    mediaId: String,
                ) = bridge.hostCall("dataBinding.onBindingMediaIdChanged", Unit) {
                    bridge.artistSurfaceHooks.onBindingMediaIdChanged(binding, mediaId)
                }

                override fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode =
                    bridge.hostCall(
                        "dataBinding.originalResolutionMode",
                        InAppOriginalResolutionMode.AFTER_LOCALIZED,
                    ) { bridge.artistSurfaceHooks.originalResolutionMode(binding) }

                override fun shouldInvalidateAppliedAlias(
                    binding: Any,
                    mediaId: String,
                    appliedAlias: AppliedMetadataAlias,
                    pendingAlias: AppliedMetadataAlias?,
                    renderedTexts: Collection<String>,
                ): Boolean = bridge.hostCall("dataBinding.shouldInvalidateAppliedAlias", false) {
                    val effective = bridge.alias(mediaId)
                    if (effective == null) {
                        false
                    } else {
                        val values = bridge.metadataApplier.dataBindingAliasValues(
                            mediaId = mediaId,
                            alias = effective,
                            binding = binding,
                        )
                        bridge.artistSurfaceHooks.shouldInvalidateAppliedAlias(
                            binding = binding,
                            mediaId = mediaId,
                            appliedAlias = appliedAlias,
                            pendingAlias = pendingAlias,
                            effectiveAlias = effective,
                            expectedTitle = values.title,
                            renderedTexts = renderedTexts,
                        )
                    }
                }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("dataBinding.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun aliasValues(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    binding: Any?,
                ): DataBindingAliasValues = bridge.hostCall(
                    "dataBinding.aliasValues",
                    DataBindingAliasValues(null, null),
                ) {
                    bridge.metadataApplier.dataBindingAliasValues(mediaId, alias, binding)
                }

                override fun isCurrentSurfaceMediaId(mediaId: String): Boolean =
                    bridge.hostCall("dataBinding.isCurrentSurfaceMediaId", false) {
                        mediaId == bridge.playbackCoordinator.currentMetadataId()
                    }

                override fun hasVisibleConsumer(mediaId: String): Boolean =
                    bridge.hostCall("dataBinding.hasVisibleConsumer", false) {
                        bridge.surfaceRuntime.hasVisibleConsumer(mediaId)
                    }

                override fun isRefreshableMediaId(mediaId: String): Boolean =
                    bridge.hostCall("dataBinding.isRefreshableMediaId", false) {
                        bridge.surfaceRuntime.isRefreshable(mediaId)
                    }

                override fun boundModelCandidates(mediaId: String): List<Any> =
                    bridge.hostCall("dataBinding.boundModelCandidates", emptyList()) {
                        bridge.registry.livePlaybackItems(mediaId) +
                            bridge.librarySurfaceHooks.liveEntities(mediaId)
                    }

                override fun enrichEntitiesForResolution(mediaIds: Collection<String>) =
                    bridge.hostCall("dataBinding.enrichEntitiesForResolution", Unit) {
                        bridge.mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("dataBinding.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("dataBinding.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority, originalResolutionMode)
                }

                override fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean = false

                override fun isQueueAdapter(adapter: Any): Boolean =
                    bridge.hostCall("dataBinding.isQueueAdapter", false) {
                        bridge.queueMetadataHooks.isQueueAdapter(adapter)
                    }

                override fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean =
                    bridge.hostCall("dataBinding.isArtistProfileRecyclerAdapter", false) {
                        bridge.artistSurfaceHooks.isRecyclerAdapter(adapter)
                    }

                override fun nextMetadataTraceSequence(): Long =
                    bridge.hostCall("dataBinding.nextMetadataTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }
            },
            collection = object : AppleCollectionSurfaceHost {
                override fun mediaApiEntityAttributes(entity: Any): Any? =
                    bridge.hostCall("collection.mediaApiEntityAttributes", null) {
                        bridge.mediaApiMetadataCoordinator.entityAttributes(entity)
                    }

                override fun mediaApiEntityCatalogId(
                    entity: Any,
                    knownAttributes: Any?,
                ): String? = bridge.hostCall("collection.mediaApiEntityCatalogId", null) {
                    bridge.mediaApiMetadataCoordinator.entityCatalogId(entity, knownAttributes)
                }

                override fun mediaApiAttribute(
                    attributes: Any,
                    attribute: AppleMediaApiTextAttribute,
                ): String? = bridge.hostCall("collection.mediaApiAttribute", null) {
                    bridge.mediaApiMetadataCoordinator.attribute(attributes, attribute)
                }

                override fun registerLibraryEntity(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    knownAttributes: Any?,
                    requestResolution: Boolean,
                    retainEntityRef: Boolean,
                ) = bridge.hostCall("collection.registerLibraryEntity", Unit) {
                    bridge.mediaApiMetadataCoordinator.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = kind,
                        knownAttributes = knownAttributes,
                        requestResolution = requestResolution,
                        retainEntityRef = retainEntityRef,
                    )
                }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("collection.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>) =
                    bridge.hostCall("collection.enrichLibraryEntitiesForResolution", Unit) {
                        bridge.mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("collection.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun applyAliasToMetadataRefs(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyModelChange: Boolean,
                ) = bridge.hostCall("collection.applyAliasToMetadataRefs", Unit) {
                    bridge.metadataApplier.applyAliasToMetadataRefs(
                        mediaId = mediaId,
                        alias = alias,
                        forceRebind = true,
                        notifyModelChange = notifyModelChange,
                    )
                }

                override fun shouldRequestOverride(mediaId: String): Boolean =
                    bridge.hostCall("collection.shouldRequestOverride", false) {
                        bridge.resolutionCoordinator.shouldRequestOverride(mediaId)
                    }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("collection.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority, originalResolutionMode)
                }

                override fun dataBindingAliasValues(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    binding: Any?,
                ): DataBindingAliasValues = bridge.hostCall(
                    "collection.dataBindingAliasValues",
                    DataBindingAliasValues(null, null),
                ) {
                    bridge.metadataApplier.dataBindingAliasValues(mediaId, alias, binding)
                }

                override fun sharedAssociatedArtistId(mediaId: String): String? =
                    bridge.hostCall("collection.sharedAssociatedArtistId", null) {
                        bridge.resolutionCoordinator.sharedAssociatedArtistId(mediaId)
                    }

                override fun onMetadataPageAttached(
                    owner: Any,
                    recycler: androidx.recyclerview.widget.RecyclerView,
                ) = bridge.hostCall("collection.onMetadataPageAttached", Unit) {
                    bridge.surfaceRuntime.onPageAttached(owner, recycler)
                }

                override fun onMetadataPageDetached(owner: Any) =
                    bridge.hostCall("collection.onMetadataPageDetached", Unit) {
                        bridge.surfaceRuntime.onPageDetached(owner)
                    }

                override fun handleArtistFinalBinding(
                    model: Any,
                    finalHolder: Any?,
                    position: Int?,
                ) = bridge.hostCall("collection.handleArtistFinalBinding", Unit) {
                    bridge.artistSurfaceHooks.handleFinalBinding(model, finalHolder, position)
                }

                override fun nextMetadataTraceSequence(): Long =
                    bridge.hostCall("collection.nextMetadataTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("collection.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }
            },
            artist = object : AppleArtistSurfaceHost {
                override fun mediaApiEntityAttributes(entity: Any): Any? =
                    bridge.hostCall("artist.mediaApiEntityAttributes", null) {
                        bridge.mediaApiMetadataCoordinator.entityAttributes(entity)
                    }

                override fun mediaApiEntityCatalogId(
                    entity: Any,
                    knownAttributes: Any?,
                ): String? = bridge.hostCall("artist.mediaApiEntityCatalogId", null) {
                    bridge.mediaApiMetadataCoordinator.entityCatalogId(entity, knownAttributes)
                }

                override fun mediaApiAttribute(
                    attributes: Any,
                    attribute: AppleMediaApiTextAttribute,
                ): String? = bridge.hostCall("artist.mediaApiAttribute", null) {
                    bridge.mediaApiMetadataCoordinator.attribute(attributes, attribute)
                }

                override fun mediaApiEntityRelationshipEntities(
                    entity: Any,
                    relationshipKey: String,
                ): Collection<Any> = bridge.hostCall("artist.mediaApiEntityRelationshipEntities", emptyList()) {
                    bridge.mediaApiMetadataCoordinator.relationshipEntities(entity, relationshipKey)
                }

                override fun registerLibraryEntity(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    knownAttributes: Any?,
                ) = bridge.hostCall("artist.registerLibraryEntity", Unit) {
                    bridge.mediaApiMetadataCoordinator.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = kind,
                        knownAttributes = knownAttributes,
                        requestResolution = false,
                        retainEntityRef = true,
                    )
                }

                override fun enrichLibraryEntity(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    attributes: Any,
                ) = bridge.hostCall("artist.enrichLibraryEntity", Unit) {
                    bridge.librarySurfaceHooks.enrichEntity(mediaId, entity, kind, attributes)
                }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("artist.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>) =
                    bridge.hostCall("artist.enrichLibraryEntitiesForResolution", Unit) {
                        bridge.mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("artist.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun applyAliasToMetadataRefs(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyModelChange: Boolean,
                ) = bridge.hostCall("artist.applyAliasToMetadataRefs", Unit) {
                    bridge.metadataApplier.applyAliasToMetadataRefs(
                        mediaId = mediaId,
                        alias = alias,
                        forceRebind = true,
                        notifyModelChange = notifyModelChange,
                    )
                }

                override fun shouldRequestOverride(mediaId: String): Boolean =
                    bridge.hostCall("artist.shouldRequestOverride", false) {
                        bridge.resolutionCoordinator.shouldRequestOverride(mediaId)
                    }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("artist.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority, originalResolutionMode)
                }

                override fun retryOriginalMetadata(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("artist.retryOriginalMetadata", Unit) {
                    bridge.resolutionCoordinator.retryOriginalMetadata(
                        mediaIds = mediaIds,
                        priority = priority,
                        originalResolutionMode = originalResolutionMode,
                    )
                }

                override fun activeMetadataPageOwner(): Any? =
                    bridge.hostCall("artist.activeMetadataPageOwner", null) {
                        bridge.surfaceRuntime.activePageOwner()
                    }

                override fun knownArtistProfileCredits(artistId: String): Set<String> =
                    bridge.hostCall("artist.knownArtistProfileCredits", emptySet()) {
                        bridge.mediaApiMetadataCoordinator.knownArtistProfileCredits(artistId)
                    }

                override fun onMetadataPageAttached(
                    owner: Any,
                    recycler: androidx.recyclerview.widget.RecyclerView,
                ) = bridge.hostCall("artist.onMetadataPageAttached", Unit) {
                    bridge.surfaceRuntime.onPageAttached(owner, recycler)
                }

                override fun onMetadataPageDetached(owner: Any) =
                    bridge.hostCall("artist.onMetadataPageDetached", Unit) {
                        bridge.surfaceRuntime.onPageDetached(owner)
                    }

                override fun nextMetadataTraceSequence(): Long =
                    bridge.hostCall("artist.nextMetadataTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("artist.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }
            },
            mediaApi = object : AppleMediaApiMetadataHost {
                override fun contentItemMediaId(contentItem: Any): String? =
                    bridge.hostCall("mediaApi.contentItemMediaId", null) {
                        bridge.contentItemHooks.mediaId(contentItem)
                    }

                override fun registerPlaybackItem(
                    mediaId: String,
                    playbackItem: Any,
                    notifyChange: Boolean,
                    analyzeMetadata: Boolean,
                ) = bridge.hostCall("mediaApi.registerPlaybackItem", Unit) {
                    bridge.metadataRegistrationCoordinator.registerPlaybackItem(
                        mediaId = mediaId,
                        playbackItem = playbackItem,
                        notifyChange = notifyChange,
                        analyzeMetadata = analyzeMetadata,
                    )
                }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("mediaApi.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyChange: Boolean,
                ) = bridge.hostCall("mediaApi.applyAliasToPlaybackItem", Unit) {
                    bridge.applyAliasToObject(playbackItem, alias, notifyChange)
                }

                override fun shouldShareOriginalSongLanguage(
                    localizedTitle: String?,
                    localizedArtist: String?,
                    alias: AppleInternalCatalogResolver.Alias?,
                ): Boolean = bridge.hostCall("mediaApi.shouldShareOriginalSongLanguage", false) {
                    bridge.resolutionCoordinator.shouldShareOriginalSongLanguage(
                        localizedTitle = localizedTitle,
                        localizedArtist = localizedArtist,
                        alias = alias,
                    )
                }

                override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) =
                    bridge.hostCall("mediaApi.rememberOriginalLanguageForArtist", Unit) {
                        bridge.resolutionCoordinator.rememberOriginalLanguageForArtist(mediaId, language)
                    }

                override fun hydrateSharedArtistOverrides(mediaId: String) =
                    bridge.hostCall("mediaApi.hydrateSharedArtistOverrides", Unit) {
                        bridge.resolutionCoordinator.hydrateSharedArtistOverrides(mediaId)
                    }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("mediaApi.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun applyAliasToMetadataRefs(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    forceRebind: Boolean,
                    notifyModelChange: Boolean,
                ) = bridge.hostCall("mediaApi.applyAliasToMetadataRefs", Unit) {
                    bridge.metadataApplier.applyAliasToMetadataRefs(
                        mediaId = mediaId,
                        alias = alias,
                        forceRebind = forceRebind,
                        notifyModelChange = notifyModelChange,
                    )
                }

                override fun shouldRequestOverride(mediaId: String): Boolean =
                    bridge.hostCall("mediaApi.shouldRequestOverride", false) {
                        bridge.resolutionCoordinator.shouldRequestOverride(mediaId)
                    }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("mediaApi.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority, originalResolutionMode)
                }

                override fun configuredContentUiLanguage(): Int = 0

                override fun nextTraceSequence(): Long =
                    bridge.hostCall("mediaApi.nextTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }
            },
            resolution = object : AppleInAppMetadataResolutionHost {
                override fun currentPlaybackMetadataId(): String? =
                    bridge.hostCall("resolution.currentPlaybackMetadataId", null) {
                        bridge.playbackCoordinator.currentMetadataId()
                    }

                override fun configuredContentUiLanguage(): Int = 0

                override fun shouldOverrideAccountLanguage(selection: Int): Boolean = false

                override fun isRestoreOriginalEnabled(): Boolean = true

                override fun refreshRequestScope() =
                    bridge.hostCall("resolution.refreshRequestScope", Unit) {
                        bridge.surfaceRuntime.refreshRequestScope()
                    }

                override fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>) =
                    bridge.hostCall("resolution.enrichLibraryEntitiesForResolution", Unit) {
                        bridge.mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                override fun applyAliasToMetadataRefs(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    forceRebind: Boolean,
                    notifyModelChange: Boolean,
                ) = bridge.hostCall("resolution.applyAliasToMetadataRefs", Unit) {
                    bridge.metadataApplier.applyAliasToMetadataRefs(
                        mediaId = mediaId,
                        alias = alias,
                        forceRebind = forceRebind,
                        notifyModelChange = notifyModelChange,
                    )
                }

                override fun applyPlaybackMetadataOverride(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    forceInAppRebind: Boolean,
                    rememberLocalizedArtist: Boolean,
                    originalMetadata: Boolean,
                    originalMetadataConfirmed: Boolean,
                    artistOnly: Boolean,
                    propagateArtistEntity: Boolean,
                ) = bridge.hostCall("resolution.applyPlaybackMetadataOverride", Unit) {
                    bridge.applyPlaybackMetadataOverrideFromHost(
                        mediaId = mediaId,
                        alias = alias,
                        forceInAppRebind = forceInAppRebind,
                        rememberLocalizedArtist = rememberLocalizedArtist,
                        originalMetadata = originalMetadata,
                        originalMetadataConfirmed = originalMetadataConfirmed,
                        artistOnly = artistOnly,
                        propagateArtistEntity = propagateArtistEntity,
                    )
                }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("resolution.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }

                override fun nextTraceSequence(): Long =
                    bridge.hostCall("resolution.nextTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }
            },
            listenNow = object : AppleListenNowHost {
                override fun mediaApiEntityAttributes(entity: Any): Any? =
                    bridge.hostCall("listenNow.mediaApiEntityAttributes", null) {
                        bridge.mediaApiMetadataCoordinator.entityAttributes(entity)
                    }

                override fun mediaApiEntityCatalogId(
                    entity: Any,
                    knownAttributes: Any?,
                ): String? = bridge.hostCall("listenNow.mediaApiEntityCatalogId", null) {
                    bridge.mediaApiMetadataCoordinator.entityCatalogId(entity, knownAttributes)
                }

                override fun registerLibraryEntity(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    knownAttributes: Any?,
                    requestResolution: Boolean,
                    retainEntityRef: Boolean,
                ) = bridge.hostCall("listenNow.registerLibraryEntity", Unit) {
                    bridge.librarySurfaceHooks.registerEntity(
                        mediaId,
                        entity,
                        kind,
                        knownAttributes,
                        requestResolution,
                        retainEntityRef,
                    )
                }

                override fun enrichLibraryEntity(
                    mediaId: String,
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    attributes: Any,
                ) = bridge.hostCall("listenNow.enrichLibraryEntity", Unit) {
                    bridge.librarySurfaceHooks.enrichEntity(mediaId, entity, kind, attributes)
                }

                override fun isRestoreOriginalMetadataEnabled(): Boolean = true

                override fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
                    bridge.hostCall("listenNow.shouldRetryOriginalMetadataCacheProbe", false) {
                        bridge.shouldRetryOriginalMetadataCacheProbe(mediaId)
                    }

                override fun rememberOriginalMetadataOverride(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    confirmed: Boolean,
                ) = bridge.hostCall("listenNow.rememberOriginalMetadataOverride", Unit) {
                    bridge.metadataStore.rememberOriginalMetadata(mediaId, alias, confirmed)
                }

                override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) =
                    bridge.hostCall("listenNow.rememberOriginalLanguageForArtist", Unit) {
                        bridge.resolutionCoordinator.rememberOriginalLanguageForArtist(mediaId, language)
                    }

                override fun resolveCachedOriginalEntityForInApp(
                    mediaId: String,
                    entityType: AppleInternalCatalogResolver.LocalizedEntityType,
                    preBind: Boolean,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridge.hostCall("listenNow.resolveCachedOriginalEntityForInApp", Unit) {
                    bridge.resolutionCoordinator.resolveCachedOriginalEntity(
                        mediaId,
                        entityType,
                        preBind,
                        priority,
                    )
                }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("listenNow.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun applyAliasToLibraryEntity(
                    entity: Any,
                    kind: InAppLibraryEntityKind,
                    alias: AppleInternalCatalogResolver.Alias,
                ): Boolean = bridge.hostCall("listenNow.applyAliasToLibraryEntity", false) {
                    bridge.librarySurfaceHooks.applyAliasToEntity(entity, kind, alias)
                }

                override fun shouldRequestOverride(mediaId: String): Boolean =
                    bridge.hostCall("listenNow.shouldRequestOverride", false) {
                        bridge.resolutionCoordinator.shouldRequestOverride(mediaId)
                    }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("listenNow.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun scheduleMetadataResolution(
                    mediaIds: Collection<String>,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                    originalResolutionMode: InAppOriginalResolutionMode,
                ) = bridge.hostCall("listenNow.scheduleMetadataResolution", Unit) {
                    bridge.resolutionCoordinator.schedule(mediaIds, priority, originalResolutionMode)
                }

                override fun nextMetadataTraceSequence(): Long =
                    bridge.hostCall("listenNow.nextMetadataTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("listenNow.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }

                override fun isDataBindingInstance(candidate: Any): Boolean =
                    bridge.hostCall("listenNow.isDataBindingInstance", false) {
                        bridge.dataBindingHooks.isBindingInstance(candidate)
                    }

                override fun dataBindingFromHolder(argument: Any?): Any? =
                    bridge.hostCall("listenNow.dataBindingFromHolder", null) {
                        bridge.dataBindingHooks.bindingFromHolder(argument)
                    }

                override fun beginDataBindingModelBind(binding: Any) =
                    bridge.hostCall("listenNow.beginDataBindingModelBind", Unit) {
                        bridge.dataBindingHooks.beginModelBind(binding)
                    }

                override fun clearDataBindingMediaId(binding: Any) =
                    bridge.hostCall("listenNow.clearDataBindingMediaId", Unit) {
                        bridge.dataBindingHooks.clearMediaId(binding)
                    }

                override fun dataBindingGeneration(binding: Any): Long =
                    bridge.hostCall("listenNow.dataBindingGeneration", 0L) {
                        bridge.dataBindingHooks.generation(binding)
                    }

                override fun captureDataBinding(binding: Any) =
                    bridge.hostCall("listenNow.captureDataBinding", Unit) {
                        bridge.dataBindingHooks.capture(binding)
                    }

                override fun registerDataBinding(mediaId: String, binding: Any) =
                    bridge.hostCall("listenNow.registerDataBinding", Unit) {
                        bridge.dataBindingHooks.register(mediaId, binding)
                    }

                override fun aliasValues(
                    mediaId: String,
                    alias: AppleInternalCatalogResolver.Alias,
                    binding: Any?,
                ): DataBindingAliasValues = bridge.hostCall(
                    "listenNow.aliasValues",
                    DataBindingAliasValues(null, null),
                ) {
                    bridge.metadataApplier.dataBindingAliasValues(mediaId, alias, binding)
                }

                override fun renderedTexts(binding: Any): List<String> =
                    bridge.hostCall("listenNow.renderedTexts", emptyList()) {
                        bridge.dataBindingHooks.renderedTexts(binding)
                    }

                override fun appliedAlias(binding: Any): AppliedMetadataAlias? =
                    bridge.hostCall("listenNow.appliedAlias", null) {
                        bridge.dataBindingHooks.appliedAlias(binding)
                    }

                override fun rememberAppliedAlias(binding: Any, alias: AppliedMetadataAlias) =
                    bridge.hostCall("listenNow.rememberAppliedAlias", Unit) {
                        bridge.dataBindingHooks.rememberAppliedAlias(binding, alias)
                    }

                override fun applyAliasVariables(
                    binding: Any,
                    values: DataBindingAliasValues,
                ): DataBindingVariableApplyResult = bridge.hostCall(
                    "listenNow.applyAliasVariables",
                    DataBindingVariableApplyResult(false, false),
                ) {
                    bridge.dataBindingHooks.applyAliasVariables(binding, values)
                }

                override fun invalidateDataBinding(binding: Any) =
                    bridge.hostCall("listenNow.invalidateDataBinding", Unit) {
                        bridge.dataBindingHooks.invalidate(binding)
                    }

                override fun executePendingDataBindings(binding: Any) =
                    bridge.hostCall("listenNow.executePendingDataBindings", Unit) {
                        bridge.dataBindingHooks.executePending(binding)
                    }
            },
            visibleDiagnostics = object : AppleVisibleMetadataDiagnosticsHost {
                override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                    bridge.hostCall(
                        "visibleDiagnostics.activePlaybackIdentity",
                        ActivePlaybackMediaIdentity(null, "ampp_hle", ""),
                    ) { bridge.currentIdentity() }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("visibleDiagnostics.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun activeMetadataValues(mediaId: String): Set<String> =
                    bridge.hostCall("visibleDiagnostics.activeMetadataValues", emptySet()) {
                        buildSet {
                            bridge.metadataStore.accountMetadata(mediaId)?.let {
                                addAll(listOfNotNull(it.title, it.artist))
                            }
                            bridge.alias(mediaId)?.let { value ->
                                addAll(listOf(value.title, value.artist, value.album))
                            }
                        }
                    }

                override fun nextTraceSequence(): Long =
                    bridge.hostCall("visibleDiagnostics.nextTraceSequence", 0L) {
                        bridge.traceSequence.incrementAndGet()
                    }
            },
            playbackItem = object : ApplePlaybackItemConversionHost {
                override fun containerKind(containerItem: Any): InAppContainerKind? =
                    bridge.hostCall("playbackItem.containerKind", null) {
                        bridge.metadataRegistrationCoordinator.containerKind(containerItem)
                    }

                override fun metadataId(metadata: Any, fallback: String?): String? =
                    bridge.hostCall("playbackItem.metadataId", null) {
                        bridge.media3MetadataCoordinator.mediaId(metadata, fallback, true)
                    }

                override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                    bridge.hostCall(
                        "playbackItem.activePlaybackIdentity",
                        ActivePlaybackMediaIdentity(null, "ampp_hle", ""),
                    ) { bridge.currentIdentity() }

                override fun metadataDetails(metadata: Any): String =
                    bridge.hostCall("playbackItem.metadataDetails", "") {
                        metadata.javaClass.name
                    }

                override fun logMetadataIdentity(
                    event: String,
                    identity: ActivePlaybackMediaIdentity,
                    details: String,
                ) = bridge.hostCall("playbackItem.logMetadataIdentity", Unit) {
                    ProviderLogger.diagnostic("$event: $details")
                }

                override fun markContainerNavigationItem(
                    containerItem: Any,
                    kind: InAppContainerKind,
                    mediaId: String,
                ) = bridge.hostCall("playbackItem.markContainerNavigationItem", Unit) {
                    bridge.metadataRegistrationCoordinator.markContainerNavigationItem(
                        containerItem,
                        kind,
                        mediaId,
                    )
                }

                override fun markMetadataVisible(mediaIds: Collection<String>) =
                    bridge.hostCall("playbackItem.markMetadataVisible", Unit) {
                        bridge.markVisible(mediaIds)
                    }

                override fun registerContainerItem(
                    mediaId: String,
                    containerItem: Any,
                    kind: InAppContainerKind,
                ) = bridge.hostCall("playbackItem.registerContainerItem", Unit) {
                    bridge.metadataRegistrationCoordinator.registerContainerItem(
                        mediaId,
                        containerItem,
                        kind,
                    )
                }

                override fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
                    bridge.hostCall("playbackItem.effectiveAlias", null) { bridge.alias(mediaId) }

                override fun applyAliasToContainerItem(
                    containerItem: Any,
                    kind: InAppContainerKind,
                    alias: AppleInternalCatalogResolver.Alias,
                ) = bridge.hostCall("playbackItem.applyAliasToContainerItem", Unit) {
                    bridge.metadataApplier.applyAliasToContainerItem(containerItem, kind, alias)
                }

                override fun contentItemMediaId(contentItem: Any): String? =
                    bridge.hostCall("playbackItem.contentItemMediaId", null) {
                        bridge.contentItemHooks.mediaId(contentItem)
                    }

                override fun registerPlaybackItem(mediaId: String, playbackItem: Any) =
                    bridge.hostCall("playbackItem.registerPlaybackItem", Unit) {
                        bridge.metadataRegistrationCoordinator.registerPlaybackItem(mediaId, playbackItem)
                    }

                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                ) = bridge.hostCall("playbackItem.applyAliasToPlaybackItem", Unit) {
                    bridge.applyAliasToObject(playbackItem, alias, true)
                }

                override fun shouldRequestOverride(mediaId: String): Boolean =
                    bridge.hostCall("playbackItem.shouldRequestOverride", false) {
                        bridge.resolutionCoordinator.shouldRequestOverride(mediaId)
                    }

                override fun ensureOverride(
                    mediaId: String,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridge.hostCall("playbackItem.ensureOverride", Unit) {
                    bridge.resolutionCoordinator.ensureOverride(mediaId, false, priority)
                }
            },
            artworkContinuity = object : AppleInAppArtworkContinuityHost {
                override fun onArtworkDelegateResolved(
                    delegate: Any,
                    liveData: Any?,
                    urls: List<String>,
                ) = bridge.hostCall("artworkContinuity.onArtworkDelegateResolved", Unit) {
                    bridge.listenNowHooks.onArtworkDelegateResolved(delegate, liveData, urls)
                }

                override fun logMetadataIdentity(event: String, details: String) =
                    bridge.hostCall("artworkContinuity.logMetadataIdentity", Unit) {
                        ProviderLogger.diagnostic("$event: $details")
                    }
            },
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

}
