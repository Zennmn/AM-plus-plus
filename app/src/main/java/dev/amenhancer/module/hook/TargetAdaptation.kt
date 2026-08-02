package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient

/**
 * The complete target-specific seam used by feature hooks.
 *
 * Each feature receives only its own capability, while symbol discovery and
 * reflective hook installation remain private to the Apple Music adapters.
 */
internal data class TargetAdaptation(
    val identity: String,
    val dualPane: DualPaneTarget,
    val editorialVideo: EditorialVideoTarget,
    val bidirectionalLyricBlur: BidirectionalLyricBlurTarget,
    val lyricsTypeface: LyricsTypefaceTarget = LyricsTypefaceTarget {
        TargetCapabilityInstall.Degraded("Lyrics typeface target was not configured")
    },
    val customLyrics: CustomLyricsTarget = CustomLyricsTarget {
        TargetCapabilityInstall.Degraded("Custom lyrics target was not configured")
    },
    val currentSongIdentity: CurrentSongIdentityTarget = CurrentSongIdentityTarget {
        TargetCapabilityInstall.Degraded("Current song identity target was not configured")
    },
) {
    companion object {
        fun appleMusic(
            config: TargetConfigClient,
            application: Application,
            classLoader: ClassLoader,
            lyricsTypefaceSession: LyricsTypefaceSession? = null,
        ): TargetAdaptation {
            val build = targetBuild(application)
            val resolver = IndexedTargetSymbolResolver(
                build = build,
                source = ApkTargetClassSource(application, classLoader),
            )
            val currentSong = CurrentSongIdentityCache()
            return TargetAdaptation(
                identity = build.displayName,
                dualPane = AppleMusicDualPaneTarget(resolver),
                editorialVideo = AppleMusicEditorialVideoTarget(application, resolver),
                bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(resolver),
                lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                    symbols = resolver,
                    session = lyricsTypefaceSession ?: LyricsTypefaceSession(),
                ),
                customLyrics = AppleMusicCustomLyricsTarget(config, resolver),
                currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                    application,
                    resolver,
                    currentSong,
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
