package dev.amenhancer.module.hook

import android.app.Application
import android.view.View
import dev.amenhancer.module.config.TargetConfigClient

/** Apple Music composition; the application supplies configuration and automatic-lyric services. */
object AppleMusicHostFactory {
    fun installDensity(application: Application, configuredDpi: Int): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.install(application,configuredDpi)
    fun densityStatus(): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.status

    private fun settingsNames(context: android.content.Context): org.json.JSONObject {
        val build=targetBuild(context)
        return checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName,build.versionName,build.versionCode)).document.getJSONObject("settings")
    }
    private fun fragmentFamily(context: android.content.Context): Boolean {
        val build = targetBuild(context)
        return dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(
            build.packageName, build.versionName, build.versionCode,
        )?.family == "fragment-content"
    }
    fun preservesNativeEditorialVideo(context: android.content.Context): Boolean = fragmentFamily(context)
    fun settingsActivityMatcher(context: android.content.Context, playerClass: Class<*>?): SettingsActivityMatcher =
        if (fragmentFamily(context)) FragmentSettingsNativeFactory.activityMatcher(context)
        else settingsNames(context).let { LegacySettingsActivityMatcher(playerClass,it.getString("playerActivity"),it.getString("mainActivity"),context.packageName) }
    fun settingsViewBridge(context: android.content.Context, onOpen: (android.app.Activity)->Unit): SettingsViewBridge =
        if (fragmentFamily(context)) FragmentSettingsNativeFactory.viewBridge(context,onOpen)
        else LegacySettingsViewBridge(context,onOpen)
    fun installSettingsEntry(context: android.content.Context, loader: ClassLoader, observer: SettingsEntryObserver) =
        if (fragmentFamily(context)) { FragmentSettingsNativeFactory.install(context,loader,observer); Unit }
        else LegacySettingsEntryInstaller(context).install(loader,observer)

    fun bindChrome(activity: android.app.Activity): ChromeHostBinding = LegacyChromeHostBinding(activity)
    fun installChromeHooks(loader: ClassLoader, build: TargetBuild, observer: ChromeHookObserver): HostSubscription =
        LegacyChromeHookInstaller.install(loader, build, observer)
    fun newTypefaceResources(): LyricsTypefaceResourceBinding = LyricsTypefaceSession()
    fun registerDualPaneResources() {
        DualPaneResourceHook.install()
        FragmentDualPaneResources.install()
    }
    fun registerLyricAuxiliaryResources() = LyricCreditsRowResourceHook.install()
    fun installLayoutCallbacks() = LayoutInflationRegistry.install()
    fun registerChromeResources(observer: (View) -> Unit) {
        listOf("bottom_navigation", "mini_player").forEach { name ->
            LayoutInflationRegistry.register(name, observer)
        }
    }
    fun appleMusic(
        config: TargetConfigClient,
        application: Application,
        classLoader: ClassLoader,
        lyricsTypefaceSession: LyricsTypefaceResourceBinding,
        currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
        autoLyricsRuntime: AutoLyricsRuntime? = null,
    ): TargetAdaptation {
        val build = targetBuild(application)
        val profile = checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        check(profile.family in setOf("legacy-activity", "fragment-content")) {
            "Host family ${profile.family} needs its own verified native capability factory"
        }
        val resolver = IndexedTargetSymbolResolver(
            build = build,
            source = ApkTargetClassSource(application, classLoader),
        )
        val settings = config.settings()
        val customLyrics = AppleMusicCustomLyricsTarget(
            config = config,
            symbols = resolver,
            currentSong = currentSong,
            autoLyricsRuntime = autoLyricsRuntime,
        )
        val lyricRepairs by lazy {
            val parser = customLyrics.installParserRepair()
            val alignment = installLineLyricAgentAlignment(profile, classLoader, application.resources) {
                config.settings().lyricEffectsOptimizationEnabled
            }
            when {
                alignment is TargetCapabilityInstall.Unsupported -> parser
                parser is TargetCapabilityInstall.Active && alignment is TargetCapabilityInstall.Active ->
                    TargetCapabilityInstall.Active("${parser.message}; ${alignment.message}")
                else -> TargetCapabilityInstall.Degraded("${parser.message}; ${alignment.message}")
            }
        }
        return TargetAdaptation(
            identity = build.displayName,
            build = build,
            currentSong = currentSong,
            playerRecovery = FragmentPlayerRecoveryTarget(resolver, build),
            startupPagePrewarm = FragmentStartupPagePrewarmTarget(
                application, classLoader, { config.settings().startupPagePrewarmEnabled },
                onFailure = { error -> config.reportHealth(dev.amenhancer.module.model.FeatureHealth(
                    dev.amenhancer.module.ModuleConstants.FEATURE_STARTUP_PAGE_PREWARM,
                    dev.amenhancer.module.model.FeatureState.DEGRADED,
                    "Startup page prewarming stopped: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}",
                    build.displayName,
                )) },
            ),
            dualPane = if (profile.family == "fragment-content") FragmentDualPaneTarget(resolver, build)
                else AppleMusicDualPaneTarget(resolver, build),
            editorialVideo = editorialVideoTargetForFamily(profile.family) {
                AppleMusicEditorialVideoTarget(application, resolver)
            },
            cellularDataEntry = if (profile.family == "fragment-content") FragmentCellularDataEntryTarget(
                resolver, build, classLoader, { config.settings().forceCellularDataEntryEnabled },
            ) else AppleMusicCellularDataEntryTarget(
                symbols = resolver,
                build = build,
                enabled = { config.settings().forceCellularDataEntryEnabled },
            ),
            bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(profile, classLoader),
            cjkKaraokeAnimation = AppleMusicCjkKaraokeAnimationTarget(
                symbols = resolver,
                foregroundTextField = profile.document.optJSONObject("cjk")
                    ?.optString("foregroundTextField", "U") ?: "U",
                terminalGradient = AppleMusicCjkTerminalGradientTarget(profile, classLoader),
                maxSpanGlowCharacters = profile.document.optJSONObject("cjk")
                    ?.optInt("maxSpanGlowCharacters", 1) ?: 1,
            ),
            lyricWrapSweep = AppleMusicLyricWrapSweepTarget(profile, classLoader),
            lyricBackgroundHighlight = AppleMusicLyricBackgroundHighlightTarget(profile, classLoader,
                onFailure = { message -> config.reportHealth(dev.amenhancer.module.model.FeatureHealth(
                    dev.amenhancer.module.ModuleConstants.FEATURE_LYRIC_BACKGROUND_HIGHLIGHT,
                    dev.amenhancer.module.model.FeatureState.DEGRADED, message, build.displayName,
                )) },
            ),
            lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                symbols = resolver,
                session = lyricsTypefaceSession as LyricsTypefaceSession,
            ),
            customLyrics = customLyrics,
            lyricsParser = LyricsParserTarget { lyricRepairs },
            currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                resolver,
                currentSong,
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
