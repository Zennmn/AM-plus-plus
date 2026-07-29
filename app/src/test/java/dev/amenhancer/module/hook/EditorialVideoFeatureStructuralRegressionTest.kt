package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the modified APK's Editorial Video suppression while keeping the
 * module's tablet-only scope and independent settings contract explicit.
 */
class EditorialVideoFeatureStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `persists and transports the tablet editorial video setting default on`() {
        val models = source("dev/amenhancer/module/model/ModuleModels.kt")
        val store = source("dev/amenhancer/module/config/ConfigStore.kt")
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")
        val application = source("dev/amenhancer/module/ModuleApplication.kt")
        val settings = source("dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(models.contains("val disableEditorialVideoOnTablet: Boolean = true"))
        assertTrue(schema.contains("\"disable_editorial_video_on_tablet\""))
        assertTrue(schema.contains("settings.disableEditorialVideoOnTablet"))
        assertTrue(store.contains("ModuleSettingsSchema.encode(settings)"))
        assertTrue(application.contains("getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)"))
        assertTrue(client.contains("ModuleSettingsSchema.decode(preferences.all)"))
        assertTrue(settings.contains("平板禁用动态视频"))
        assertTrue(settings.contains("store.settings().copy(disableEditorialVideoOnTablet = it)"))
    }

    @Test
    fun `matches only the modified apk editorial video url selector contract`() {
        val symbols = source("dev/amenhancer/module/hook/TargetSymbols.kt")
        val target = source("dev/amenhancer/module/hook/TargetAdaptation.kt")

        assertTrue(symbols.contains("com.apple.android.music.player.c1"))
        assertTrue(symbols.contains("com.apple.android.music.model.Song"))
        assertTrue(symbols.contains("Float::class.javaPrimitiveType"))
        assertTrue(symbols.contains("EditorialVideo\\\$Flavor"))
        assertTrue(symbols.contains("method.parameterTypes[2].isArray"))
        assertTrue(symbols.contains("method.returnType == String::class.java"))
        assertTrue(target.contains("AppleMusicSymbols.EditorialVideoUrlSelector"))
        assertFalse(target.contains("TextureView"))
    }

    @Test
    fun `suppresses only in official tablet landscape without consulting dual pane`() {
        val qualifier = source("dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt")
        val feature = source("dev/amenhancer/module/hook/EditorialVideoFeature.kt")
        val target = source("dev/amenhancer/module/hook/TargetAdaptation.kt")

        assertTrue(qualifier.contains("fun isOfficialTabletLandscape(context: Context): Boolean"))
        assertTrue(target.contains("TabletModeQualifier.isOfficialTabletLandscape(application)"))
        assertTrue(target.contains("param.result = null"))
        assertFalse(feature.contains("dualPaneEnabled"))
        assertFalse(target.contains("TabletModeQualifier.isEligible"))
    }

    @Test
    fun `installs editorial suppression as an independent reported feature`() {
        val constants = source("dev/amenhancer/module/ModuleConstants.kt")
        val feature = source("dev/amenhancer/module/hook/EditorialVideoFeature.kt")

        assertTrue(constants.contains("FEATURE_EDITORIAL_VIDEO"))
        assertTrue(feature.contains("ModuleConstants.FEATURE_EDITORIAL_VIDEO"))
        assertTrue(feature.contains("settings().disableEditorialVideoOnTablet"))
        assertTrue(feature.contains("context.target.editorialVideo.install()"))
        listOf("Class<", "Method", "Field", "TargetResolution", "AppleMusicSymbols").forEach {
            forbidden -> assertFalse("feature leaked $forbidden", feature.contains(forbidden))
        }
    }
}
