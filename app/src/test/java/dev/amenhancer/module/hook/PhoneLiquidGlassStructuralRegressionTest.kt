package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the phone-only liquid-glass resource and configuration contract. */
class PhoneLiquidGlassStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `persists and transports an independent phone liquid glass setting`() {
        val models = source("dev/amenhancer/module/model/ModuleModels.kt")
        val store = source("dev/amenhancer/module/config/ConfigStore.kt")
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")
        val application = source("dev/amenhancer/module/ModuleApplication.kt")
        val settings = source("dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(models.contains("val phoneLiquidGlassEnabled: Boolean = false"))
        assertTrue(store.contains("ModuleSettingsSchema.encodeOrdinarySettings(settings)"))
        assertTrue(store.contains("ModuleSettingsSchema.encodeFontManifest(manifest)"))
        assertFalse(store.contains("lyrics_font_"))
        listOf(
            "lyrics_font_enabled",
            "lyrics_font_file_id",
            "lyrics_font_display_name",
            "lyrics_font_size_bytes",
            "lyrics_font_sha256",
        ).forEach { key -> assertTrue(schema.contains("\"$key\"")) }
        assertTrue(schema.contains("\"phone_liquid_glass_enabled\""))
        assertTrue(application.contains("getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)"))
        assertTrue(client.contains("ModuleSettingsSchema.decode(preferences.all)"))
        assertTrue(settings.contains("title = \"手机液态玻璃底栏\""))
        assertTrue(settings.contains("badge = \"WIP\""))
        assertTrue(settings.contains("store.settings().copy(phoneLiquidGlassEnabled = it)"))
    }

    @Test
    fun `uses api 102 remote preferences and keeps liquid glass fail closed`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")

        assertFalse(manifest.contains("ConfigProvider"))
        assertTrue(client.contains("phoneLiquidGlassEnabled = false"))
        assertFalse(client.contains("contentResolver.call"))
    }

    @Test
    fun `registers both target layouts without entering the tablet path`() {
        val glass = source("dev/amenhancer/module/hook/PhoneLiquidGlassFeature.kt")
        val tablet = source("dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt")

        assertTrue(glass.contains("\"bottom_navigation\""))
        assertTrue(glass.contains("\"mini_player\""))
        assertTrue(tablet.contains("fun isOfficialTablet(context: Context): Boolean"))
        assertTrue(glass.contains("!TabletModeQualifier.isOfficialTablet(context)"))
        assertTrue(glass.contains("config.settings().phoneLiquidGlassEnabled"))
        assertTrue(glass.contains("installMiniPlayerWhenAvailable(root, config, attempt = 0)"))
        assertTrue(glass.contains("navigationRoot.findViewById<FrameLayout>(it)"))
        assertFalse(glass.contains("TabletModeQualifier.isEligible"))
    }

    @Test
    fun `uses render node backdrop blur with a navigation-host-only target`() {
        val glass = source("dev/amenhancer/module/hook/PhoneLiquidGlassFeature.kt")

        assertTrue(glass.contains("import eightbitlab.com.blurview.BlurView"))
        assertTrue(glass.contains("import eightbitlab.com.blurview.BlurTarget"))
        assertTrue(glass.contains("setupWith(target, 4f, false)"))
        assertTrue(glass.contains("parent.addView(target, index, hostParams)"))
        assertTrue(glass.contains("target.addView("))
        assertTrue(glass.contains("syncNavigationUnderlap(outerHost, target, hostId)"))
        assertTrue(glass.contains("expandNavigationHostPath(outerHost, hostId)"))
        assertTrue(glass.contains("outerHost.setPadding("))
        assertTrue(glass.contains("params.bottomMargin = 0"))
        assertTrue(glass.contains("FrameLayout.LayoutParams("))
        assertFalse(glass.contains("clearCoordinatorBehavior"))
        assertTrue(glass.contains("Color.argb(20, 255, 255, 255)"))
        assertTrue(glass.contains("makePlayerSheetTransparentWhileCollapsed"))
        assertTrue(glass.contains("awaitingInitialCollapse"))
        assertFalse(glass.contains("if (awaitingInitialCollapse) sheet.alpha = 0f"))
        assertTrue(glass.contains("PLAYER_FRAGMENTS_HOST"))
        assertTrue(glass.contains("INITIAL_PLAYER_PROTECTION_MS = 3_000L"))
        assertTrue(glass.contains("val visuallyCollapsed = collapsed || awaitingInitialCollapse"))
        assertTrue(glass.contains("window.navigationBarColor = Color.TRANSPARENT"))
        assertTrue(glass.contains("window.setDecorFitsSystemWindows(false)"))
        assertTrue(glass.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(glass.contains("View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION"))
        assertTrue(glass.contains("configurePlayerSheetWhenAttached(root, attempt = 0)"))
        assertTrue(glass.contains("PLAYER_BACKGROUND_LAYERS"))
        assertTrue(glass.contains("PLAYER_TOP_SHADOW"))
        assertTrue(glass.contains("BOTTOM_NAVIGATION_ROOT_STACKED"))
        assertTrue(glass.contains("if (current === stackedRoot) break"))
        assertTrue(glass.contains("LayerDrawable"))
        assertTrue(glass.contains("NAV_GLASS_HEIGHT_DP = 60"))
        assertTrue(glass.contains("NAV_GLASS_HORIZONTAL_INSET_DP = 14"))
        assertTrue(glass.contains("width = maxOf(1, navigation.width - menuInset * 2)"))
        assertTrue(glass.contains("menu.translationX = 0f"))
        assertTrue(glass.contains("LiquidSelectionIndicator"))
        assertTrue(glass.contains("NAV_SELECTION_MOTION_DURATION_MS = 480L"))
        assertTrue(glass.contains("SweepGradient"))
        assertTrue(glass.contains("navigationItemPressAnimator"))
        assertFalse(glass.contains("item.setOnClickListener"))
        assertFalse(glass.contains("chromeBackdropColor"))
        assertTrue(glass.contains("setBlurRadius(GLASS_BLUR_RADIUS)"))
        assertTrue(glass.contains("setFrameClearDrawable(ColorDrawable(Color.TRANSPARENT))"))
        assertTrue(glass.contains("addView(blurView, 0"))
        assertTrue(glass.contains("BOTTOM_NAVIGATION_TABS_FRAME"))
        assertTrue(glass.contains("clearNavigationContainerBackgrounds(navigation)"))
        assertTrue(glass.contains("MINI_PLAYER_CONTENT"))
        assertTrue(glass.contains("NAVIGATION_HOST_GROUP"))
        assertTrue(glass.contains("wrapNavigationHostInBlurTarget"))
        assertTrue(glass.contains("parent.removeViewAt(index)"))
        assertTrue(glass.contains("android.R.attr.state_checked"))
        assertFalse(glass.contains("android.R.id.content"))
        assertFalse(glass.contains("\"player_container\""))
        assertFalse(glass.contains("DualPaneShell"))
    }

    @Test
    fun `pins the apache blur dependency and reports a separate feature`() {
        val appBuild = projectFile("app/build.gradle.kts")
        val settingsBuild = projectFile("settings.gradle.kts")
        val notices = projectFile("THIRD_PARTY_NOTICES.md")
        val constants = source("dev/amenhancer/module/ModuleConstants.kt")

        assertTrue(appBuild.contains("com.github.Dimezis:BlurView:version-3.2.0"))
        assertTrue(settingsBuild.contains("https://jitpack.io"))
        assertTrue(notices.contains("Dimezis/BlurView"))
        assertTrue(notices.contains("Apache License, Version 2.0"))
        assertTrue(constants.contains("FEATURE_PHONE_LIQUID_GLASS"))
        val glass = source("dev/amenhancer/module/hook/PhoneLiquidGlassFeature.kt")
        assertTrue(glass.contains("FeatureInstallResult.degraded"))
        assertTrue(glass.contains("WIP: resource hooks registered"))
    }
}
