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
    fun `provides a dedicated dark theme and system bar colors`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val darkTheme = projectFile("app/src/main/res/values-night/styles.xml")

        assertTrue(activity.contains("Configuration.UI_MODE_NIGHT_YES"))
        assertTrue(darkTheme.contains("Theme.Material.NoActionBar"))
        assertTrue(darkTheme.contains("android:windowLightStatusBar\">false"))
    }
}
