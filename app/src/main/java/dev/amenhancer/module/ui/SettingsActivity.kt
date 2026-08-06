package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.R
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.font.SafFontImporter
import dev.amenhancer.module.hook.AmLyricsClient
import dev.amenhancer.module.hook.AmllTtmlClient
import dev.amenhancer.module.hook.HttpLyricTransport
import dev.amenhancer.module.hook.NeteaseLyricClient
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsBackupResult
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.lyrics.CustomLyricsInspection
import dev.amenhancer.module.lyrics.CustomLyricsManager
import dev.amenhancer.module.lyrics.CustomLyricsMutationResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.lyrics.CustomLyricsRestoreResult
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object BlurRadiusSeekBarPersistencePolicy {
    fun shouldPersistProgressChange(fromUser: Boolean, trackingTouch: Boolean): Boolean =
        fromUser && !trackingTouch
}

private enum class SettingsPage {
    MAIN,
    CUSTOM_LYRICS,
}

class SettingsActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var launcherIconController: LauncherIconController
    private lateinit var content: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var palette: Palette
    private lateinit var currentSongIdentityRequester: CurrentSongIdentityRequester
    private lateinit var topBarTitle: TextView
    private lateinit var topBarBackButton: ImageView
    private val backgroundExecutor: ExecutorService get() = settingsExecutor
    private var pendingCustomTtmlImport: ((String) -> Unit)? = null
    private var awaitingCustomTtmlPickerResult = false
    private var currentPage = SettingsPage.MAIN
    private val customLyricsListState = CustomLyricsListState()
    private var customLyricsSearchQuery = ""
    private var customLyricsListRegion: LinearLayout? = null

    private val serviceListener: (XposedServiceSnapshot) -> Unit = { snapshot ->
        runOnUiThread { if (::content.isInitialized) render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        launcherIconController = LauncherIconController(this)
        currentSongIdentityRequester = CurrentSongIdentityRequester(this)
        palette = Palette.resolve(this)
        awaitingCustomTtmlPickerResult = savedInstanceState?.getBoolean(
            STATE_AWAITING_CUSTOM_TTML_PICKER,
            false,
        ) == true
        currentPage = savedInstanceState?.getString(STATE_SETTINGS_PAGE)
            ?.let { saved -> runCatching { SettingsPage.valueOf(saved) }.getOrNull() }
            ?: SettingsPage.MAIN
        configureSystemBars()
        setContentView(buildScreen().also(::applySystemBarInsets))
        render()
    }

    override fun onResume() {
        super.onResume()
        ModuleApplication.addServiceListener(serviceListener)
        if (::content.isInitialized) render()
    }

    override fun onPause() {
        ModuleApplication.removeServiceListener(serviceListener)
        super.onPause()
    }

    override fun onDestroy() {
        if (::currentSongIdentityRequester.isInitialized) currentSongIdentityRequester.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_CUSTOM_TTML_PICKER, awaitingCustomTtmlPickerResult)
        outState.putString(STATE_SETTINGS_PAGE, currentPage.name)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentPage == SettingsPage.CUSTOM_LYRICS) {
            showPage(SettingsPage.MAIN)
        } else {
            super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FONT_PICKER_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) data?.data?.let(::importFont)
            }
            CUSTOM_TTML_PICKER_REQUEST_CODE -> {
                val onImported = pendingCustomTtmlImport
                val restoreEditor = onImported == null && awaitingCustomTtmlPickerResult
                pendingCustomTtmlImport = null
                awaitingCustomTtmlPickerResult = false
                if (resultCode == RESULT_OK) data?.data?.let { uri ->
                    importCustomTtml(uri) { ttml ->
                        if (onImported != null) onImported(ttml) else if (restoreEditor) {
                            showCustomLyricsEditor(initialTtml = ttml)
                        }
                    }
                }
            }
            CUSTOM_LYRICS_BACKUP_CREATE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) data?.data?.let(::backupCustomLyrics)
            }
            CUSTOM_LYRICS_BACKUP_RESTORE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) data?.data?.let(::confirmRestoreCustomLyrics)
            }
        }
    }

    private fun configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        var flags = window.decorView.systemUiVisibility
        flags = if (palette.isDark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        root.setOnApplyWindowInsetsListener { view, insets ->
            val statusBars = insets.getInsets(WindowInsets.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsets.Type.navigationBars())
            view.setPadding(0, statusBars.top, 0, navigationBars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildScreen(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        addView(
            buildTopBar(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )
        addView(divider())
        settingsScroll = ScrollView(this@SettingsActivity).apply {
            isFillViewport = true
            clipToPadding = false
            content = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(32))
            }
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(settingsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildTopBar(): View = FrameLayout(this).apply {
        setPadding(dp(12), 0, dp(24), 0)
        topBarBackButton = ImageView(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rippleDrawable()
            setOnClickListener { showPage(SettingsPage.MAIN) }
        }
        addView(
            topBarBackButton,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        topBarTitle = TextView(this@SettingsActivity).apply {
            textSize = 20f
            setTextColor(palette.onSurface)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        addView(topBarTitle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
        ).apply { marginStart = dp(12) })
    }

    private fun render(snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot) {
        content.removeAllViews()
        val settings = store.settingsWithCustomLyrics(snapshot)
        updateTopBar()
        when (currentPage) {
            SettingsPage.MAIN -> renderMainPage(settings, snapshot)
            SettingsPage.CUSTOM_LYRICS -> renderCustomLyricsPage(settings, snapshot)
        }
    }

    private fun renderMainPage(settings: ModuleSettings, snapshot: XposedServiceSnapshot) {
        val writable = snapshot.isRemoteAvailable

        content.addView(statusCard(snapshot))
        content.addView(spacer(20))
        content.addView(featureCard(settings, writable))
        content.addView(spacer(24))
        content.addView(fontCard(settings.fontManifest, snapshot.isRemoteFileAvailable))
        content.addView(spacer(24))
        content.addView(sectionLabel("应用"))
        content.addView(spacer(10))
        content.addView(appCard())
        content.addView(spacer(24))
        content.addView(sectionLabel("帮助"))
        content.addView(spacer(10))
        content.addView(helpRow())
    }

    private fun renderCustomLyricsPage(settings: ModuleSettings, snapshot: XposedServiceSnapshot) {
        customLyricsListState.update(
            settings.customLyricsManifest.entries,
            customLyricsSearchQuery,
        )
        content.addView(customLyricsSettingsCard(settings, snapshot.isRemoteAvailable))
        content.addView(spacer(20))
        content.addView(
            customLyricsCard(settings.customLyricsManifest, snapshot.isRemoteFileAvailable),
        )
    }

    private fun showPage(page: SettingsPage) {
        if (currentPage == page) return
        if (page == SettingsPage.MAIN) currentSongIdentityRequester.cancel()
        currentPage = page
        render()
        settingsScroll.post { settingsScroll.scrollTo(0, 0) }
    }

    private fun updateTopBar() {
        val customLyrics = currentPage == SettingsPage.CUSTOM_LYRICS
        topBarTitle.text = if (customLyrics) "自定义歌词" else "AM++"
        topBarBackButton.visibility = if (customLyrics) View.VISIBLE else View.GONE
        (topBarTitle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.marginStart = dp(if (customLyrics) 52 else 12)
            topBarTitle.layoutParams = params
        }
    }

    private fun statusCard(snapshot: XposedServiceSnapshot): View = LinearLayout(this).apply {
        val writable = snapshot.isRemoteAvailable
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(
            color = if (writable) palette.primaryContainer else palette.disabledContainer,
            radiusDp = 18,
        )

        addView(iconBubble(R.drawable.ic_status_check, writable))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = if (writable) snapshot.status else "配置暂时只读"
                textSize = 17f
                setTextColor(palette.onSurface)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            if (!writable) {
                addView(TextView(this@SettingsActivity).apply {
                    text = snapshot.status
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun featureCard(settings: ModuleSettings, writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(palette.surface, radiusDp = 20, strokeColor = palette.outline)
            elevation = dp(2).toFloat()
            clipToOutline = true

            addView(sectionLabel("功能").apply {
                setPadding(dp(16), dp(18), dp(16), dp(10))
            })
            addView(settingRow(
                title = "平板双栏播放器",
                summary = "仅在 Apple Music 判定为平板且横屏时启用",
                checked = settings.dualPaneEnabled,
                enabled = writable,
            ) { enabled ->
                store.saveSettings(store.settings().copy(dualPaneEnabled = enabled))
            })
            addView(insetDivider())
            addView(settingRow(
                title = "平板底栏补偿",
                summary = "如果底栏显示异常开启该选项",
                checked = settings.navigationCompensationEnabled,
                enabled = writable,
            ) { enabled ->
                store.saveSettings(store.settings().copy(navigationCompensationEnabled = enabled))
            })
            addView(insetDivider())
            addView(settingRow(
                title = "平板禁用动态视频",
                summary = "平板横屏时禁用 Editorial Video；普通音乐视频不受影响",
                checked = settings.disableEditorialVideoOnTablet,
                enabled = writable,
            ) {
                store.saveSettings(store.settings().copy(disableEditorialVideoOnTablet = it))
            })
            addView(insetDivider())
            addView(settingRow(
                title = "手机液态玻璃底栏",
                summary = "仅手机启用 · 更改后需强制停止并重开 Apple Music",
                checked = settings.phoneLiquidGlassEnabled,
                enabled = writable,
                badge = "WIP",
            ) {
                store.saveSettings(store.settings().copy(phoneLiquidGlassEnabled = it))
            })
            addView(insetDivider())
            addView(settingRow(
                title = "双向歌词模糊",
                summary = "Android 12 及以上 · 手动滚动停止 1 秒后恢复",
                checked = settings.futureBlurEnabled,
                enabled = writable,
            ) { enabled ->
                store.saveSettings(store.settings().copy(futureBlurEnabled = enabled))
            })
            addView(insetDivider())
            addView(blurRadiusOffsetRow(
                offsetPx = settings.lyricBlurRadiusOffsetPx,
                enabled = writable,
            ) { offsetPx ->
                store.saveSettings(store.settings().copy(lyricBlurRadiusOffsetPx = offsetPx))
            })
            addView(insetDivider())
            addView(customLyricsNavigationRow(settings.customLyricsManifest))
        }

    private fun customLyricsNavigationRow(manifest: CustomLyricsManifest): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            isClickable = true
            isFocusable = true
            contentDescription = "自定义歌词"
            background = rippleDrawable()
            setPadding(dp(16), dp(12), dp(14), dp(12))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "自定义歌词"
                    textSize = 17f
                    setTextColor(palette.onSurface)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = if (manifest.entries.isEmpty()) {
                        "添加和管理 Apple Music ID 歌词映射"
                    } else {
                        "已配置 ${manifest.entries.size} 首歌词"
                    }
                    textSize = 13.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            setOnClickListener { showPage(SettingsPage.CUSTOM_LYRICS) }
        }

    private fun customLyricsSettingsCard(settings: ModuleSettings, writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(palette.surface, radiusDp = 20, strokeColor = palette.outline)
            elevation = dp(2).toFloat()
            clipToOutline = true
            addView(settingRow(
                title = "自定义歌词替换",
                summary = "按 Apple Music ID 注入 · 更改后重开 Apple Music 生效",
                checked = settings.customLyricsEnabled,
                enabled = writable,
            ) { enabled ->
                store.saveSettings(store.settings().copy(customLyricsEnabled = enabled))
            })
        }

    private fun fontCard(manifest: LyricsFontManifest, writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(palette.surface, radiusDp = 20, strokeColor = palette.outline)
            elevation = dp(2).toFloat()
            clipToOutline = true

            addView(sectionLabel("歌词字体").apply {
                setPadding(dp(16), dp(18), dp(16), dp(8))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = if (manifest.enabled) manifest.displayName else "原字体"
                textSize = 17f
                setTextColor(palette.onSurface)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(16), dp(4), dp(16), 0)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = when {
                    !writable -> "需要 libxposed API 102 remote file 服务"
                    manifest.enabled -> "仅覆盖播放器歌词 · 重开 Apple Music 后生效"
                    else -> "导入 TTF/OTF · 重开 Apple Music 后生效"
                }
                textSize = 13.5f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(16), dp(4), dp(16), dp(12))
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(0), dp(12), dp(12))
                addView(fontActionButton("选择字体", writable) { chooseFont() },
                    LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(spacer(8), LinearLayout.LayoutParams(dp(8), dp(1)))
                addView(
                    fontActionButton("恢复原字体", writable && manifest.enabled) { restoreFont() },
                    LinearLayout.LayoutParams(0, dp(48), 1f),
                )
            })
        }

    private fun fontActionButton(
        label: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        isClickable = enabled
        alpha = if (enabled) 1f else 0.58f
        minHeight = dp(48)
        setTextColor(if (enabled) palette.primary else palette.onSurfaceVariant)
        background = rippleDrawable(
            roundedDrawable(
                color = if (enabled) palette.primaryContainer else palette.disabledContainer,
                radiusDp = 14,
            ),
        )
        setOnClickListener { if (enabled) onClick() }
    }

    private fun chooseFont() {
        if (!ModuleApplication.serviceSnapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "font/ttf",
                    "font/otf",
                    "application/x-font-ttf",
                    "application/x-font-opentype",
                    "application/vnd.ms-opentype",
                ),
            )
        }, FONT_PICKER_REQUEST_CODE)
    }

    private fun importFont(uri: android.net.Uri) {
        val snapshot = ModuleApplication.serviceSnapshot
        if (!snapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        backgroundExecutor.execute {
            val result = SafFontImporter(applicationContext, snapshot, store).import(uri)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (::content.isInitialized) render()
                    when (result) {
                        is FontImportResult.Imported -> toast("字体已导入，重开 Apple Music 后生效")
                        is FontImportResult.Failed -> toast(result.message)
                    }
                }
            }
        }
    }

    private fun restoreFont() {
        val snapshot = ModuleApplication.serviceSnapshot
        if (!snapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        val oldManifest = store.settings(snapshot).fontManifest
        backgroundExecutor.execute {
            val cleared = store.saveFontManifest(LyricsFontManifest.disabled(), snapshot)
            if (cleared && oldManifest.enabled) snapshot.deleteRemoteFile(oldManifest.fileId)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (::content.isInitialized) render()
                    toast(if (cleared) "已恢复原字体，重开 Apple Music 后生效" else "恢复原字体失败")
                }
            }
        }
    }

    private fun customLyricsCard(manifest: CustomLyricsManifest, writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(palette.surface, radiusDp = 20, strokeColor = palette.outline)
            elevation = dp(2).toFloat()
            clipToOutline = true

            addView(sectionLabel("自定义歌词").apply {
                setPadding(dp(16), dp(18), dp(16), dp(8))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = when {
                    !writable -> "需要 libxposed API 102 remote file 服务"
                    manifest.entries.isEmpty() -> "按 Apple Music ID 手动添加 TTML；不会在播放时联网识歌"
                    else -> "已配置 ${manifest.entries.size} 首；更改后重开 Apple Music 生效"
                }
                textSize = 13.5f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(16), dp(4), dp(16), dp(12))
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(12))
                addView(
                    fontActionButton("添加歌词", writable) { showCustomLyricsEditor() },
                    LinearLayout.LayoutParams(0, dp(48), 1f),
                )
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(12))
                addView(
                    fontActionButton("备份歌词", writable) { chooseCustomLyricsBackupDestination() },
                    LinearLayout.LayoutParams(0, dp(48), 1f),
                )
                addView(spacer(8), LinearLayout.LayoutParams(dp(8), dp(1)))
                addView(
                    fontActionButton("恢复备份", writable) { chooseCustomLyricsBackupRestore() },
                    LinearLayout.LayoutParams(0, dp(48), 1f),
                )
            })
            if (manifest.entries.isNotEmpty()) {
                addView(
                    customLyricsSearchInput(writable),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(44),
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                        bottomMargin = dp(6)
                    },
                )
                addView(customLyricsEntriesRegion(writable))
            }
        }

    private fun customLyricsSearchInput(writable: Boolean): View =
        EditText(this).apply {
            hint = "搜索名称或 Apple Music ID"
            textSize = 14f
            setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceVariant)
            isSingleLine = true
            setText(customLyricsSearchQuery)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(palette.background, radiusDp = 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    customLyricsSearchQuery = s?.toString().orEmpty()
                    customLyricsListState.setQuery(customLyricsSearchQuery)
                    refreshCustomLyricsEntries(writable)
                }
            })
        }

    private fun customLyricsEntriesRegion(writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            customLyricsListRegion = this
            refreshCustomLyricsEntries(writable)
        }

    private fun refreshCustomLyricsEntries(writable: Boolean) {
        val region = customLyricsListRegion ?: return
        region.removeAllViews()
        val state = customLyricsListState
        if (state.totalCount == 0) {
            region.addView(TextView(this).apply {
                text = "没有匹配的歌词"
                textSize = 13.5f
                setTextColor(palette.onSurfaceVariant)
                setPadding(dp(16), dp(8), dp(16), dp(12))
            })
            return
        }
        state.visibleEntries.forEach { entry ->
            region.addView(customLyricsEntryRow(entry, writable))
            region.addView(insetDivider())
        }
        region.addView(TextView(this).apply {
            text = "已显示 ${state.visibleCount} / 共 ${state.totalCount} 首"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(dp(16), dp(8), dp(16), dp(12))
        })
        if (state.hasMore) {
            region.addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), dp(12))
                addView(
                    fontActionButton("加载更多", true) {
                        customLyricsListState.loadMore()
                        refreshCustomLyricsEntries(writable)
                    },
                    LinearLayout.LayoutParams(0, dp(48), 1f),
                )
            })
        }
    }

    private fun chooseCustomLyricsBackupDestination() {
        if (!ModuleApplication.serviceSnapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = BACKUP_MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, "AM++-custom-lyrics-backup.zip")
        }, CUSTOM_LYRICS_BACKUP_CREATE_REQUEST_CODE)
    }

    private fun chooseCustomLyricsBackupRestore() {
        if (!ModuleApplication.serviceSnapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    BACKUP_MIME_TYPE,
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
        }, CUSTOM_LYRICS_BACKUP_RESTORE_REQUEST_CODE)
    }

    private fun backupCustomLyrics(uri: android.net.Uri) {
        val snapshot = ModuleApplication.serviceSnapshot
        backgroundExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    CustomLyricsManager(snapshot, store).backup(output)
                } ?: CustomLyricsBackupResult.Failed("无法创建备份文件")
            }.getOrElse { CustomLyricsBackupResult.Failed("写入备份失败") }
            if (result is CustomLyricsBackupResult.Failed) {
                runCatching { contentResolver.delete(uri, null, null) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CustomLyricsBackupResult.Done -> toast("已备份 ${result.entryCount} 首歌词")
                    is CustomLyricsBackupResult.Failed -> toast(result.message)
                }
            }
        }
    }

    private fun confirmRestoreCustomLyrics(uri: android.net.Uri) {
        AlertDialog.Builder(this)
            .setTitle("恢复歌词备份")
            .setMessage("覆盖：冲突歌词使用备份版本；不覆盖：冲突歌词保留当前版本。")
            .setNegativeButton("取消", null)
            .setNeutralButton("不覆盖") { _, _ ->
                restoreCustomLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
            }
            .setPositiveButton("覆盖") { _, _ ->
                restoreCustomLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
            }
            .show()
    }

    private fun restoreCustomLyrics(
        uri: android.net.Uri,
        policy: CustomLyricsRestorePolicy,
    ) {
        val snapshot = ModuleApplication.serviceSnapshot
        backgroundExecutor.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    CustomLyricsManager(snapshot, store).restore(input, policy)
                } ?: CustomLyricsRestoreResult.Failed("无法读取备份文件")
            }.getOrElse { CustomLyricsRestoreResult.Failed("读取备份失败") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CustomLyricsRestoreResult.Restored -> {
                        render()
                        toast("恢复完成，当前共 ${result.manifest.entries.size} 首歌词")
                    }
                    is CustomLyricsRestoreResult.Failed -> toast(result.message)
                }
            }
        }
    }

    private fun requestCurrentSongId(appleMusicId: EditText, displayName: EditText) {
        if (!currentSongIdentityRequester.request { currentSong ->
                if (currentSong == null) {
                    toast("未获取到当前歌曲信息，请先在 Apple Music 播放一首歌")
                    return@request
                }
                appleMusicId.setText(currentSong.appleMusicId.toString())
                appleMusicId.setSelection(appleMusicId.length())
                formatCurrentSongDisplayName(currentSong.title, currentSong.artist)?.let {
                    displayName.setText(it)
                    displayName.setSelection(displayName.length())
                }
                toast("已获取当前歌曲信息")
            }
        ) {
            toast("正在获取当前歌曲信息")
            return
        }
        toast("正在获取当前歌曲信息…")
    }

    private fun formatCurrentSongDisplayName(title: String?, artist: String?): String? = listOfNotNull(
        title?.takeIf(String::isNotBlank),
        artist?.takeIf(String::isNotBlank),
    ).joinToString(" - ").takeIf(String::isNotBlank)

    private fun customLyricsEntryRow(entry: CustomLyricsEntry, writable: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            alpha = if (writable) 1f else 0.58f
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@SettingsActivity).apply {
                        text = entry.displayName
                        textSize = 16f
                        setTextColor(palette.onSurface)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    addView(TextView(this@SettingsActivity).apply {
                        text = "${entry.appleMusicId} · ${customLyricsSourceName(entry.source)}"
                        textSize = 13f
                        setTextColor(palette.onSurfaceVariant)
                        setPadding(0, dp(3), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(this@SettingsActivity).apply {
                    isChecked = entry.enabled
                    isEnabled = writable
                    contentDescription = "${entry.displayName} 自定义歌词开关"
                    thumbTintList = switchThumbColors()
                    trackTintList = switchTrackColors()
                    setOnCheckedChangeListener { _, checked ->
                        setCustomLyricsEnabled(entry.appleMusicId, checked)
                    }
                }, LinearLayout.LayoutParams(dp(64), dp(48)))
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(
                    fontActionButton("编辑", writable) { showCustomLyricsEditor(entry) },
                    LinearLayout.LayoutParams(0, dp(44), 1f),
                )
                addView(spacer(8), LinearLayout.LayoutParams(dp(8), dp(1)))
                addView(
                    fontActionButton("删除", writable) { confirmDeleteCustomLyrics(entry) },
                    LinearLayout.LayoutParams(0, dp(44), 1f),
                )
            })
        }

    private fun showCustomLyricsEditor(
        existing: CustomLyricsEntry? = null,
        initialTtml: String = "",
    ) {
        var source = existing?.source ?: CustomLyricsSources.MANUAL
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val appleMusicId = lyricEditorInput(
            hint = "Apple Music ID",
            initial = existing?.appleMusicId?.toString().orEmpty(),
            numeric = true,
        )
        val displayName = lyricEditorInput(
            hint = "显示名称（可选）",
            initial = existing?.displayName.orEmpty(),
        )
        val neteaseId = lyricEditorInput(hint = "网易云歌曲 ID（仅网易云导入时需要）", numeric = true)
        val ttml = lyricEditorInput(
            hint = "TTML 内容",
            initial = initialTtml,
            multiLine = true,
        )
        val sourceLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(8), 0, dp(8))
        }
        fun updateSourceLabel() {
            sourceLabel.text = "当前来源：${customLyricsSourceName(source)}"
        }
        updateSourceLabel()
        form.addView(appleMusicId)
        form.addView(displayName)
        form.addView(neteaseId)
        form.addView(sourceLabel)
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                fontActionButton("从 AMLL 导入", true) {
                    importFromAmll(appleMusicId, ttml) { importedSource ->
                        source = importedSource
                        updateSourceLabel()
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            addView(spacer(8), LinearLayout.LayoutParams(dp(8), dp(1)))
            addView(
                fontActionButton("从网易云导入", true) {
                    importFromNetease(neteaseId, displayName, ttml) { importedSource ->
                        source = importedSource
                        updateSourceLabel()
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            addView(spacer(8), LinearLayout.LayoutParams(dp(8), dp(1)))
            addView(
                fontActionButton("从 GitHub 导入", true) {
                    importFromAmLyrics(appleMusicId, ttml) { importedSource ->
                        source = importedSource
                        updateSourceLabel()
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        })
        form.addView(ttml, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()
            addView(
                ScrollView(this@SettingsActivity).apply { addView(form) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        lateinit var dialog: AlertDialog
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dialogActionButton("导入 TTML") {
                chooseCustomTtml { imported ->
                    ttml.setText(imported)
                    source = CustomLyricsSources.MANUAL
                    updateSourceLabel()
                }
            })
            addView(dialogActionButton("获取 ID") { requestCurrentSongId(appleMusicId, displayName) })
            addView(dialogActionButton("取消") { dialog.dismiss() })
            addView(dialogActionButton("保存") save@{
                val id = parsePositiveId(appleMusicId.text.toString())
                if (id == null) {
                    appleMusicId.error = "请输入正整数 Apple Music ID"
                    return@save
                }
                val rawTtml = ttml.text.toString()
                if (rawTtml.isBlank()) {
                    ttml.error = "请输入或导入 TTML"
                    return@save
                }
                saveCustomLyrics(
                    draft = CustomLyricsDraft(
                        appleMusicId = id,
                        displayName = displayName.text.toString(),
                        ttml = rawTtml,
                        source = source,
                        enabled = existing?.enabled ?: true,
                    ),
                    replacingAppleMusicId = existing?.appleMusicId,
                    dialog = dialog,
                )
            })
        }
        dialogContent.addView(
            actionBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )
        dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加自定义歌词" else "编辑自定义歌词")
            .setView(dialogContent)
            .create()
        dialog.setOnShowListener {
            existing?.let { entry -> loadExistingCustomTtml(entry, ttml) }
        }
        dialog.setOnDismissListener { currentSongIdentityRequester.cancel() }
        dialog.show()
    }

    private fun LinearLayout.dialogActionButton(label: String, onClick: () -> Unit): Button =
        Button(this@SettingsActivity).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minimumWidth = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(palette.primary)
            background = rippleDrawable(roundedDrawable(Color.TRANSPARENT, radiusDp = 0))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }

    private fun lyricEditorInput(
        hint: String,
        initial: String = "",
        numeric: Boolean = false,
        multiLine: Boolean = false,
    ): EditText = EditText(this).apply {
        this.hint = hint
        setText(initial)
        textSize = 15f
        setTextColor(palette.onSurface)
        setHintTextColor(palette.onSurfaceVariant)
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiLine) {
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
        } else {
            isSingleLine = true
        }
    }

    private fun onlineLyricsImporter(): CustomLyricsOnlineImporter = CustomLyricsOnlineImporter(
        fetchAmll = AmllTtmlClient(HttpLyricTransport())::fetch,
        fetchAmLyrics = AmLyricsClient(HttpLyricTransport())::fetch,
        fetchNeteaseYrc = NeteaseLyricClient(HttpLyricTransport())::fetchYrc,
    )

    private fun importFromAmll(
        appleMusicIdInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val appleMusicId = parsePositiveId(appleMusicIdInput.text.toString())
        if (appleMusicId == null) {
            appleMusicIdInput.error = "请输入正整数 Apple Music ID"
            return
        }
        backgroundExecutor.execute {
            val result = onlineLyricsImporter().importAmll(appleMusicId)
            showOnlineImportResult(result, ttmlInput, onImported)
        }
    }

    private fun importFromAmLyrics(
        appleMusicIdInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val appleMusicId = parsePositiveId(appleMusicIdInput.text.toString())
        if (appleMusicId == null) {
            appleMusicIdInput.error = "请输入正整数 Apple Music ID"
            return
        }
        backgroundExecutor.execute {
            val result = onlineLyricsImporter().importAmLyrics(appleMusicId)
            showOnlineImportResult(result, ttmlInput, onImported)
        }
    }

    private fun importFromNetease(
        neteaseIdInput: EditText,
        displayNameInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val neteaseSongId = parsePositiveId(neteaseIdInput.text.toString())
        if (neteaseSongId == null) {
            neteaseIdInput.error = "请输入正整数网易云歌曲 ID"
            return
        }
        val displayName = displayNameInput.text.toString()
        backgroundExecutor.execute {
            val result = onlineLyricsImporter().importNetease(
                neteaseSongId,
                displayName,
            )
            showOnlineImportResult(result, ttmlInput, onImported)
        }
    }

    private fun showOnlineImportResult(
        result: CustomLyricsOnlineImportResult,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (result) {
                is CustomLyricsOnlineImportResult.Imported -> {
                    ttmlInput.setText(result.ttml)
                    onImported(result.source)
                    toast("已导入 ${customLyricsSourceName(result.source)} 歌词，请确认后保存")
                }
                is CustomLyricsOnlineImportResult.Failed -> toast(result.message)
            }
        }
    }

    private fun chooseCustomTtml(onImported: (String) -> Unit) {
        pendingCustomTtmlImport = onImported
        awaitingCustomTtmlPickerResult = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/xml"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
            )
        }, CUSTOM_TTML_PICKER_REQUEST_CODE)
    }

    private fun importCustomTtml(uri: android.net.Uri, onImported: (String) -> Unit) {
        backgroundExecutor.execute {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use(CustomLyricsFilePolicy::readBounded)
                    ?: return@runCatching null
                bytes.toString(Charsets.UTF_8).takeIf {
                    CustomLyricsFilePolicy.inspect(it) is CustomLyricsInspection.Accepted
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    toast("所选文件不是有效且不超过 512 KiB 的 TTML")
                } else {
                    onImported(result)
                    toast("TTML 已导入，请确认后保存")
                }
            }
        }
    }

    private fun loadExistingCustomTtml(entry: CustomLyricsEntry, ttmlInput: EditText) {
        val snapshot = ModuleApplication.serviceSnapshot
        if (!snapshot.isRemoteFileAvailable) return
        backgroundExecutor.execute {
            val reader = CustomLyricsFileReader { fileId ->
                snapshot.openRemoteFile(fileId)?.let { descriptor ->
                    runCatching {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(
                            CustomLyricsFilePolicy::readBounded,
                        )
                    }.getOrNull()
                }
            }
            val ttml = reader.read(entry)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ttml == null) {
                    toast("无法读取已保存的 TTML，请重新导入后保存")
                } else if (ttmlInput.text.isNullOrBlank()) {
                    ttmlInput.setText(ttml)
                }
            }
        }
    }

    private fun saveCustomLyrics(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long?,
        dialog: AlertDialog,
    ) {
        val snapshot = ModuleApplication.serviceSnapshot
        if (!snapshot.isRemoteFileAvailable) {
            toast("libxposed remote file 服务不可用")
            return
        }
        backgroundExecutor.execute {
            val result = CustomLyricsManager(snapshot, store).save(draft, replacingAppleMusicId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CustomLyricsSaveResult.Saved -> {
                        dialog.dismiss()
                        render()
                        toast("歌词映射已保存，重开 Apple Music 后生效")
                    }
                    is CustomLyricsSaveResult.Failed -> toast(result.message)
                }
            }
        }
    }

    private fun setCustomLyricsEnabled(appleMusicId: Long, enabled: Boolean) {
        val snapshot = ModuleApplication.serviceSnapshot
        backgroundExecutor.execute {
            val result = CustomLyricsManager(snapshot, store).setEnabled(appleMusicId, enabled)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render()
                if (result is CustomLyricsMutationResult.Failed) toast(result.message)
            }
        }
    }

    private fun confirmDeleteCustomLyrics(entry: CustomLyricsEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除自定义歌词")
            .setMessage("删除“${entry.displayName}”的 TTML 映射？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val snapshot = ModuleApplication.serviceSnapshot
                backgroundExecutor.execute {
                    val result = CustomLyricsManager(snapshot, store).delete(entry.appleMusicId)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        render()
                        when (result) {
                            is CustomLyricsMutationResult.Updated -> toast("已删除歌词映射")
                            is CustomLyricsMutationResult.Failed -> toast(result.message)
                        }
                    }
                }
            }
            .show()
    }

    private fun parsePositiveId(value: String): Long? = value.trim().toLongOrNull()?.takeIf { it > 0L }

    private fun customLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.NETEASE -> "网易云 YRC"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        else -> "手动 TTML"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun appCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(palette.surface, radiusDp = 20, strokeColor = palette.outline)
        elevation = dp(2).toFloat()
        clipToOutline = true

        addView(settingRow(
            title = "隐藏启动器图标",
            summary = "隐藏后可从 LSPosed 模块详情重新打开设置",
            checked = launcherIconController.isHidden(),
            enabled = true,
        ) { hidden ->
            launcherIconController.setHidden(hidden)
        })
    }

    private fun settingRow(
        title: String,
        summary: String,
        checked: Boolean,
        enabled: Boolean,
        badge: String? = null,
        onChanged: (Boolean) -> Unit,
    ): View {
        val switch = Switch(this).apply {
            isChecked = checked
            isEnabled = enabled
            showText = false
            contentDescription = title
            thumbTintList = switchThumbColors()
            trackTintList = switchTrackColors()
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.58f
            background = rippleDrawable()
            setPadding(dp(16), dp(12), dp(10), dp(12))

            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@SettingsActivity).apply {
                        text = title
                        textSize = 17f
                        setTextColor(palette.onSurface)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    badge?.let { addView(badge(it)) }
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = summary
                    textSize = 13.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch, LinearLayout.LayoutParams(dp(64), dp(48)))
            setOnClickListener { switch.isChecked = !switch.isChecked }
        }
    }

    private fun blurRadiusOffsetRow(
        offsetPx: Int,
        enabled: Boolean,
        onChanged: (Int) -> Unit,
    ): View {
        val safeOffset = offsetPx.coerceIn(
            ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
        )
        val valueLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(palette.primary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text = formatBlurRadiusOffset(safeOffset)
        }
        var trackingTouch = false
        val seekBar = SeekBar(this@SettingsActivity).apply {
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = safeOffset - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            isEnabled = enabled
            contentDescription = "歌词模糊半径偏移"
            progressTintList = ColorStateList.valueOf(palette.primary)
            thumbTintList = ColorStateList.valueOf(palette.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
                    valueLabel.text = formatBlurRadiusOffset(value)
                    if (BlurRadiusSeekBarPersistencePolicy.shouldPersistProgressChange(
                            fromUser = fromUser,
                            trackingTouch = trackingTouch,
                        )
                    ) {
                        onChanged(value)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    trackingTouch = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    trackingTouch = false
                    onChanged(
                        seekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    )
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(116)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.58f
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "歌词模糊半径偏移"
                    textSize = 17f
                    setTextColor(palette.onSurface)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueLabel)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "统一调整非高亮歌词 · 更改后需重开 Apple Music"
                textSize = 13.5f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(
                seekBar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)),
            )
        }
    }

    private fun formatBlurRadiusOffset(offsetPx: Int): String = when {
        offsetPx > 0 -> "+${offsetPx}px"
        else -> "${offsetPx}px"
    }

    private fun badge(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(palette.primary)
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(3), dp(9), dp(3))
        background = roundedDrawable(palette.primaryContainer, radiusDp = 99)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
    }

    private fun helpRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(10), dp(14), dp(10))
        background = rippleDrawable(
            roundedDrawable(palette.surface, radiusDp = 18, strokeColor = palette.outline),
        )
        contentDescription = "LSPosed 配置提示"
        addView(iconBubble(R.drawable.ic_help_outline, active = true, compact = true))
        addView(TextView(this@SettingsActivity).apply {
            text = "LSPosed 配置提示"
            textSize = 16f
            setTextColor(palette.onSurface)
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(palette.onSurfaceVariant)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        setOnClickListener { showHelp() }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("LSPosed 配置提示")
            .setMessage("在 LSPosed 中启用 AM++，并仅选择 Apple Music（com.apple.android.music）作为作用域。修改功能后，请强制停止并重新打开 Apple Music。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun iconBubble(drawable: Int, active: Boolean, compact: Boolean = false): View =
        FrameLayout(this).apply {
            val size = if (compact) 40 else 44
            background = roundedDrawable(
                if (active) palette.primary else palette.disabledIcon,
                radiusDp = 99,
            )
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(drawable)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = null
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }, FrameLayout.LayoutParams(dp(size), dp(size)))
        }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(palette.primary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun insetDivider(): View = divider().apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(16)
        }
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun rippleDrawable(content: GradientDrawable? = null): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(withAlpha(palette.primary, 28)),
        content ?: roundedDrawable(Color.TRANSPARENT, radiusDp = 0),
        null,
    )

    private fun switchThumbColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(palette.primary, palette.switchThumbOff),
    )

    private fun switchTrackColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(palette.switchTrackOn, palette.switchTrackOff),
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val FONT_PICKER_REQUEST_CODE = 4401
        const val CUSTOM_TTML_PICKER_REQUEST_CODE = 4402
        const val CUSTOM_LYRICS_BACKUP_CREATE_REQUEST_CODE = 4403
        const val CUSTOM_LYRICS_BACKUP_RESTORE_REQUEST_CODE = 4404
        const val BACKUP_MIME_TYPE = "application/zip"
        const val STATE_AWAITING_CUSTOM_TTML_PICKER = "awaiting_custom_ttml_picker"
        const val STATE_SETTINGS_PAGE = "settings_page"
        val settingsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }

    private data class Palette(
        val isDark: Boolean,
        val background: Int,
        val surface: Int,
        val primary: Int,
        val primaryContainer: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
        val disabledContainer: Int,
        val disabledIcon: Int,
        val switchTrackOff: Int,
        val switchTrackOn: Int,
        val switchThumbOff: Int,
    ) {
        companion object {
            fun resolve(context: Context): Palette {
                val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (dark) {
                    Palette(
                        isDark = true,
                        background = Color.rgb(22, 17, 19),
                        surface = Color.rgb(34, 27, 30),
                        primary = Color.rgb(255, 139, 176),
                        primaryContainer = Color.rgb(66, 34, 45),
                        onSurface = Color.rgb(248, 239, 242),
                        onSurfaceVariant = Color.rgb(213, 195, 201),
                        outline = Color.rgb(77, 62, 67),
                        disabledContainer = Color.rgb(48, 43, 45),
                        disabledIcon = Color.rgb(105, 95, 99),
                        switchTrackOff = Color.rgb(94, 83, 87),
                        switchTrackOn = Color.rgb(100, 50, 68),
                        switchThumbOff = Color.rgb(224, 215, 218),
                    )
                } else {
                    Palette(
                        isDark = false,
                        background = Color.rgb(255, 250, 252),
                        surface = Color.WHITE,
                        primary = Color.rgb(210, 56, 108),
                        primaryContainer = Color.rgb(253, 237, 243),
                        onSurface = Color.rgb(34, 27, 30),
                        onSurfaceVariant = Color.rgb(113, 99, 104),
                        outline = Color.rgb(235, 221, 226),
                        disabledContainer = Color.rgb(241, 237, 239),
                        disabledIcon = Color.rgb(154, 145, 148),
                        switchTrackOff = Color.rgb(205, 198, 201),
                        switchTrackOn = Color.rgb(247, 198, 216),
                        switchThumbOff = Color.rgb(250, 247, 248),
                    )
                }
            }
        }
    }
}
