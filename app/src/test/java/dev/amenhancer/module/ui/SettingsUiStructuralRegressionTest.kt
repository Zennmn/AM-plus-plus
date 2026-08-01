package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `keeps content below the real system status bar`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("window.setDecorFitsSystemWindows(false)"))
        assertTrue(activity.contains("WindowInsets.Type.statusBars()"))
        assertTrue(activity.contains("WindowInsets.Type.navigationBars()"))
        assertTrue(activity.contains("view.setPadding(0, statusBars.top, 0, navigationBars.bottom)"))
        assertTrue(activity.contains("window.statusBarColor = palette.background"))
        assertFalse(activity.contains("SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN"))
        assertFalse(activity.contains("FLAG_LAYOUT_NO_LIMITS"))
    }

    @Test
    fun `centers the compact title bar instead of pinning its text below the inset`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))"))
        assertTrue(activity.contains("ViewGroup.LayoutParams.WRAP_CONTENT,\n            Gravity.CENTER_VERTICAL,"))
        assertTrue(activity.contains("textSize = 20f"))
        assertTrue(activity.contains("setPadding(dp(20), dp(16), dp(20), dp(32))"))
        assertFalse(activity.contains("minimumHeight = dp(64)"))
    }

    @Test
    fun `renders selected grouped settings direction without changing storage`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("statusCard(snapshot)"))
        assertTrue(activity.contains("store.settings(snapshot)"))
        assertTrue(activity.contains("featureCard(settings, writable)"))
        assertTrue(activity.contains("badge = \"WIP\""))
        assertTrue(activity.contains("LSPosed 配置提示"))
        assertTrue(activity.contains("store.saveSettings(store.settings().copy("))
        assertTrue(activity.contains("minimumHeight = dp(84)"))
        assertTrue(activity.contains("contentDescription = title"))
        assertTrue(activity.contains("歌词模糊半径偏移"))
        assertTrue(activity.contains("blurRadiusOffsetRow("))
        assertTrue(activity.contains("SeekBar(this@SettingsActivity)"))
        assertTrue(activity.contains("lyricBlurRadiusOffsetPx = offsetPx"))
    }

    @Test
    fun `imports fonts through a transient open document grant and keeps controls read only offline`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val importer = projectFile("app/src/main/java/dev/amenhancer/module/font/SafFontImporter.kt")
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"))
        assertTrue(activity.contains("Intent.CATEGORY_OPENABLE"))
        assertTrue(activity.contains("type = \"*/*\""))
        listOf(
            "font/ttf",
            "font/otf",
            "application/x-font-ttf",
            "application/x-font-opentype",
            "application/vnd.ms-opentype",
        ).forEach { mime -> assertTrue(activity.contains("\"$mime\"")) }
        assertTrue(activity.contains("backgroundExecutor.execute"))
        assertTrue(activity.contains("backgroundExecutor.shutdown()"))
        assertFalse(activity.contains("backgroundExecutor.shutdownNow()"))
        assertTrue(activity.contains("snapshot.isRemoteFileAvailable"))
        assertTrue(activity.contains("歌词字体"))
        assertTrue(activity.contains("原字体"))
        assertTrue(activity.contains("选择字体"))
        assertTrue(activity.contains("恢复原字体"))
        assertTrue(importer.contains("readBounded"))
        assertTrue(importer.contains("snapshot.openRemoteFile(fileId)"))
        assertFalse(activity.contains("takePersistableUriPermission"))
        assertFalse(importer.contains("uri.toString()"))
        assertFalse(manifest.contains("<provider"))
    }

    @Test
    fun `persists non touch blur radius changes without duplicating touch writes`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("var trackingTouch = false"))
        assertTrue(activity.contains("BlurRadiusSeekBarPersistencePolicy.shouldPersistProgressChange("))
        assertTrue(activity.contains("fromUser = fromUser"))
        assertTrue(activity.contains("trackingTouch = trackingTouch"))
        assertTrue(activity.contains("trackingTouch = true"))
        assertTrue(activity.contains("trackingTouch = false"))
    }

    @Test
    fun `provides a dedicated dark theme and system bar colors`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val darkTheme = projectFile("app/src/main/res/values-night/styles.xml")

        assertTrue(activity.contains("Configuration.UI_MODE_NIGHT_YES"))
        assertTrue(darkTheme.contains("Theme.Material.NoActionBar"))
        assertTrue(darkTheme.contains("android:windowLightStatusBar\">false"))
    }
}
