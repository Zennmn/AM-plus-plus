package dev.amenhancer.module.hook
import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.ui.showEmbeddedLyricsMenu

internal fun assembleAppleMusicTarget(
    config: TargetConfigClient,
    application: Application,
    classLoader: ClassLoader,
    lyricsTypefaceSession: LyricsTypefaceResourceBinding,
    currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
    writeAutomaticLyricsSourceOrder: ((List<String>) -> Boolean)? = null,
): TargetAdaptation {
    val automatic = run {
        val suppressed = runCatching { config.customLyricsManifest().entries
            .filterNot { it.enabled }.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        createAutoLyricsRuntime(application, suppressed, config::settings)
    }
    return AppleMusicHostFactory.appleMusic(config, application, classLoader, lyricsTypefaceSession, currentSong, automatic,
        onOpenLyricsMenu = { activity ->
            showEmbeddedLyricsMenu(activity, currentSong, automatic.playback, config::settings, writeAutomaticLyricsSourceOrder)
        },
    )
}
