package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSettingsEntryStructuralRegressionTest {
    private data class VisibleSetting(
        val title: String,
        val field: String? = null,
    )

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found")

    private fun assertSourceContains(source: String, marker: String, description: String) {
        assertTrue("missing $description: $marker", source.contains(marker))
    }

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
        assertTrue(hook.contains("findPreferenceSetup"))
        assertTrue(hook.contains("onSettingsPreferencesReady"))
        assertTrue(hook.contains("getDeclaredMethod(\"onResume\")"))
        assertTrue(hook.contains("onCreateView"))
        assertTrue(hook.contains("onViewCreated"))
        assertTrue(host.contains("androidx.preference.Preference"))
        assertTrue(host.contains("ModernXposedRuntime.callMethod(screen, \"P\", preference)"))
        assertTrue(!host.contains("ModernXposedRuntime.callMethod(screen, \"S\", preference)"))
        assertTrue(host.contains("ModernXposedRuntime.callMethod(fragment, \"t0\", key)"))
        assertTrue(!host.contains("ModernXposedRuntime.callMethod(fragment, \"s0\", key)"))
        assertTrue(host.contains("AM++ 模块设置"))
        assertTrue(host.contains("val keyWasSet = runCatching"))
        assertTrue(host.contains("if (!installNativePreferenceClick(preferenceClass, preference, activity)) return false"))
        assertTrue(host.contains("): Boolean {"))
        assertTrue(host.contains("scheduleSettingsOptionFallback"))
        assertTrue(host.contains("scheduleNativePreferenceRefresh"))
        assertTrue(host.contains("refreshNativePreferenceAdapter"))
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
    fun `embedded main page exposes title correction and target language picker`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("歌曲名显示修正"))
        assertTrue(host.contains("settings.titleCorrectionEnabled"))
        assertTrue(host.contains("titleCorrectionEnabled = it"))
        assertTrue(host.contains("目标语言"))
        assertTrue(host.contains("titleCorrectionTargetLanguage"))
        assertTrue(host.contains("CatalogLanguagePolicy.displayName"))
        assertTrue(host.contains("CatalogLanguagePolicy.isValid(raw)"))
        assertTrue(host.contains("CatalogLanguagePolicy.normalize(raw)"))
        assertTrue(host.contains("showEmbeddedTargetLanguagePicker"))
        assertTrue(host.contains("val tags = EMBEDDED_CATALOG_LANGUAGE_TAGS"))
    }

    @Test
    fun `embedded main page exposes an in-process library refresh action`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val controller = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedRuntimeSettingsController.kt",
        )

        assertTrue(host.contains("刷新资料库"))
        assertTrue(host.contains("onRefreshLibrary = { requestLibraryRefresh(activity) }"))
        assertTrue(host.contains("controller.requestLibraryRefresh(::finish)"))
        assertTrue(host.contains("controller.cancelLibraryRefresh()"))
        assertTrue(host.contains("LibraryRefreshProtocol.RESULT_COMPLETED"))
        assertTrue(host.contains("A sendBroadcast failure can synchronously complete"))
        assertTrue(controller.contains("LibraryRefreshRequester(appContext)"))
        assertTrue(controller.contains("override fun requestLibraryRefresh"))
        assertTrue(controller.contains("override fun cancelLibraryRefresh"))
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
        assertTrue(host.contains("GitHub 同步"))
        assertTrue(host.contains("controller.syncFromGitHub"))
    }

    @Test
    fun `main settings visible item checklist stays mirrored in embedded host`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val settings = listOf(
            VisibleSetting("平板双栏播放器", "dualPaneEnabled"),
            VisibleSetting("平板底栏补偿", "navigationCompensationEnabled"),
            VisibleSetting("平板禁用动态视频", "disableEditorialVideoOnTablet"),
            VisibleSetting("手机液态玻璃底栏", "phoneLiquidGlassEnabled"),
            VisibleSetting("双向歌词模糊", "futureBlurEnabled"),
            VisibleSetting("歌词模糊半径偏移", "lyricBlurRadiusOffsetPx"),
            VisibleSetting("歌曲名显示修正", "titleCorrectionEnabled"),
            VisibleSetting("目标语言", "titleCorrectionTargetLanguage"),
            VisibleSetting("刷新资料库"),
            VisibleSetting("自定义歌词"),
        )

        settings.forEach { item ->
            assertSourceContains(activity, "\"${item.title}\"", "SettingsActivity item ${item.title}")
            assertSourceContains(host, "\"${item.title}\"", "EmbeddedSettingsHost item ${item.title}")
            item.field?.let { field ->
                assertSourceContains(
                    activity,
                    "settings.$field",
                    "SettingsActivity binding for ${item.title}",
                )
                assertSourceContains(
                    host,
                    "settings.$field",
                    "EmbeddedSettingsHost binding for ${item.title}",
                )
                assertSourceContains(
                    activity,
                    "$field =",
                    "SettingsActivity write path for ${item.title}",
                )
                assertSourceContains(
                    host,
                    "$field =",
                    "EmbeddedSettingsHost write path for ${item.title}",
                )
            }
        }

        assertSourceContains(
            activity,
            "showTargetLanguagePicker()",
            "standalone target-language action",
        )
        assertSourceContains(
            host,
            "showEmbeddedTargetLanguagePicker(",
            "embedded target-language action",
        )
        assertSourceContains(activity, "requestLibraryRefresh()", "standalone refresh action")
        assertSourceContains(
            host,
            "onRefreshLibrary = { requestLibraryRefresh(activity) }",
            "embedded refresh action",
        )
        assertSourceContains(
            activity,
            "showPage(SettingsPage.CUSTOM_LYRICS)",
            "standalone custom-lyrics navigation",
        )
        assertSourceContains(
            host,
            "page = EmbeddedSettingsPage.CUSTOM_LYRICS",
            "embedded custom-lyrics navigation",
        )
    }

    @Test
    fun `font and custom lyric page checklist stays mirrored in embedded host`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        listOf(
            "歌词字体",
            "选择字体",
            "恢复原字体",
            "自定义歌词替换",
            "添加歌词",
            "备份歌词",
            "恢复备份",
            "搜索名称或 Apple Music ID",
            "加载更多",
            "编辑",
            "删除",
        ).forEach { title ->
            assertSourceContains(activity, "\"$title\"", "SettingsActivity item $title")
            assertSourceContains(host, "\"$title\"", "EmbeddedSettingsHost item $title")
        }
        assertSourceContains(activity, "\"同步 GitHub 源\"", "SettingsActivity GitHub action")
        assertSourceContains(host, "\"GitHub 同步\"", "EmbeddedSettingsHost GitHub action")

        assertSourceContains(
            activity,
            "settings.customLyricsEnabled",
            "standalone custom-lyrics toggle binding",
        )
        assertSourceContains(
            host,
            "settings.customLyricsEnabled",
            "embedded custom-lyrics toggle binding",
        )
        assertSourceContains(
            activity,
            "settings.fontManifest",
            "standalone font manifest binding",
        )
        assertSourceContains(
            host,
            "settings.fontManifest",
            "embedded font manifest binding",
        )
    }

    @Test
    fun `custom lyric editor keeps import identity and multi-id capabilities in both surfaces`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        listOf("从 AMLL 导入", "从网易云导入", "从 GitHub 导入").forEach { title ->
            assertSourceContains(activity, "\"$title\"", "SettingsActivity editor action $title")
            assertSourceContains(host, "\"$title\"", "EmbeddedSettingsHost editor action $title")
        }
        listOf("导入 TTML", "获取 ID").forEach { title ->
            assertSourceContains(activity, "\"$title\"", "SettingsActivity editor action $title")
            assertSourceContains(host, "\"$title\"", "EmbeddedSettingsHost editor action $title")
        }

        listOf(
            "CustomLyricsIdParser.parse",
            "CustomLyricsMultiIdDraft",
            "saveMany(",
        ).forEach { marker ->
            assertSourceContains(activity, marker, "SettingsActivity multi-id editor support")
            assertSourceContains(host, marker, "EmbeddedSettingsHost multi-id editor support")
        }
    }
}
