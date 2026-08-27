package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HleMetadataIntegrationStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing $relative")

    @Test
    fun `title switch installs HLE metadata runtime and optional catalog language target`() {
        val feature = source("app/src/main/java/dev/amenhancer/module/hook/TitleCorrectionFeature.kt")
        val installation = source("app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt")
        assertTrue(feature.contains("hleMetadata.install()"))
        assertFalse(installation.contains("LibraryRefreshFeature()"))
        assertTrue(installation.contains("CatalogLanguageFeature()"))
        assertTrue(
            installation.indexOf("CatalogLanguageFeature()") <
                installation.indexOf("TitleCorrectionFeature()"),
        )
    }

    @Test
    fun `surface bridge keeps the original HLE metadata hook families live`() {
        val bridge = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        val playback = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/ApplePlaybackMetadataHooks.kt",
        )
        listOf(
            "AppleListenNowHooks",
            "AppleLibrarySurfaceHooks",
            "AppleDataBindingMetadataHooks",
            "AppleCollectionSurfaceHooks",
            "AppleArtistSurfaceHooks",
            "AppleInAppArtworkContinuityHooks",
            "ApplePlaybackItemConversionHooks",
            "AppleMetadataSurfaceRuntime",
        ).forEach { module ->
            assertTrue("missing HLE module $module", bridge.contains(module))
        }
        assertTrue(playback.contains("attachActivePlayer(mediaPlayer)"))
    }

    @Test
    fun `playback host delegates alias validation to HLE policy without recursion`() {
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        assertTrue(
            runtime.contains(
                "io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias(",
            ),
        )
        assertFalse(
            runtime.contains("validatedOriginalSongAlias(alias, localizedTitle, localizedArtist)"),
        )
    }

    @Test
    fun `new metadata lookups install HLE storefront and language request rewriting`() {
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        assertTrue(runtime.contains("contentLocalizationHooks.installMediaApiLocalization()"))
        assertTrue(runtime.contains("contentLocalizationHooks.installContentHttpLocalization()"))
        assertTrue(
            source(
                "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt",
            ).contains("Accept-Language"),
        )
    }

    @Test
    fun `library and album refresh paths delegate to HLE stateful hosts`() {
        val bridge = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        assertTrue(bridge.contains("collectionSurfaceHooks.albumTrackMediaIds"))
        assertTrue(bridge.contains("metadataApplier.requestLibraryControllerBuild"))
        assertTrue(bridge.contains("collectionSurfaceHooks.controllerAppliedAlias"))
        assertTrue(bridge.contains("registry.livePlaybackItems"))
        assertTrue(bridge.contains("librarySurfaceHooks.liveEntities"))
        assertTrue(bridge.contains("artistSurfaceHooks.shouldInvalidateAppliedAlias"))
        assertTrue(bridge.contains("dataBindingHooks.recordCurrentRecyclerMediaId"))
        assertTrue(runtime.contains("contentItemMetadataOverride("))
        assertTrue(runtime.contains("surfaceBridge.recordComposeMediaId(mediaId)"))
        assertTrue(runtime.contains("surfaceBridge.recordCurrentRecyclerMediaId(mediaId)"))
        assertFalse(bridge.contains("\"controllerAlbumTrackMediaIds\" -> emptyList"))
        assertFalse(bridge.contains("\"requestControllerBuild\" -> false"))
    }

    @Test
    fun `typed host adapters preserve HLE callback contracts`() {
        val bridge = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        assertTrue(bridge.contains("createHostAdapters()"))
        assertTrue(bridge.contains("private inline fun <T> hostCall"))
        listOf(
            "AppleMetadataSurfaceHost",
            "AppleLibrarySurfaceHost",
            "AppleDataBindingMetadataHost",
            "AppleCollectionSurfaceHost",
            "AppleArtistSurfaceHost",
            "AppleMediaApiMetadataHost",
            "AppleInAppMetadataResolutionHost",
            "AppleListenNowHost",
            "AppleVisibleMetadataDiagnosticsHost",
            "ApplePlaybackItemConversionHost",
            "AppleInAppArtworkContinuityHost",
        ).forEach { host ->
            assertTrue("missing typed host $host", bridge.contains("$host"))
        }
        assertFalse(bridge.contains("Proxy.newProxyInstance"))
        assertFalse(bridge.contains("Array<out Any?>"))
        assertFalse(bridge.contains("surfaceValue"))
        assertFalse(bridge.contains("dataBindingValue"))
        assertTrue(bridge.contains("mediaApiMetadataCoordinator.registerLibraryEntity"))
        assertTrue(bridge.contains("requestResolution = false"))
        assertTrue(bridge.contains("retainEntityRef = true"))
    }

    @Test
    fun `queue host delegates media3 identity and surface scope to HLE runtime`() {
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        assertFalse(runtime.contains("metadata as? android.media.MediaMetadata"))
        assertTrue(runtime.contains("bridgeMedia3MetadataId(metadata, fallback, trustedFallback)"))
        assertTrue(runtime.contains("bridgeMedia3MetadataDetails(metadata)"))
        assertTrue(runtime.contains("bridgeIsCurrentMetadataSurfaceMediaId(mediaId)"))
        assertTrue(runtime.contains("bridgeMedia3MetadataId = surfaceBridge::media3MetadataId"))
        assertTrue(runtime.contains("bridgeMedia3MetadataDetails = surfaceBridge::media3MetadataDetails"))
        assertTrue(
            runtime.contains(
                "bridgeIsCurrentMetadataSurfaceMediaId = surfaceBridge::isCurrentMetadataSurfaceMediaId",
            ),
        )
        assertTrue(bridge.contains("fun media3MetadataId("))
        assertTrue(bridge.contains("media3MetadataCoordinator.mediaId("))
        assertTrue(bridge.contains("fun media3MetadataDetails("))
        assertTrue(bridge.contains("media3MetadataCoordinator.details(metadata)"))
        assertTrue(bridge.contains("fun isCurrentMetadataSurfaceMediaId("))
        assertTrue(bridge.contains("surfaceRuntime.isCurrentMediaId(mediaId)"))
    }

    @Test
    fun `all HLE hosts use merged aliases and preserve original album candidates`() {
        val runtime = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("app/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        assertTrue(runtime.contains("bridgeEffectiveAlias(mediaId)"))
        assertTrue(runtime.contains("bridgeEffectiveAlias = surfaceBridge::effectiveAlias"))
        assertTrue(bridge.contains("fun effectiveAlias(mediaId: String)"))
        assertTrue(bridge.contains("resolutionCoordinator.effectiveAlias(mediaId)"))
        assertTrue(bridge.contains("VisibleTextField.ALBUM"))
        assertTrue(bridge.contains("registry.livePlaybackItemRefs(mediaId)"))
        assertTrue(bridge.contains("originalCollectionName"))
    }

    @Test
    fun `visible refresh paths share one frame queue while playback stays immediate`() {
        val queue = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataRefreshQueue.kt",
        )
        assertTrue(queue.contains("MetadataFrameScheduler"))
        assertTrue(queue.contains("VISIBLE_RESOLUTION"))
        assertTrue(queue.contains("higherPriority"))
        assertTrue(queue.contains("higherResolutionMode"))
        assertTrue(queue.contains("frameScheduler.postFrame"))
        val binding = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleDataBindingMetadataHooks.kt",
        )
        val library = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleLibrarySurfaceHooks.kt",
        )
        val listenNow = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleListenNowHooks.kt",
        )
        assertTrue(binding.contains("AppleMetadataRefreshKind.DATA_BINDING_REBIND"))
        assertTrue(binding.contains("AppleMetadataRefreshKind.GENERIC_RECYCLER_NOTIFY"))
        assertTrue(library.contains("AppleMetadataRefreshKind.LIBRARY_CONTROLLER_REBIND"))
        assertTrue(library.contains("AppleMetadataRefreshKind.LIBRARY_COMPOSE_REBIND"))
        assertTrue(listenNow.contains("AppleMetadataRefreshKind.LISTEN_NOW_REBIND"))
        val applier = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataApplier.kt",
        )
        assertTrue(applier.contains("runtime.mainHandler.post"))
    }

    @Test
    fun `listen now release artwork lookup avoids diagnostics cache scans`() {
        val listenNow = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleListenNowHooks.kt",
        )
        assertTrue(
            listenNow.contains(
                "val cachedArtwork = synchronized(inAppListenNowArtworkContinuityCache)",
            ),
        )
        assertTrue(listenNow.contains("if (BuildConfig.DEBUG) {\n                        val cacheDiagnostics"))
        assertFalse(listenNow.contains("InAppListenNowArtworkCacheProbe"))
    }

    @Test
    fun `generic profile top songs use the direct relationship and h1 binding seam`() {
        val artist = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )
        val coordinator = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleMediaApiMetadataCoordinator.kt",
        )
        assertTrue(artist.contains("method.name == \"populateViews\""))
        assertTrue(artist.contains("registerGenericProfileRelationship(controller, relationship)"))
        assertTrue(artist.contains("genericProfileTopSongTexts[controller] = texts"))
        assertTrue(artist.contains("resolveGenericTopSongSnapshot(model)"))
        assertTrue(artist.contains("ARTIST_TOP_SONG_TITLE_FIELD"))
        assertTrue(artist.contains("ARTIST_TOP_SONG_SUBTITLE_FIELD"))
        assertTrue(coordinator.contains("getViews"))
        assertTrue(coordinator.contains("field(entity, \"views\")"))
        assertFalse(artist.contains("[DEBUG-ARTIST-PREFETCH]"))
    }

    @Test
    fun `settings expose target language without restoring refresh action`() {
        val standalone = source("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val embedded = source("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        assertTrue(standalone.contains("歌曲名显示修正"))
        assertTrue(embedded.contains("歌曲名显示修正"))
        assertTrue(standalone.contains("目标语言"))
        assertTrue(embedded.contains("目标语言"))
        assertFalse(standalone.contains("刷新资料库"))
        assertFalse(embedded.contains("刷新资料库"))
    }

    @Test
    fun `schema owns optional target language and HLE token requests bypass it`() {
        val schema = source("app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val target = source(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogLanguageTarget.kt",
        )
        assertTrue(schema.contains("KEY_TITLE_CORRECTION_TARGET_LANGUAGE"))
        assertTrue(target.contains("isHleResolverRequest"))
        assertTrue(target.contains("CATALOG_REQUEST_TOKEN_PARAM"))
    }
}
