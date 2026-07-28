package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconVisibilityStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `launcher icon uses a disableable alias while settings activity stays available`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("<activity-alias"))
        assertTrue(manifest.contains("android:name=\".LauncherAlias\""))
        assertTrue(manifest.contains("android:targetActivity=\".ui.SettingsActivity\""))
        assertTrue(manifest.contains("android.intent.action.APPLICATION_PREFERENCES"))
        assertTrue(manifest.indexOf("<activity-alias") < manifest.indexOf("android.intent.category.LAUNCHER"))
        val settingsDeclaration = manifest.substringBefore("<activity-alias")
        assertTrue(settingsDeclaration.contains("android.intent.action.MAIN"))
        assertTrue(settingsDeclaration.contains("android.intent.category.INFO"))
        assertFalse(settingsDeclaration.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `component toggle persists locally without killing the settings process`() {
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/LauncherIconController.kt",
        )

        assertTrue(controller.contains("ComponentName("))
        assertTrue(controller.contains("dev.amenhancer.module.LauncherAlias"))
        assertFalse(controller.contains("${'$'}{appContext.packageName}.LauncherAlias"))
        assertTrue(controller.contains("getComponentEnabledSetting"))
        assertTrue(controller.contains("setComponentEnabledSetting"))
        assertTrue(controller.contains("COMPONENT_ENABLED_STATE_DISABLED"))
        assertTrue(controller.contains("COMPONENT_ENABLED_STATE_DEFAULT"))
        assertTrue(controller.contains("PackageManager.DONT_KILL_APP"))
        assertFalse(controller.contains("ModuleApplication.remotePreferences"))
    }

    @Test
    fun `settings page exposes an always available launcher visibility switch`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("title = \"隐藏启动器图标\""))
        assertTrue(activity.contains("checked = launcherIconController.isHidden()"))
        assertTrue(activity.contains("launcherIconController.setHidden(hidden)"))
        assertTrue(activity.contains("enabled = true"))
    }
}
