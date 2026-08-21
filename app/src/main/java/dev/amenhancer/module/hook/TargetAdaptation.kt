package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import java.util.concurrent.atomic.AtomicReference

/**
 * The complete target-specific seam used by feature hooks.
 *
 * Each feature receives only its own capability, while symbol discovery and
 * reflective hook installation remain private to the Apple Music adapters.
 */
internal data class TargetAdaptation(
    val identity: String,
    val currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
    val dualPane: DualPaneTarget,
    val editorialVideo: EditorialVideoTarget,
    val bidirectionalLyricBlur: BidirectionalLyricBlurTarget,
    val cjkKaraokeAnimation: CjkKaraokeAnimationTarget = CjkKaraokeAnimationTarget {
        TargetCapabilityInstall.Degraded("CJK karaoke animation target was not configured")
    },
    val lyricsTypeface: LyricsTypefaceTarget = LyricsTypefaceTarget {
        TargetCapabilityInstall.Degraded("Lyrics typeface target was not configured")
    },
    val customLyrics: CustomLyricsTarget = CustomLyricsTarget {
        TargetCapabilityInstall.Degraded("Custom lyrics target was not configured")
    },
    val currentSongIdentity: CurrentSongIdentityTarget = CurrentSongIdentityTarget {
        TargetCapabilityInstall.Degraded("Current song identity target was not configured")
    },
    val titleCorrection: TitleCorrectionTarget = TitleCorrectionTarget {
        TargetCapabilityInstall.Degraded("Title correction target was not configured")
    },
    val catalogLanguage: CatalogLanguageTarget = CatalogLanguageTarget {
        TargetCapabilityInstall.Degraded("Catalog language target was not configured")
    },
    val libraryRefresh: LibraryRefreshTarget = LibraryRefreshTarget {
        TargetCapabilityInstall.Degraded("Library refresh target was not configured")
    },
) {
    companion object {
        fun appleMusic(
            config: TargetConfigClient,
            application: Application,
            classLoader: ClassLoader,
            lyricsTypefaceSession: LyricsTypefaceSession,
            currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
            registerCurrentSongResponder: Boolean = true,
            useLibraryRefreshPermission: Boolean = true,
        ): TargetAdaptation {
            val build = targetBuild(application)
            val resolver = IndexedTargetSymbolResolver(
                build = build,
                source = ApkTargetClassSource(application, classLoader),
            )
            val settings = config.settings()
            val catalogLookup = settings.titleCorrectionEnabled
                .takeIf { it }
                ?.let { AppleMusicCatalogEntityLookup(resolver, classLoader) }
            val missCoordinator = AtomicReference<CatalogMissBackfillCoordinator?>()
            // The title feature and manual catalog refresh must observe one
            // cache, but its preference scan is deferred until the first title
            // lookup or an explicit refresh (never Application.onCreate).
            val titleCacheProvider = settings.titleCorrectionEnabled
                .takeIf { it }
                ?.let {
                    CatalogTitleCacheProvider {
                        CatalogTitleCache(
                            application,
                            settings.titleCorrectionTargetLanguage,
                            CatalogTitleMissListener { id -> missCoordinator.get()?.enqueue(id) },
                            observationScheduler = DefaultCatalogObservationScheduler,
                        )
                    }
                }
            if (titleCacheProvider != null && catalogLookup != null) {
                missCoordinator.set(CatalogMissBackfillCoordinator(
                    cacheProvider = titleCacheProvider::get,
                    lookup = CatalogSongLookup { ids -> catalogLookup.lookup("songs", ids) },
                    logger = ModernXposedRuntime::log,
                ))
                missCoordinator.get()?.prewarm()
            }
            return TargetAdaptation(
                identity = build.displayName,
                currentSong = currentSong,
                dualPane = AppleMusicDualPaneTarget(resolver, build),
                editorialVideo = AppleMusicEditorialVideoTarget(application, resolver),
                bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(resolver),
                cjkKaraokeAnimation = AppleMusicCjkKaraokeAnimationTarget(resolver),
                lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                    symbols = resolver,
                    session = lyricsTypefaceSession,
                ),
                customLyrics = AppleMusicCustomLyricsTarget(config, resolver, currentSong),
                currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                    application,
                    resolver,
                    currentSong,
                    registerCurrentSongResponder,
                ),
                titleCorrection = AppleMusicTitleCorrectionTarget(
                    application,
                    resolver,
                    settings.titleCorrectionTargetLanguage,
                    cacheProvider = titleCacheProvider,
                ),
                catalogLanguage = AppleMusicCatalogLanguageTarget(
                    resolver,
                    settings.titleCorrectionTargetLanguage,
                ),
                libraryRefresh = AppleMusicLibraryRefreshTarget(
                    application,
                    resolver,
                    classLoader,
                    settings.titleCorrectionTargetLanguage.takeIf { settings.titleCorrectionEnabled }.orEmpty(),
                    titleCacheProvider = titleCacheProvider,
                    catalogLookup = catalogLookup,
                    useRequestPermission = useLibraryRefreshPermission,
                ),
            )
        }
    }
}
internal fun interface DualPaneTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface EditorialVideoTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface BidirectionalLyricBlurTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CjkKaraokeAnimationTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface LyricsTypefaceTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CustomLyricsTarget {
    fun install(): TargetCapabilityInstall
}

internal fun interface CurrentSongIdentityTarget {
    fun install(): TargetCapabilityInstall
}

internal class AppleMusicEditorialVideoTarget(
    private val application: Application,
    private val symbols: TargetSymbolResolver,
) : EditorialVideoTarget {
    override fun install(): TargetCapabilityInstall {
        val resolution = symbols.resolve(AppleMusicSymbols.EditorialVideoUrlSelector)
        val selector = resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(resolution.summary)

        ModernXposedRuntime.hookMethod(selector, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!TabletModeQualifier.isOfficialTabletLandscape(application)) return
                param.result = null
            }
        })
        return TargetCapabilityInstall.Active(
            "Installed tablet-landscape Editorial Video URL suppression on " +
                "${selector.declaringClass.name}.${selector.name}; ${resolution.summary}",
        )
    }
}

internal sealed interface TargetCapabilityInstall {
    val message: String

    data class Active(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Degraded(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }
}

internal fun TargetCapabilityInstall.toFeatureInstallResult(): FeatureInstallResult = when (this) {
    is TargetCapabilityInstall.Active -> FeatureInstallResult.active(message)
    is TargetCapabilityInstall.Degraded -> FeatureInstallResult.degraded(message)
}
