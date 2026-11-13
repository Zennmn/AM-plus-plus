package dev.amenhancer.module.hook
import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.CustomLyricsEntry

internal fun assembleAppleMusicTarget(
    config: TargetConfigClient,
    application: Application,
    classLoader: ClassLoader,
    lyricsTypefaceSession: LyricsTypefaceResourceBinding,
    currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
): TargetAdaptation {
    val settings = config.settings()
    val automatic = if ((settings.customLyricsEnabled && settings.automaticLyricsEnabled) ||
        settings.llmLyricsTransliterationEnabled
    ) {
        val suppressed = runCatching { config.customLyricsManifest().entries
            .filterNot { it.enabled }.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        createAutoLyricsRuntime(application, suppressed, config::settings)
    } else null
    return AppleMusicHostFactory.appleMusic(config, application, classLoader, lyricsTypefaceSession, currentSong, automatic)
}
