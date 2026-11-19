package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.settings.EmbeddedRuntimeSettingsController
import dev.amenhancer.module.ui.showEmbeddedLyricsMenu

internal fun assembleAppleMusicTarget(
    config: TargetConfigClient,
    application: Application,
    classLoader: ClassLoader,
    lyricsTypefaceSession: LyricsTypefaceResourceBinding,
    currentSong: CurrentSongIdentityCache,
    settingsController: EmbeddedRuntimeSettingsController,
): TargetAdaptation {
    val automatic = createAutoLyricsRuntime(
        application,
        content = settingsController.content,
        lunabeatCatalogCache = settingsController.lunabeatCatalogCache,
        currentSong = currentSong,
        configuration = config::settings,
    )
    return AppleMusicHostFactory.appleMusic(config, application, classLoader, lyricsTypefaceSession, currentSong, automatic,
        onOpenLyricsMenu = { activity ->
            showEmbeddedLyricsMenu(
                activity, currentSong, automatic.playback, config::settings,
                writeSourceOrder = settingsController::saveAutomaticLyricsSourceOrder,
                writeAutomaticLyricsEnabled = settingsController::saveAutomaticLyricsEnabled,
            )
        },
    )
}
