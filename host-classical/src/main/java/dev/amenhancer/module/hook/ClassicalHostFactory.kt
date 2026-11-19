package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import dev.amenhancer.host.classical.ClassicalHostProfiles
import dev.amenhancer.module.config.TargetConfigClient

internal fun classicalTargetBuild(context: Context): TargetBuild = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    TargetBuild(context.packageName, info.versionName.orEmpty(), code)
}.getOrElse { TargetBuild(context.packageName, "", -1) }

/** Apple Music Classical composition façade parallel to [AppleMusicHostFactory]. */
object ClassicalHostFactory {
    fun settingsActivityMatcher(context: Context): SettingsActivityMatcher {
        val build = classicalTargetBuild(context)
        val profile = checkNotNull(ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        val settingsActivity = profile.document.getJSONObject("activities").getString("settings")
        return ClassicalSettingsActivityMatcher(settingsActivity, context.packageName)
    }

    fun settingsViewBridge(): SettingsViewBridge = ClassicalSettingsViewBridge()

    fun installSettingsEntry(context: Context, loader: ClassLoader, observer: SettingsEntryObserver) {
        val result = ClassicalSettingsEntryInstaller(context).install(loader, observer)
        ModernXposedRuntime.log(result.message)
    }

    fun newTypefaceResources(application: Application, classLoader: ClassLoader): LyricsTypefaceResourceBinding? {
        val build = classicalTargetBuild(application)
        val profile = ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode) ?: return null
        return ClassicalFontTarget(classLoader, profile)
    }

    fun installChromeHooks(
        application: Application,
        classLoader: ClassLoader,
        dialogGlassObserver: MultiSurfaceGlassObserver? = null,
    ): ClassicalChromePort? {
        val build = classicalTargetBuild(application)
        val profile = ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode) ?: return null
        if (!profile.productionEnabled || !profile.capability("glass")) return null
        return ClassicalChromeHooks(application, classLoader, profile, dialogGlassObserver).apply { install() }
    }

    fun isMainActivity(activity: Activity): Boolean {
        val build = classicalTargetBuild(activity)
        val profile = ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode) ?: return false
        return activity.javaClass.name == profile.document.getJSONObject("activities").getString("main")
    }

    fun classical(
        config: TargetConfigClient,
        application: Application,
        classLoader: ClassLoader,
        typefaceBinding: LyricsTypefaceResourceBinding,
    ): TargetAdaptation {
        val build = classicalTargetBuild(application)
        val profile = checkNotNull(ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        val unsupported: (String) -> TargetCapabilityInstall = { TargetCapabilityInstall.Unsupported(it) }
        return TargetAdaptation(
            identity = build.displayName,
            build = build,
            dualPane = ClassicalDualPaneTarget(application, classLoader, profile) { config.settings().dualPaneEnabled },
            editorialVideo = EditorialVideoTarget { unsupported("Editorial video is Apple Music specific") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget { unsupported("Lyrics blur is Apple Music specific") },
            cjkKaraokeAnimation = CjkKaraokeAnimationTarget { unsupported("Karaoke lyrics are Apple Music specific") },
            lyricsTypeface = (typefaceBinding as? ClassicalFontTarget) ?: ClassicalFontTarget(classLoader, profile),
            customLyrics = CustomLyricsTarget { unsupported("Custom TTML lyrics are Apple Music specific") },
            currentSongIdentity = CurrentSongIdentityTarget { unsupported("AM song identity cache is not used by Classical") },
            hleMetadata = HleMetadataTarget { unsupported("AM HLE metadata is not used by Classical") },
            cellularDataEntry = CellularDataEntryTarget { unsupported("Cellular toggle is Apple Music specific") },
            classicalGuideTranslation = ClassicalGuideTranslatorTarget(classLoader, profile) { config.settings() },
        )
    }
}
