package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.CustomLyricsEntry

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
    /** Retained only for compatibility; the former global target is never installed. */
    val catalogLanguage: CatalogLanguageTarget = CatalogLanguageTarget {
        TargetCapabilityInstall.Degraded("Catalog language target is intentionally disabled")
    },
    val hleMetadata: HleMetadataTarget = HleMetadataTarget {
        TargetCapabilityInstall.Degraded("HLE metadata target was not configured")
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
        ): TargetAdaptation {
            val build = targetBuild(application)
            val resolver = IndexedTargetSymbolResolver(
                build = build,
                source = ApkTargetClassSource(application, classLoader),
            )
            val settings = config.settings()
            val autoLyricsRuntime = (settings.customLyricsEnabled && settings.automaticLyricsEnabled)
                .takeIf { it }
                ?.let {
                    val suppressedAutoIds = runCatching {
                        config.customLyricsManifest().entries
                            .filterNot { entry -> entry.enabled }
                            .mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
                    }.getOrDefault(emptySet())
                    createAutoLyricsRuntime(application, suppressedAutoIds)
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
                customLyrics = AppleMusicCustomLyricsTarget(
                    config = config,
                    symbols = resolver,
                    currentSong = currentSong,
                    autoLyricsRuntime = autoLyricsRuntime,
                ),
                currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                    application,
                    resolver,
                    currentSong,
                    registerCurrentSongResponder,
                ),
                catalogLanguage = AppleMusicCatalogLanguageTarget(
                    symbols = resolver,
                    rawTargetLanguage = settings.titleCorrectionMode.catalogLanguage.orEmpty(),
                ),
                hleMetadata = HleMetadataTarget {
                    val activeModule = ModernXposedRuntime.activeModule()
                        ?: return@HleMetadataTarget TargetCapabilityInstall.Degraded(
                            "Modern Xposed module was not attached",
                        )
                    runCatching {
                        HleMetadataRuntime(
                            module = activeModule,
                            application = application,
                            classLoader = classLoader,
                            mode = settings.titleCorrectionMode,
                        ).install()
                    }.getOrElse { error ->
                        ModernXposedRuntime.log("HLE metadata runtime install failed", error)
                        TargetCapabilityInstall.Degraded(
                            "HLE metadata runtime failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
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

internal fun interface HleMetadataTarget {
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
