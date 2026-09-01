package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOnlyArtifactStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectPath(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::exists) ?: File(relativePath)

    @Test
    fun `artifact exposes only the injected Apple Music settings entry`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertFalse(manifest.contains("<activity"))
        assertFalse(manifest.contains("<activity-alias"))
        assertFalse(manifest.contains("android:exported=\"true\""))
        assertTrue(entry.contains("EmbeddedSettingsHost.install("))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController("))
        assertTrue(host.contains("Application.ActivityLifecycleCallbacks"))
        assertTrue(host.contains("showSettingsDialog(activity)"))
    }

    @Test
    fun `obsolete standalone settings sources are absent`() {
        listOf(
            "app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt",
            "app/src/main/java/dev/amenhancer/module/ui/LauncherIconController.kt",
            "app/src/main/java/dev/amenhancer/module/ui/CurrentSongIdentityRequester.kt",
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
            "app/src/main/java/dev/amenhancer/module/XposedServiceSnapshot.kt",
            "app/src/main/java/dev/amenhancer/module/config/ConfigStore.kt",
            "app/src/main/java/dev/amenhancer/module/font/SafFontImporter.kt",
            "app/src/main/java/dev/amenhancer/module/lyrics/CustomLyricsManager.kt",
        ).forEach { path -> assertFalse("obsolete standalone source remains: $path", projectPath(path).isFile) }
    }
}
