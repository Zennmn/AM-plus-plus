package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
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
        assertTrue(host.contains("ModernXposedRuntime.callMethod(targetScreen, \"P\", preference)"))
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
        assertTrue(host.contains("findNativePreferencesByKey"))
        assertTrue(host.contains("removeDuplicateNativePreferences"))
        assertTrue(host.contains("MAX_NATIVE_PREFERENCE_SCAN"))
    }

    @Test
    fun settingsEntryKeepsFirstNativeRowAndRemovesFallbackDuplicates() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("val screenMatches = screen?.let"))
        assertTrue(host.contains("val keeper = matches.firstOrNull { it === preferred } ?: matches.first()"))
        assertTrue(host.contains("matches.drop(1).forEach { duplicate ->"))
        assertTrue(host.contains("deduplicateTaggedSettingsOptions(activity)"))
        assertTrue(host.contains("findTaggedViews(activity, SETTINGS_OPTION_TAG)"))
        assertTrue(host.contains("MAX_TAGGED_VIEW_SCAN"))
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
    fun `reference tablet layout keeps compact page-specific panel proportions`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("0.514f"))
        assertTrue(host.contains("0.418f"))
        assertTrue(host.contains("embeddedTopBarHeight"))
        assertTrue(host.contains("embeddedSettingRowHeight"))
        assertTrue(host.contains("embeddedNavigationRowHeight"))
        assertTrue(host.contains("embeddedSearchFieldHeight"))
        assertTrue(host.contains("headerDivider.visibility"))
        assertTrue(host.contains("inlineSummary = true"))
        assertTrue(host.contains("syncDialogLayout()"))
    }

    @Test
    fun `phone settings uses a compact typography and geometry profile`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("height * 0.70f"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 60 else 52"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 40 else 34"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 72 else if (compactWide) 68 else 56"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 72 else if (compactWide) 60 else 52"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 48 else 44"))
        assertTrue(host.contains("embeddedTextSize(activity, 16f, 14f)"))
        assertTrue(host.contains("embeddedTextSize(activity, 13f, 12f)"))
        assertTrue(host.contains("ellipsize = android.text.TextUtils.TruncateAt.END"))
    }

    @Test
    fun `embedded dimensions follow the host density without a second width scale`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("private fun embeddedTextSize"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) phone else wide"))
        assertTrue(host.contains("activity.resources.displayMetrics.density"))
        assertFalse(host.contains("embeddedPhoneScale(activity)"))
    }

    @Test
    fun `main page uses a controlled close bar instead of the default dialog button panel`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("val closeBar = LinearLayout(activity).apply"))
        assertTrue(host.contains("setOnClickListener { dialog.dismiss() }"))
        assertTrue(host.contains("addView(closeBar"))
        assertTrue(host.contains("closeBar.visibility = if (page == EmbeddedSettingsPage.MAIN) View.VISIBLE else View.GONE"))
        assertFalse(host.contains(".setNegativeButton(\"关闭\", null)"))
    }

    @Test
    fun `settings panel uses the same rounded outline as the advanced settings card`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("val panelBackground = GradientDrawable().apply"))
        assertTrue(host.contains("cornerRadius = embeddedCardCornerRadius(activity)"))
        assertTrue(host.contains("clipToOutline = true"))
        assertTrue(host.contains("setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))"))
        assertTrue(host.contains("private fun embeddedCardCornerRadius(activity: Activity): Float"))
    }

    @Test
    fun `visible embedded controls use the shared reference glyph family`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        listOf(
            "TabletDualPane",
            "BottomBar",
            "VideoDisplay",
            "Glass",
            "LyricsBlur",
            "Document",
            "Translate",
            "Refresh",
            "AddCircle",
            "TtmlDocument",
            "Github",
            "CloudBackup",
            "Search",
            "Edit",
            "Delete",
            "BackArrow",
            "ChevronRight",
            "MoreVertical",
        ).forEach { glyph ->
            assertTrue("missing embedded glyph $glyph", host.contains("EmbeddedGlyphKind.$glyph"))
        }
        assertTrue(!host.contains("android.R.drawable.ic_menu_edit"))
        assertTrue(!host.contains("android.R.drawable.ic_menu_delete"))
        assertTrue(host.contains("embeddedActionGlyphSize"))
        assertTrue(host.contains("if (isEmbeddedPhone(activity)) 28 else 36"))
        assertTrue(host.contains("strokeWidthFraction = 0.055f"))
        assertTrue(!host.contains("setImageDrawable(loadEmbeddedArrowIcon"))
    }

    @Test
    fun `custom lyrics action bar retains the compact geometry in every layout`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val customLyrics = host
            .substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("val search = EditText")

        listOf(
            "embeddedCompactLyricsActionBar",
            "dp(activity, 76)",
            "marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 5)",
            "marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 4 else 3)",
            "bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 12 else 14)",
            "embeddedCompactLyricsActionDivider",
            "embeddedCustomLyricsDivider",
            "isVerticalScrollBarEnabled = false",
            "fillType = Path.FillType.EVEN_ODD",
        ).forEach { marker ->
            assertTrue("missing custom-lyrics reference marker $marker", host.contains(marker))
        }
        assertTrue(!customLyrics.contains("embeddedIconActionButton("))
    }

    @Test
    fun `user SVG custom lyrics glyphs do not depend on host package resources`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val svgAssets = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsSvgAssets.kt")

        listOf(
            "EmbeddedSvgIcon.AddLyrics",
            "EmbeddedSvgIcon.ImportTtml",
            "EmbeddedSvgIcon.GitHubSync",
            "EmbeddedSvgIcon.BackupRestore",
            "EmbeddedSvgIcon.Back",
            "embeddedSvgDrawable",
        ).forEach { marker ->
            assertTrue("missing host-independent raster glyph marker $marker", host.contains(marker))
        }
        assertTrue(
            "SVG glyphs must not depend on ImageGen raster assets or module resource lookup",
            !host.contains("EmbeddedRasterIcon") &&
                !host.contains("embeddedRasterDrawable") &&
                !host.contains("loadEmbeddedModuleDrawable"),
        )
        listOf("#A94B76", "#A94B73", "#A34E74", "#A84C76", "#F33343").forEach { color ->
            assertTrue("missing user SVG path colour $color", svgAssets.contains("Color.parseColor(\"$color\")"))
        }
        assertTrue(!svgAssets.contains("Color.parseColor(\"none\")"))
        assertTrue(host.contains("icon is EmbeddedOwnColorDrawable"))
    }

    @Test
    fun `follow-up embedded polish uses the source brand mark and removes duplicate lyric status`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val customLyricsPage = host
            .substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("val lyricsContent")
        val liquidGlassRow = host
            .substringAfter("\"手机液态玻璃底栏\"")
            .substringBefore("addView(embeddedDivider(activity))")

        assertTrue(host.contains("EmbeddedAmppBrandDrawable"))
        assertTrue(!host.contains("loadEmbeddedModuleIcon"))
        assertTrue(!customLyricsPage.contains("embeddedNavigationRow("))
        assertTrue(liquidGlassRow.contains("badgeAtToggle = true"))
        assertTrue(host.contains("badgeAtToggle && badge != null"))
    }

    @Test
    fun `custom lyrics uses a compact single-row action bar in every layout`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val customLyrics = host
            .substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("val search = EditText")

        assertTrue(customLyrics.contains("embeddedCompactLyricsActionBar"))
        assertTrue(host.contains("\"添加\""))
        assertTrue(host.contains("\"TTML\""))
        assertTrue(host.contains("\"GitHub\""))
        assertTrue(host.contains("\"备份\""))
        assertTrue(!customLyrics.contains("embeddedIconActionButton("))
        assertTrue(customLyrics.contains("marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 5)"))
        assertTrue(host.contains("orientation = LinearLayout.HORIZONTAL"))
        assertTrue(host.contains("embeddedCompactLyricsActionItem"))
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
        assertTrue(host.contains("从 Lunabeat 导入"))
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
            VisibleSetting("手机液态玻璃底栏", "phoneLiquidGlassEnabled"),
            VisibleSetting("双向歌词模糊", "futureBlurEnabled"),
            VisibleSetting("CJK 长尾歌词动画", "cjkKaraokeAnimationEnabled"),
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

        assertFalse(activity.contains("平板禁用动态视频"))
        assertFalse(host.contains("平板禁用动态视频"))
        assertTrue(host.contains("平板横屏启用双栏，同时停用 Editorial Video"))
        assertTrue(host.contains("配置保存在 Apple Music 私有目录中"))
        assertFalse(host.contains("由嵌入式设置页直接管理"))
        assertFalse(host.contains("嵌入版不提供独立启动器图标"))
        assertTrue(host.contains("这是半成品功能，不接受反馈。"))
        assertTrue(host.contains("onEnableConfirmation"))
        assertTrue(activity.contains("这是半成品功能，不接受反馈。"))
        assertTrue(activity.contains("onEnableConfirmation"))

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
            "自动实时补全",
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
            host,
            "pageRefresh?.invoke()",
            "embedded custom-lyrics toggle refresh",
        )
        assertSourceContains(
            activity,
            "settings.automaticLyricsEnabled",
            "standalone automatic-lyrics toggle binding",
        )
        assertSourceContains(
            host,
            "settings.automaticLyricsEnabled",
            "embedded automatic-lyrics toggle binding",
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

        listOf("从 AMLL 导入", "从 Lunabeat 导入", "从 GitHub 导入").forEach { title ->
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

    @Test
    fun `embedded home removes the current-song status card`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val mainPage = host
            .substringAfter("private fun renderEmbeddedMainPage(")
            .substringBefore("private fun renderEmbeddedCustomLyricsPage(")

        assertTrue(!mainPage.contains("embeddedStatusCard(activity, song)"))
        assertTrue(!mainPage.contains("当前歌曲："))
    }

    @Test
    fun `embedded lyric editor follows the shared reference layout in both orientations`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val editor = host
            .substringAfter("private fun showLyricsEditor(")
            .substringBefore("/** Compatibility overload")

        listOf(
            "embeddedLyricsEditorActionRows",
            "导入 TTML",
            "获取 ID",
            "从 AMLL 导入",
            "从 Lunabeat 导入",
            "从 GitHub 导入",
            "当前来源：",
        ).forEach { marker ->
            assertTrue("missing lyrics-editor reference marker $marker", editor.contains(marker))
        }
        listOf(
            "embeddedLyricsEditorButton",
            "Typeface.MONOSPACE",
            "isVerticalScrollBarEnabled = true",
            "LinearLayout.HORIZONTAL",
            "text = \"|\"",
            "setAutoSizeTextTypeUniformWithConfiguration",
            "val compactRow = actions.size >= 3",
            "listOf(actions.take(2), actions.drop(2))",
            "compactLabel = \"AMLL 导入\"",
            "compactLabel = \"Lunabeat 导入\"",
            "compactLabel = \"GitHub 导入\"",
            "useCompactLabels = rows.size > 1 && rowIndex == rows.lastIndex",
            "LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.WRAP_CONTENT,\n                    dp(activity, 32),",
        ).forEach { marker ->
            assertTrue("missing shared lyrics-editor layout marker $marker", host.contains(marker))
        }
    }

    @Test
    fun `embedded lyric editor shares the rounded host-density dialog treatment`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val editor = host
            .substringAfter("private fun showLyricsEditor(")
            .substringBefore("/** Compatibility overload")

        assertTrue(editor.contains("val editorRoot = LinearLayout(activity).apply"))
        assertTrue(editor.contains("cornerRadius = embeddedCardCornerRadius(activity)"))
        assertTrue(editor.contains("clipToOutline = true"))
        assertTrue(editor.contains("textSize = embeddedTextSize(activity, 19f, 18f)"))
        assertTrue(editor.contains("textSize = embeddedTextSize(activity, 16f, 14f)"))
        assertTrue(editor.contains(".setView(editorRoot)"))
        assertTrue(editor.contains("setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))"))
        assertTrue(editor.contains("embeddedLyricsEditorDialogWidth(activity)"))
        assertFalse(editor.contains("displayMetrics.widthPixels"))
        assertFalse(editor.contains(".setTitle("))
        assertFalse(editor.contains(".setNegativeButton("))
    }

    @Test
    fun `custom lyric cards keep only the overflow menu actions`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val entryRow = host
            .substringAfter("private fun embeddedCustomLyricsEntryRow(")
            .substringBefore("private fun confirmEmbeddedLyricsDelete(")

        assertTrue(entryRow.contains("EmbeddedGlyphKind.MoreVertical"))
        assertTrue(entryRow.contains("showEmbeddedLyricsOverflowMenu"))
        assertTrue(!entryRow.contains("embeddedEntryActionButton("))
        assertTrue(!entryRow.contains("编辑歌词"))
        assertTrue(!entryRow.contains("confirmEmbeddedLyricsDelete(activity, group)"))
        assertTrue(entryRow.contains("minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 60 else 56)"))
        assertTrue(host.contains("menu.add(\"编辑\")"))
        assertTrue(host.contains("menu.add(\"删除\")"))
        assertTrue(host.contains("menu.add(\"复制 Apple Music ID\")"))
    }

    @Test
    fun `glyph drawable centers rectangular bounds`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        assertTrue(host.contains("val left = box.left + (box.width() - size) / 2f"))
        assertTrue(host.contains("val top = box.top + (box.height() - size) / 2f"))
        assertTrue(host.contains("val cy = top + size / 2f"))
    }
}
