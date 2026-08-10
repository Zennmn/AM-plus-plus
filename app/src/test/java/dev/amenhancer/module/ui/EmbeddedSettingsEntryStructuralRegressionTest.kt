package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsEntryStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found")

    @Test
    fun `injects a tagged AM plus option and opens the existing settings dialog`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(source.contains("SETTINGS_OPTION_TAG"))
        assertTrue(source.contains("AM++ 模块设置"))
        assertTrue(source.contains("字体、歌词与模块功能"))
        assertTrue(source.contains("setOnClickListener { showSettingsDialog(activity) }"))
        assertTrue(source.contains("findSettingsInsertionContainer"))
    }

    @Test
    fun `handles scroll containers and avoids direct RecyclerView child insertion`() {
        val source = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(source.contains("isScrollView(root)"))
        assertTrue(source.contains("NestedScrollView"))
        assertTrue(source.contains("isRecyclerView(root)"))
        assertTrue(source.contains("RecyclerView cannot accept arbitrary children"))
        assertTrue(source.contains("activity.window?.decorView"))
        assertTrue(source.contains("EmbeddedHostActivityRole.Settings"))
    }

    @Test
    fun `supports the verified 651 PreferenceFragment seam`() {
        val hook = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(hook.contains("com.apple.android.music.settings.fragment.SettingsFragment"))
        assertTrue(hook.contains("getDeclaredMethod(\"onResume\")"))
        assertTrue(hook.contains("onCreateView"))
        assertTrue(hook.contains("onViewCreated"))
        assertTrue(host.contains("androidx.preference.Preference"))
        assertTrue(host.contains("ModernXposedRuntime.callMethod(screen, \"S\", preference)"))
        assertTrue(host.contains("AM++ 模块设置"))
        assertTrue(host.contains("val keyWasSet = runCatching"))
        assertTrue(host.contains("if (!installNativePreferenceClick(preferenceClass, preference, activity)) return false"))
        assertTrue(host.contains("): Boolean {"))
    }

    @Test
    fun `observes fragment navigation inside MainContentActivity and cleans up`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("MAIN_CONTENT_ACTIVITY_NAME"))
        assertTrue(host.contains("ViewTreeObserver.OnGlobalLayoutListener"))
        assertTrue(host.contains("addOnGlobalLayoutListener"))
        assertTrue(host.contains("removeOnGlobalLayoutListener"))
        assertTrue(host.contains("nativePreferenceActivityIds"))
        assertTrue(host.contains("nativePreferenceFragmentReference"))
        assertTrue(host.contains("override fun onActivityCreated"))
        assertTrue(host.contains("ignoredTag"))
        assertTrue(host.contains("hasNativePreferenceClick"))
        assertTrue(host.contains("belongsToActivity"))
        assertTrue(host.contains("onSettingsFragmentViewCreated"))
        assertTrue(host.contains("onMainContentLayout"))
        assertTrue(host.contains("nativePreferenceWasReady"))
        assertTrue(host.contains("fragmentView(fragment)"))
        assertTrue(host.contains("findSettingsListOverlayContainer"))
        assertTrue(host.contains("FrameLayout.LayoutParams"))
    }

    @Test
    fun `embedded settings keeps main and custom lyrics as separate pages`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("internal enum class EmbeddedSettingsPage"))
        assertTrue(host.contains("MAIN"))
        assertTrue(host.contains("CUSTOM_LYRICS"))
        assertTrue(host.contains("renderEmbeddedMainPage"))
        assertTrue(host.contains("renderEmbeddedCustomLyricsPage"))
        assertTrue(host.contains("onOpenCustomLyrics"))
        assertTrue(host.contains("setOnClickListener { onClick() }"))
        assertTrue(host.contains("page = EmbeddedSettingsPage.CUSTOM_LYRICS"))
        assertTrue(host.contains("page = EmbeddedSettingsPage.MAIN"))
    }

    @Test
    fun `embedded font picker accepts providers that do not advertise font wildcard`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("\"*/*\""))
        assertTrue(host.contains("EMBEDDED_FONT_MIME_TYPES"))
        assertTrue(host.contains("Intent.EXTRA_MIME_TYPES"))
        assertTrue(host.contains("font/ttf"))
        assertTrue(host.contains("font/otf"))
    }

    @Test
    fun `embedded restore asks for overwrite policy after selecting a backup`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("EmbeddedSafOperation.RestoreOverwrite"))
        assertTrue(host.contains("EmbeddedSafOperation.RestoreOverwrite -> confirmEmbeddedRestore(activity, uri)"))
        assertTrue(host.contains("private fun confirmEmbeddedRestore(activity: Activity, uri: Uri)"))
        assertTrue(host.contains("setNeutralButton(\"不覆盖\")"))
        assertTrue(host.contains("setPositiveButton(\"覆盖\")"))
        assertTrue(host.contains("CustomLyricsRestorePolicy.KEEP_EXISTING"))
        assertTrue(host.contains("CustomLyricsRestorePolicy.OVERWRITE"))
    }

    @Test
    fun `embedded lyric editor exposes the same online sources as main`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("从 AMLL 导入"))
        assertTrue(host.contains("从网易云导入"))
        assertTrue(host.contains("从 GitHub 导入"))
        assertTrue(host.contains("CustomLyricsOnlineImporter"))
        assertTrue(host.contains("importEmbeddedOnlineLyrics"))
        assertTrue(host.contains("source = source"))
    }
}
