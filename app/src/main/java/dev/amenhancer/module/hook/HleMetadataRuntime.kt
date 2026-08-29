package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TitleCorrectionMode
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataOverrideStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.PlaybackManager
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataCoordinator
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataCoordinatorHost
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemMetadataHost
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemGetter
import io.github.proify.lyricon.amprovider.xposed.AppleQueueMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleQueueMetadataHost
import io.github.proify.lyricon.amprovider.xposed.AppleActionSheetMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleActionSheetMetadataHost
import io.github.proify.lyricon.amprovider.xposed.InAppContainerKind
import io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy

/**
 * AM++ host for the transplanted HLE metadata subsystem.
 *
 * It intentionally wires only metadata concerns: HLE's lyric provider,
 * preference UI, remote player, and unrelated enhancement hooks are not
 * initialized. The original HLE resolver/cache/policy/hook profile path is
 * retained for Apple Music metadata.
 */
internal class HleMetadataRuntime(
    private val module: XposedModule,
    private val application: Application,
    private val classLoader: ClassLoader,
    private val mode: TitleCorrectionMode = TitleCorrectionMode.ORIGINAL_HYPER,
    private val stableMetadata: StablePlaybackMetadataCoordinator? = null,
) {
    private val version = runCatching {
        val info = application.packageManager.getPackageInfo(
            "com.apple.android.music",
            0,
        )
        AppleMusicVersion(
            versionName = info.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            },
        )
    }.getOrDefault(AppleMusicVersion(null, null))

    private val hookResolver = AppleMusicHookResolver(
        version = version,
        application = application,
        nativeLibraryDir = runCatching { module.getModuleApplicationInfo().nativeLibraryDir }
            .getOrDefault(""),
    )
    private val runtime = AppleMusicProviderRuntime(module, classLoader)
    private val playbackHooks = ApplePlaybackHooks()
    private val metadataStore = AppleMetadataOverrideStore()
    private val catalogResolver = AppleInternalCatalogResolver(
        context = application,
        classLoader = classLoader,
        hookResolver = hookResolver,
        mainHandler = runtime.mainHandler,
        cacheNamespace = mode.cacheNamespace,
    )
    private lateinit var contentLocalizationHooks: AppleContentLocalizationHooks
    private lateinit var frameworkHooks: AppleFrameworkMetadataHooks
    private lateinit var playbackCoordinator: ApplePlaybackMetadataCoordinator
    private lateinit var contentItemHooks: AppleContentItemMetadataHooks
    private lateinit var queueMetadataHooks: AppleQueueMetadataHooks
    private lateinit var actionSheetMetadataHooks: AppleActionSheetMetadataHooks
    private lateinit var surfaceBridge: HleMetadataSurfaceBridge
    private var bridgeEnsureOverride: (
        String,
        Boolean,
        AppleInternalCatalogResolver.RequestPriority,
    ) -> Unit = { _, _, _ -> }
    private var bridgeEnsureOverrides: (
        Collection<String>,
        Boolean,
        Int,
    ) -> Unit = { _, _, _ -> }
    private var bridgeRegisterMetadata: (
        String,
        Any,
        Boolean,
        Boolean,
        AppleInternalCatalogResolver.RequestPriority,
    ) -> Unit = { _, _, _, _, _ -> }
    private var bridgeRegisterPlaybackItem: (String, Any, Boolean, Boolean) -> Unit =
        { _, _, _, _ -> }
    private var bridgeApplyAliasToPlaybackItem: (Any, AppleInternalCatalogResolver.Alias, Boolean) -> Unit =
        { _, _, _ -> }
    private var bridgeMarkMetadataVisible: (Collection<String>) -> Unit = {}
    private var bridgeSetPlaybackMediaId: (String) -> Unit = {}
    private var bridgeRegisterContainerItem: (String, Any, InAppContainerKind) -> Unit =
        { _, _, _ -> }
    private var bridgeContainerNavigationBinding: (Any) -> io.github.proify.lyricon.amprovider.xposed.InAppContainerNavigationRef? =
        { null }
    private var bridgeRawContentItemValue: (Any, io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember) -> Any? =
        { _, _ -> null }
    private var bridgeKnownValues: (String, io.github.proify.lyricon.amprovider.xposed.VisibleTextField) -> Set<String> =
        { _, _ -> emptySet() }
    private var bridgeHasLivePlaybackItem: (String) -> Boolean = { false }
    private var bridgeEffectiveAlias: (String) -> AppleInternalCatalogResolver.Alias? = { mediaId ->
        metadataStore.originalMetadata(mediaId) ?: metadataStore.configuredMetadata(mediaId)
    }
    private var bridgeMedia3MetadataId: (Any, String?, Boolean) -> String? =
        { _, fallback, trustedFallback ->
            fallback?.takeIf { trustedFallback && it.isNotBlank() && it.all(Char::isDigit) }
        }
    private var bridgeMedia3MetadataDetails: (Any) -> String = { metadata -> metadata.javaClass.name }
    private var bridgeIsCurrentMetadataSurfaceMediaId: (String) -> Boolean = { mediaId ->
        ::playbackCoordinator.isInitialized && mediaId == playbackCoordinator.currentMetadataId()
    }

    fun install(): TargetCapabilityInstall {
        runtime.attach(application, hookResolver)
        MediaMetadataCache.setProfile(mode.cacheNamespace)
        PlaybackManager.setMetadataResolutionFinishedListener { mediaId ->
            val metadata = MediaMetadataCache.getMetadataById(mediaId)
                ?: return@setMetadataResolutionFinishedListener
            val alias = runCatching { effectiveAlias(mediaId) }.getOrNull()
            stableMetadata?.onResolutionFinished(
                appleMusicId = mediaId.toLongOrNull() ?: return@setMetadataResolutionFinishedListener,
                title = alias?.title ?: metadata.originalTitle ?: metadata.title,
                artist = alias?.artist ?: metadata.originalArtist ?: metadata.artist,
                album = alias?.album ?: metadata.originalAlbum,
                durationMs = metadata.duration,
            )
        }
        catalogResolver.applyContentUiLanguage(mode.contentUiLanguageSelection)
        catalogResolver.setPersistentLocalizedCacheEnabled(true)

        contentLocalizationHooks = AppleContentLocalizationHooks(
            runtime = runtime,
            catalogResolver = { catalogResolver },
        )
        runCatching { contentLocalizationHooks.installMediaApiLocalization() }
            .onFailure { ProviderLogger.error("HLE MediaApi localization hook failed", it) }
        runCatching { contentLocalizationHooks.installContentHttpLocalization() }
            .onFailure { ProviderLogger.error("HLE content HTTP localization hook failed", it) }

        frameworkHooks = AppleFrameworkMetadataHooks(
            runtime = runtime,
            preferences = { null },
            metadataStore = metadataStore,
            effectiveMetadataAlias = ::effectiveAlias,
            activePlaybackIdentity = { this@HleMetadataRuntime.activePlaybackIdentity() },
            logMetadataIdentity = { event, _, details ->
                ProviderLogger.diagnostic("$event: $details")
            },
        )

        playbackCoordinator = ApplePlaybackMetadataCoordinator(
            hookResolver = hookResolver,
            catalogResolver = catalogResolver,
            metadataStore = metadataStore,
            host = playbackHost(),
        )

        frameworkHooks.installMediaSessionMetadata()
        frameworkHooks.installMediaSessionQueue()
        frameworkHooks.installPlaybackNotificationMetadata()
        ApplePlaybackMetadataHooks(
            runtime = runtime,
            playbackHooks = { playbackHooks },
            metadataCoordinator = playbackCoordinator,
        ).installHooks()

        contentItemHooks = AppleContentItemMetadataHooks(
            runtime = runtime,
            host = object : AppleContentItemMetadataHost {
                override fun containerNavigationBinding(contentItem: Any) =
                    bridgeContainerNavigationBinding(contentItem)
                override fun effectiveAlias(
                    mediaId: String,
                ): AppleInternalCatalogResolver.Alias? =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun registerContainerItem(
                    mediaId: String,
                    contentItem: Any,
                    kind: InAppContainerKind,
                ) = bridgeRegisterContainerItem(mediaId, contentItem, kind)
                override fun localizedEntityType(contentItem: Any) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.contentItemLocalizedEntityType(contentItem)
                    } else {
                        AppleInternalCatalogResolver.LocalizedEntityType.SONG
                    }
                override fun recordComposeMediaId(mediaId: String) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordComposeMediaId(mediaId)
                    }
                    bridgeMarkMetadataVisible(listOf(mediaId))
                }
                override fun recordCurrentRecyclerMediaId(mediaId: String) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordCurrentRecyclerMediaId(mediaId)
                    }
                }
                override fun requestPriority(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.requestPriority(mediaId)
                    } else {
                        AppleInternalCatalogResolver.RequestPriority.VISIBLE
                    }
                override fun shouldResolveFromGetter(
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = io.github.proify.lyricon.amprovider.xposed.shouldResolveMetadataFromGetter(priority)
                override fun registerPlaybackItem(
                    mediaId: String,
                    playbackItem: Any,
                    notifyChange: Boolean,
                    analyzeMetadata: Boolean,
                ) = bridgeRegisterPlaybackItem(mediaId, playbackItem, notifyChange, analyzeMetadata)
                override fun shouldRequestOverride(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.shouldRequestOverride(mediaId)
                    } else {
                        metadataStore.originalMetadata(mediaId) == null
                    }
                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyChange: Boolean,
                ) = bridgeApplyAliasToPlaybackItem(playbackItem, alias, notifyChange)
                override fun metadataOverride(
                    entityType: AppleInternalCatalogResolver.LocalizedEntityType,
                    getter: AppleContentItemGetter,
                    alias: AppleInternalCatalogResolver.Alias,
                    original: String?,
                ): String? = io.github.proify.lyricon.amprovider.xposed.contentItemMetadataOverride(
                    entityType = entityType,
                    getter = getter,
                    alias = alias,
                    original = original,
                )
            },
        )
        contentItemHooks.installHooks()

        queueMetadataHooks = AppleQueueMetadataHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            host = object : AppleQueueMetadataHost {
                override fun activePlaybackIdentity() =
                    this@HleMetadataRuntime.activePlaybackIdentity()
                override fun logMetadataIdentity(
                    event: String,
                    identity: io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity,
                    details: String,
                ) = ProviderLogger.diagnostic("$event: $details")
                override fun media3MetadataId(
                    metadata: Any,
                    fallback: String?,
                    trustedFallback: Boolean,
                ): String? = bridgeMedia3MetadataId(metadata, fallback, trustedFallback)
                override fun media3MetadataDetails(metadata: Any): String =
                    bridgeMedia3MetadataDetails(metadata)
                override fun registerMetadata(
                    mediaId: String,
                    metadata: Any,
                    requestResolution: Boolean,
                    preBind: Boolean,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeRegisterMetadata(
                    mediaId,
                    metadata,
                    requestResolution,
                    preBind,
                    priority,
                )
                override fun markPlaybackItemHistory(playbackItem: Any) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.markPlaybackItemHistory(playbackItem)
                    }
                }
                override fun registerPlaybackItem(
                    mediaId: String,
                    playbackItem: Any,
                    notifyChange: Boolean,
                    analyzeMetadata: Boolean,
                ) = bridgeRegisterPlaybackItem(mediaId, playbackItem, notifyChange, analyzeMetadata)
                override fun contentItemMediaId(contentItem: Any, refresh: Boolean): String? =
                    contentItemHooks.mediaId(contentItem, refresh)
                override fun effectiveAlias(mediaId: String) =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyChange: Boolean,
                ) = bridgeApplyAliasToPlaybackItem(playbackItem, alias, notifyChange)
                override fun shouldRequestOverride(mediaId: String) = true
                override fun ensureOverride(
                    mediaId: String,
                    preBind: Boolean,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeEnsureOverride(mediaId, preBind, priority)
                override fun ensureOverrides(
                    mediaIds: Collection<String>,
                    preBind: Boolean,
                    originalResolutionLimit: Int,
                ) = bridgeEnsureOverrides(mediaIds, preBind, originalResolutionLimit)
                override fun readPlaybackItemValue(
                    playbackItem: Any,
                    field: io.github.proify.lyricon.amprovider.xposed.InAppPlaybackItemField,
                    contract: io.github.proify.lyricon.amprovider.xposed.InAppPlaybackItemContract,
                ): String? = null
                override fun markMetadataVisible(mediaIds: Collection<String>) = bridgeMarkMetadataVisible(mediaIds)
                override fun isCurrentMetadataSurfaceMediaId(mediaId: String) =
                    bridgeIsCurrentMetadataSurfaceMediaId(mediaId)
                override fun hasLivePlaybackItem(mediaId: String) = bridgeHasLivePlaybackItem(mediaId)
            },
        )
        runCatching { queueMetadataHooks.installHooks() }
            .onFailure { ProviderLogger.info("HLE queue metadata hooks unavailable: ${it.message}") }

        actionSheetMetadataHooks = AppleActionSheetMetadataHooks(
            runtime = runtime,
            host = object : AppleActionSheetMetadataHost {
                override fun activePlaybackIdentity() =
                    this@HleMetadataRuntime.activePlaybackIdentity()
                override fun markMetadataVisible(mediaIds: Collection<String>) = bridgeMarkMetadataVisible(mediaIds)
                override fun rawContentItemValue(
                    contentItem: Any,
                    runtimeMember: io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember,
                ): Any? = bridgeRawContentItemValue(contentItem, runtimeMember)
                override fun recordArtistAssociation(mediaId: String, item: Any, rawTitle: String?) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordArtistAssociation(mediaId, item, rawTitle)
                    }
                }
                override fun effectiveAlias(mediaId: String) =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun knownValues(
                    mediaId: String,
                    field: io.github.proify.lyricon.amprovider.xposed.VisibleTextField,
                ): Set<String> = bridgeKnownValues(mediaId, field)
                override fun shouldRequestOverride(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.shouldRequestOverride(mediaId)
                    } else {
                        metadataStore.originalMetadata(mediaId) == null
                    }
                override fun ensureOverride(
                    mediaId: String,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeEnsureOverride(mediaId, false, priority)
                override fun localizedText(
                    field: io.github.proify.lyricon.amprovider.xposed.VisibleTextField,
                    alias: AppleInternalCatalogResolver.Alias,
                ): String = when (field) {
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.TITLE -> alias.title
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.ARTIST -> alias.artist
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.ALBUM -> alias.album
                }
                override fun logMetadataIdentity(
                    event: String,
                    identity: io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity?,
                    details: String,
                ) = ProviderLogger.diagnostic("$event: $details")
            },
        )
        runCatching { actionSheetMetadataHooks.installHooks() }
            .onFailure { ProviderLogger.info("HLE action-sheet metadata hooks unavailable: ${it.message}") }

        // The generic adapter is intentionally not used for the specialized
        // HLE surface points: installing both would double-hook the same
        // profiled methods and let a conservative string hook overwrite the
        // identity-aware HLE result. The bridge below installs HLE's complete
        // Listen Now/library/data-binding/artist/collection surface instead.
        surfaceBridge = HleMetadataSurfaceBridge(
            runtime = runtime,
            catalogResolver = catalogResolver,
            metadataStore = metadataStore,
            playbackCoordinator = playbackCoordinator,
            playbackHooks = playbackHooks,
            frameworkHooks = frameworkHooks,
            contentItemHooks = contentItemHooks,
            queueMetadataHooks = queueMetadataHooks,
            actionSheetMetadataHooks = actionSheetMetadataHooks,
            configuredContentUiLanguage = mode.contentUiLanguageSelection,
            restoreOriginalMetadata = mode == TitleCorrectionMode.ORIGINAL_HYPER,
            profileId = mode.cacheNamespace,
        )
        surfaceBridge.install()
        bridgeEnsureOverride = surfaceBridge::ensureOverride
        bridgeEnsureOverrides = surfaceBridge::ensureOverrides
        bridgeRegisterMetadata = surfaceBridge::registerMetadata
        bridgeRegisterPlaybackItem = surfaceBridge::registerPlaybackItem
        bridgeApplyAliasToPlaybackItem = surfaceBridge::applyAliasToPlaybackItem
        bridgeMarkMetadataVisible = surfaceBridge::markMetadataVisible
        bridgeSetPlaybackMediaId = surfaceBridge::setPlaybackMediaId
        bridgeRegisterContainerItem = surfaceBridge::registerContainerItem
        bridgeContainerNavigationBinding = surfaceBridge::containerNavigationBinding
        bridgeRawContentItemValue = surfaceBridge::rawContentItemValue
        bridgeKnownValues = surfaceBridge::knownValues
        bridgeHasLivePlaybackItem = surfaceBridge::hasLivePlaybackItem
        bridgeEffectiveAlias = surfaceBridge::effectiveAlias
        bridgeMedia3MetadataId = surfaceBridge::media3MetadataId
        bridgeMedia3MetadataDetails = surfaceBridge::media3MetadataDetails
        bridgeIsCurrentMetadataSurfaceMediaId = surfaceBridge::isCurrentMetadataSurfaceMediaId

        return TargetCapabilityInstall.Active(
            "HLE metadata runtime installed for ${version.displayName}; " +
                "original metadata + persistent SQLite cache enabled",
        )
    }

    private fun activePlaybackIdentity(): io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity =
        if (::surfaceBridge.isInitialized) {
            surfaceBridge.activePlaybackIdentity()
        } else {
            val mediaId = if (::playbackCoordinator.isInitialized) {
                playbackCoordinator.currentMetadataId()
            } else {
                null
            }
            io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity(
                mediaId = mediaId,
                source = "ampp_hle",
                candidates = mediaId.orEmpty(),
            )
        }

    private fun playbackHost() = object : ApplePlaybackMetadataCoordinatorHost {
        override fun activePlayer(): Any? = playbackHooks.activePlayer()
        override fun configuredContentUiLanguage(): Int = mode.contentUiLanguageSelection
        override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
            mode.catalogLanguage != null
        override fun shouldRestoreCjkOriginalMetadata(metadata: MediaMetadataCache.Metadata): Boolean =
            mode == TitleCorrectionMode.ORIGINAL_HYPER &&
                AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
                mediaId = metadata.id,
                title = metadata.title,
                artist = metadata.artist,
                genre = metadata.genre,
            )
        override fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) {
            if (::contentItemHooks.isInitialized) contentItemHooks.ensureHooks(contentItemClass)
        }
        override fun setMetadataPlaybackMediaId(mediaId: String) =
            bridgeSetPlaybackMediaId(mediaId)
        override fun onCurrentPlaybackItem(mediaId: String, playbackItem: Any, queueId: Long) =
            bridgeRegisterPlaybackItem(mediaId, playbackItem, false, true)
        override fun effectiveMetadataAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
            effectiveAlias(mediaId)
        override fun applyPlaybackMetadataOverride(
            mediaId: String,
            alias: AppleInternalCatalogResolver.Alias,
            rememberLocalizedArtist: Boolean,
            originalMetadata: Boolean,
            originalMetadataConfirmed: Boolean,
        ) {
            if (::surfaceBridge.isInitialized) {
                surfaceBridge.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = true,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
            } else if (originalMetadata && mode != TitleCorrectionMode.ORIGINAL_HYPER) {
                return
            } else if (originalMetadata) {
                metadataStore.rememberOriginalMetadata(mediaId, alias, originalMetadataConfirmed)
            } else {
                metadataStore.rememberConfiguredMetadata(mediaId, alias)
            }
            if (!::surfaceBridge.isInitialized) {
                frameworkHooks.refreshMediaSessionMetadata(mediaId, alias)
            }
        }
        override fun logMetadataIdentity(event: String, details: String) =
            ProviderLogger.diagnostic("$event: $details")
        override fun validatedOriginalSongAlias(
            alias: AppleInternalCatalogResolver.Alias?,
            localizedTitle: String?,
            localizedArtist: String?,
        ): AppleInternalCatalogResolver.Alias? =
            io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias(
                alias = alias,
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
            )
        override fun shouldShareOriginalSongLanguage(
            localizedTitle: String?,
            localizedArtist: String?,
            alias: AppleInternalCatalogResolver.Alias?,
        ): Boolean = if (::surfaceBridge.isInitialized) {
            surfaceBridge.shouldShareOriginalSongLanguage(
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
                alias = alias,
            )
        } else {
            false
        }
        override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
            if (::surfaceBridge.isInitialized) {
                surfaceBridge.rememberOriginalLanguageForArtist(mediaId, language)
            }
        }
        override fun isRestoreOriginalMetadataEnabled(): Boolean =
            mode == TitleCorrectionMode.ORIGINAL_HYPER
    }

    private fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
        bridgeEffectiveAlias(mediaId)
}
