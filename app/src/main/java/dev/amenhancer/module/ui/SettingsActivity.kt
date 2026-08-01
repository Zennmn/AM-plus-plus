package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.R
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.model.ModuleSettings

internal object BlurRadiusSeekBarPersistencePolicy {
    fun shouldPersistProgressChange(fromUser: Boolean, trackingTouch: Boolean): Boolean =
        fromUser && !trackingTouch
}

class SettingsActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var launcherIconController: LauncherIconController
    private lateinit var content: LinearLayout
    private lateinit var palette: Palette

    private val serviceListener: (XposedServiceSnapshot) -> Unit = { snapshot ->
        runOnUiThread { if (::content.isInitialized) render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        launcherIconController = LauncherIconController(this)
        palette = Palette.resolve(this)
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
        addView(ScrollView(this@SettingsActivity).apply {
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildTopBar(): View = FrameLayout(this).apply {
        setPadding(dp(24), 0, dp(24), 0)
        addView(TextView(this@SettingsActivity).apply {
            text = "AM++"
            textSize = 20f
            setTextColor(palette.onSurface)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
        ))
    }

    private fun render(snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot) {
        content.removeAllViews()
        val settings = store.settings(snapshot)
        val writable = snapshot.isRemoteAvailable

        content.addView(statusCard(snapshot))
        content.addView(spacer(20))
        content.addView(featureCard(settings, writable))
        content.addView(spacer(24))
        content.addView(sectionLabel("应用"))
        content.addView(spacer(10))
        content.addView(appCard())
        content.addView(spacer(24))
        content.addView(sectionLabel("帮助"))
        content.addView(spacer(10))
        content.addView(helpRow())
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
