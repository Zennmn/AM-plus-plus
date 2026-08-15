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
        assertTrue(activity.contains("store.settingsWithCustomLyrics(snapshot)"))
        assertTrue(activity.contains("featureCard(settings, writable)"))
        assertTrue(activity.contains("badge = \"WIP\""))
        assertTrue(activity.contains("LSPosed 配置提示"))
        assertTrue(activity.contains("store.saveSettings(store.settings().copy("))
        assertTrue(activity.contains("title = \"平板底栏补偿\""))
        assertTrue(activity.contains("summary = \"如果底栏显示异常开启该选项\""))
        assertTrue(activity.contains("navigationCompensationEnabled = enabled"))
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
        assertTrue(activity.contains("val backgroundExecutor: ExecutorService get() = settingsExecutor"))
        assertTrue(activity.contains("val settingsExecutor: ExecutorService = Executors.newSingleThreadExecutor()"))
        assertFalse(activity.contains("backgroundExecutor.shutdown()"))
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
    fun `keeps custom lyrics manual at playback time and online only at explicit import time`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val session = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/CustomLyricsReplacementSession.kt",
        )
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        assertTrue(activity.contains("从 AMLL 导入"))
        assertTrue(activity.contains("从网易云导入"))
        assertTrue(activity.contains("从 GitHub 导入"))
        assertTrue(activity.contains("同步 GitHub 源"))
        assertTrue(activity.contains("CustomLyricsManager(ModuleApplication.serviceSnapshot, store).syncFromGitHub"))
        assertTrue(activity.contains("CustomLyricsSyncLoadResult.Cancelled"))
        assertTrue(activity.contains("AtomicBoolean(false)"))
        assertTrue(activity.contains("不会在播放时联网识歌"))
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(target.contains("session.start()"))
        assertFalse(target.contains("HttpLyricTransport"))
        assertFalse(target.contains("AmLyricsClient"))
        assertFalse(session.contains("HttpLyricTransport"))
        assertFalse(session.contains("AmLyricsClient"))
        assertFalse(session.contains("config.settings()"))
        assertTrue(session.contains("files and native parsing are prepared off-hook"))
    }

    @Test
    fun `gets the current song id only through an explicit standalone settings request`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val requester = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/CurrentSongIdentityRequester.kt",
        )

        assertTrue(activity.contains("获取当前歌曲信息"))
        assertTrue(activity.contains("dialogActionButton(\"获取 ID\") { requestCurrentSongId(appleMusicId, displayName) }"))
        assertTrue(activity.contains("requestCurrentSongId(appleMusicId, displayName)"))
        assertTrue(activity.contains("appleMusicId.setText(currentSong.appleMusicId.toString())"))
        assertTrue(activity.contains("CustomLyricsIdParser.parse(appleMusicId.text.toString())"))
        assertTrue(activity.contains("existing?.appleMusicIds?.let(CustomLyricsIdParser::format)"))
        assertTrue(activity.contains("主 ID：\${group.primary.appleMusicId} · 共 \${group.entries.size} 个 ID"))
        assertTrue(activity.contains("formatCurrentSongDisplayName(currentSong.title, currentSong.artist)"))
        assertTrue(activity.contains("displayName.setText(it)"))
        assertTrue(activity.contains("val actionBar = LinearLayout(this).apply"))
        assertTrue(activity.contains("orientation = LinearLayout.HORIZONTAL"))
        assertTrue(activity.contains("dialogActionButton(\"导入 TTML\")"))
        assertTrue(activity.contains("dialogActionButton(\"取消\") { dialog.dismiss() }"))
        assertTrue(activity.contains("dialogActionButton(\"保存\") save@{"))
        assertTrue(activity.contains("LinearLayout.LayoutParams(0, dp(56), 1f)"))
        assertFalse(activity.contains("setAllowStacking"))
        assertFalse(activity.contains("dialog.getButton("))
        assertFalse(activity.contains("fontActionButton(\"获取当前歌曲 ID\""))
        assertTrue(activity.contains("未获取到当前歌曲信息，请先在 Apple Music 播放一首歌"))
        assertTrue(requester.contains("setPackage(ModuleConstants.TARGET_PACKAGE)"))
        assertTrue(requester.contains("ResultReceiver"))
        assertTrue(requester.contains("TIMEOUT_MILLIS"))
        assertFalse(requester.contains("SharedPreferences"))
        assertFalse(requester.contains("HttpLyricTransport"))
        assertFalse(requester.contains("AmLyricsClient"))
    }

    @Test
    fun `exposes catalog title correction and a native library refresh action`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/CurrentSongIdentityProtocol.kt",
        )
        val titleTarget = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicTitleCorrectionTarget.kt",
        )
        val titlePolicy = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/TitleCorrectionPolicy.kt",
        )
        val libraryTarget = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicLibraryRefreshTarget.kt",
        )
        val symbols = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt",
        )

        assertTrue(activity.contains("歌曲名显示修正"))
        assertTrue(activity.contains("titleCorrectionEnabled"))
        assertTrue(activity.contains("目标语言"))
        assertTrue(activity.contains("titleCorrectionTargetLanguage"))
        assertTrue(activity.contains("刷新资料库"))
        assertTrue(activity.contains("requestLibraryRefresh()"))
        assertTrue(protocol.contains("REQUEST_LIBRARY_REFRESH"))
        assertTrue(manifest.contains("REQUEST_LIBRARY_REFRESH"))
        assertTrue(titleTarget.contains("MediaEntityGetTitleMethod"))
        assertTrue(titleTarget.contains("MediaEntityGetAttributesMethod"))
        assertTrue(titleTarget.contains("MediaEntityToCollectionItemViewMethod"))
        assertTrue(titlePolicy.contains("catalog-title:"))
        assertTrue(titlePolicy.contains("catalog-schema"))
        assertTrue(titlePolicy.contains("allowReplace"))
        assertTrue(libraryTarget.contains("UserInitiatedPoll"))
        assertTrue(libraryTarget.contains("update.invoke(library, updateReason)"))
        assertTrue(libraryTarget.contains("RESULT_COMPLETED"))
        assertTrue(symbols.contains("com.apple.android.medialibrary.library.MediaLibrary"))
        assertTrue(symbols.contains("isMediaLibraryUpdateMethod"))
        assertTrue(symbols.contains("lookupAndRefreshCatalogItemsInLibrary"))
        assertTrue(symbols.contains("ConfigurationStoreStoreFrontLanguageMethod"))
        assertTrue(symbols.contains("MediaApiRepositoryGetEntitiesWithIdsMethod"))
    }

    @Test
    fun `target language presets match AMTool and stay reachable while correction is off`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("val tags = listOf(\"zh-CN\", \"zh-TW\", \"ja-JP\", \"en-US\", \"tr-TR\")"))
        assertFalse(activity.contains("\"ko-KR\""))
        assertFalse(activity.contains("\"de-DE\""))
        assertFalse(activity.contains("\"fr-FR\""))
        assertFalse(activity.contains("\"es-ES\""))
        assertFalse(activity.contains("系统语言"))
        assertFalse(activity.contains("coerceAtLeast"))

        val targetLanguageRegion = activity.substringAfter("title = \"目标语言\"")
            .substringBefore("title = \"刷新资料库\"")
        assertTrue(targetLanguageRegion.contains("enabled = writable"))
        assertFalse(targetLanguageRegion.contains("titleCorrectionEnabled"))
    }

    @Test
    fun `custom target language input rejects empty and invalid values`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("CatalogLanguagePolicy.isValid(raw)"))
        assertTrue(activity.contains("目标语言格式无效，例如 tr-TR"))
        assertTrue(activity.contains("空值或非法值无法保存"))
        assertFalse(activity.contains("留空表示使用 Apple Music 当前语言"))
    }

    @Test
    fun `library refresh shows an AMTool style cancellable progress dialog`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val requester = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/LibraryRefreshRequester.kt",
        )

        assertTrue(activity.contains("ProgressBar(this@SettingsActivity)"))
        assertTrue(activity.contains("正在刷新资料库，请稍候"))
        assertTrue(activity.contains("setNegativeButton(\"停止\")"))
        assertTrue(activity.contains("libraryRefreshRequester.cancel()"))
        assertTrue(activity.contains("LibraryRefreshProtocol.RESULT_CANCELLED"))
        assertTrue(activity.contains("已停止刷新资料库"))
        assertTrue(activity.contains("libraryRefreshDialog?.takeIf { it.isShowing }?.dismiss()"))
        assertTrue(activity.contains("setCanceledOnTouchOutside(false)"))
        assertTrue(requester.contains("CANCEL_ACTION"))
        assertTrue(requester.contains("RESULT_CANCELLED"))
        assertFalse(requester.contains("SharedPreferences"))
    }

    @Test
    fun `moves custom lyrics management to a saved secondary page`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("enum class SettingsPage"))
        assertTrue(activity.contains("STATE_SETTINGS_PAGE"))
        assertTrue(activity.contains("renderMainPage(settings, snapshot)"))
        assertTrue(activity.contains("renderCustomLyricsPage(settings, snapshot)"))
        assertTrue(activity.contains("customLyricsNavigationRow(settings.customLyricsManifest)"))
        assertTrue(activity.contains("showPage(SettingsPage.CUSTOM_LYRICS)"))
        assertTrue(activity.contains("customLyricsEnabled = enabled"))
        assertTrue(activity.contains("customLyricsCard(settings.customLyricsManifest"))
        assertTrue(activity.contains("override fun onBackPressed()"))
        assertTrue(activity.contains("showPage(SettingsPage.MAIN)"))
        assertTrue(activity.contains("settingsScroll.post { settingsScroll.scrollTo(0, 0) }"))

        val mainPage = activity.substringAfter("private fun renderMainPage(")
            .substringBefore("private fun renderCustomLyricsPage(")
        assertFalse(mainPage.contains("customLyricsCard("))
        assertFalse(mainPage.contains("customLyricsEnabled = enabled"))
    }

    @Test
    fun `main page count comes from the resolved v2 index, not the legacy manifest`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("已配置 \${manifest.entries.size} 首；更改后重开 Apple Music 生效"))
        val render = activity.substringAfter("private fun render(")
            .substringBefore("private fun renderMainPage(")
        assertTrue(render.contains("val settings = store.settingsWithCustomLyrics(snapshot)"))
        assertFalse(render.contains("currentPage == SettingsPage.CUSTOM_LYRICS"))
    }

    @Test
    fun `backs up and merge restores custom lyrics through transient saf documents`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("fontActionButton(\"备份歌词\""))
        assertTrue(activity.contains("fontActionButton(\"恢复备份\""))
        assertTrue(activity.contains("Intent.ACTION_CREATE_DOCUMENT"))
        assertTrue(activity.contains("CUSTOM_LYRICS_BACKUP_CREATE_REQUEST_CODE"))
        assertTrue(activity.contains("CUSTOM_LYRICS_BACKUP_RESTORE_REQUEST_CODE"))
        assertTrue(activity.contains("application/x-zip-compressed"))
        assertTrue(activity.contains("application/octet-stream"))
        assertTrue(activity.contains("CustomLyricsManager(snapshot, store).backup(output)"))
        assertTrue(activity.contains("CustomLyricsManager(snapshot, store).restore(input, policy)"))
        assertTrue(activity.contains("showTargetLanguagePicker()"))
        assertTrue(activity.contains("setNegativeButton(\"取消\", null)"))
        assertTrue(activity.contains("setNeutralButton(\"不覆盖\")"))
        assertTrue(activity.contains("setPositiveButton(\"覆盖\")"))
        assertTrue(activity.contains("CustomLyricsRestorePolicy.OVERWRITE"))
        assertTrue(activity.contains("CustomLyricsRestorePolicy.KEEP_EXISTING"))
        assertTrue(activity.contains("restoreCustomLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)"))
        assertTrue(activity.contains("restoreCustomLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)"))
        assertTrue(activity.contains("覆盖：冲突歌词使用备份版本；不覆盖：冲突歌词保留当前版本。"))
        assertTrue(activity.contains("backgroundExecutor.execute"))
        assertTrue(activity.contains("contentResolver.delete(uri, null, null)"))
        assertFalse(activity.contains("takePersistableUriPermission"))
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
