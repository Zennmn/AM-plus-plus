package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Dialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.config.TitleCorrectionMode
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.ModuleSettings
import java.lang.ref.WeakReference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun EmbeddedSettingsHost.showSettingsDialog(activity: Activity) {
    if (dialogReference?.get()?.isShowing == true || activity.isFinishing || activity.isDestroyed) return
    val initial = runCatching { controller.currentSettings() }.getOrElse {
        Toast.makeText(activity, localizedText(ModuleText.SETTINGS_READ_FAILED), Toast.LENGTH_SHORT).show()
        return
    }
    val draft = EmbeddedSettingsDraft(initial, controller::saveOrdinarySettings)
    var settings by mutableStateOf(initial)
    var revision by mutableIntStateOf(0)
    var page by mutableStateOf(EmbeddedSettingsPage.MAIN)
    var dialog: Dialog? = null

    fun update(next: ModuleSettings) {
        val saved = draft.update(next)
        settings = draft.settings
        if (!saved) Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
    }

    fun back() {
        when (page) {
            EmbeddedSettingsPage.MAIN -> dialog?.dismiss()
            EmbeddedSettingsPage.AUTOMATIC_LYRICS, EmbeddedSettingsPage.LLM_LYRICS ->
                page = EmbeddedSettingsPage.CUSTOM_LYRICS
            EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> page = EmbeddedSettingsPage.INTERFACE
            else -> page = EmbeddedSettingsPage.MAIN
        }
    }

    runCatching {
        val opened = createEmbeddedSettingsDialog(activity, onBack = ::back, onDismiss = {
            if (dialogReference?.get() === dialog) {
                dialogReference = null
                pageRefresh = null
                dismissAuxiliarySettingsDialogs()
            }
        }) { dismiss ->
            val selectedPage = page
            val current = settings
            val refresh = revision
            val scrollStateHolder = rememberSaveableStateHolder()
            EmbeddedSettingsPanel(width = 560.dp, onDismiss = dismiss) {
                EmbeddedSettingsHeader(
                    title = localizedText(selectedPage.title),
                    start = {
                        if (selectedPage == EmbeddedSettingsPage.MAIN) {
                            EmbeddedSettingsBrand()
                        } else {
                            EmbeddedSettingsIconAction(EmbeddedSvgIcon.Back, localizedText(ModuleText.BACK), onClick = ::back)
                        }
                    },
                    end = {
                        if (selectedPage == EmbeddedSettingsPage.PLUGINS) {
                            EmbeddedSettingsIconAction(
                                EmbeddedSvgIcon.AddCircle, localizedText(ModuleText.IMPORT_ZIP), enabled = plugins != null,
                            ) {
                                launchSafPicker(activity, EmbeddedSafOperation.PluginZip, "*/*",
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }
                        } else {
                            EmbeddedSettingsIconAction(EmbeddedSvgIcon.Document, localizedText(ModuleText.SETTINGS_DETAILS_ACTION)) {
                                showEmbeddedPageDetails(activity, selectedPage, settings)
                            }
                        }
                    },
                )
                scrollStateHolder.SaveableStateProvider(selectedPage.name) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (selectedPage) {
                            EmbeddedSettingsPage.MAIN -> EmbeddedSettingsHome(activity, current, refresh) { page = it }
                            EmbeddedSettingsPage.CUSTOM_LYRICS ->
                                EmbeddedCustomLyricsPage(activity, current, refresh, ::update) { page = it }
                            EmbeddedSettingsPage.AUTOMATIC_LYRICS -> EmbeddedAutomaticLyricsPage(current, ::update)
                            EmbeddedSettingsPage.LLM_LYRICS -> EmbeddedLlmLyricsPage(activity, current, ::update)
                            EmbeddedSettingsPage.PLUGINS -> EmbeddedPluginManagementPage(activity, refresh)
                            EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION ->
                                EmbeddedAnimationSettingsPage(current) { edit -> update(edit(draft.settings)) }
                            else -> EmbeddedSettingsCategory(activity, selectedPage, current, refresh,
                                onEdit = { edit -> update(edit(draft.settings)) },
                                onOpenAnimationSettings = { page = EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION },
                            )
                        }
                    }
                }
                if (selectedPage == EmbeddedSettingsPage.MAIN) {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        EmbeddedDialogActions(localizedText(ModuleText.CLOSE), dismiss)
                    }
                }
            }
        }
        dialog = opened
        dialogReference = WeakReference(opened)
        pageRefresh = { if (opened.isShowing) revision++ }
        opened.show()
    }.onFailure { error ->
        dialog?.dismiss()
        if (dialogReference?.get() === dialog) {
            dialogReference = null
            pageRefresh = null
        }
        Toast.makeText(activity, localizedText(ModuleText.SETTINGS_OPEN_FAILED, error.message.orEmpty()), Toast.LENGTH_LONG).show()
    }
}

private val EmbeddedSettingsPage.title: ModuleText
    get() = when (this) {
        EmbeddedSettingsPage.MAIN -> ModuleText.MODULE_SETTINGS
        EmbeddedSettingsPage.CUSTOM_LYRICS -> ModuleText.CATEGORY_LYRICS_MANAGEMENT
        EmbeddedSettingsPage.AUTOMATIC_LYRICS -> ModuleText.AUTO_LYRICS
        EmbeddedSettingsPage.LYRICS_EFFECTS -> ModuleText.CATEGORY_LYRICS_EFFECTS
        EmbeddedSettingsPage.INTERFACE -> ModuleText.CATEGORY_INTERFACE
        EmbeddedSettingsPage.TITLE_REGION -> ModuleText.CATEGORY_TITLE_REGION
        EmbeddedSettingsPage.ADVANCED -> ModuleText.ADVANCED_SETTINGS
        EmbeddedSettingsPage.PLUGINS -> ModuleText.PLUGINS
        EmbeddedSettingsPage.LLM_LYRICS -> ModuleText.LLM_LYRICS_FALLBACK
        EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS
    }

@Composable
private fun EmbeddedSettingsHost.EmbeddedSettingsHome(
    activity: Activity,
    settings: ModuleSettings,
    revision: Int,
    onOpen: (EmbeddedSettingsPage) -> Unit,
) {
    val lyricsCount = remember(revision) { runCatching { controller.lyricsEntries().size }.getOrDefault(0) }
    val font = remember(revision) { runCatching { controller.currentSettings().fontManifest }.getOrDefault(settings.fontManifest) }
    val fontName = if (font.enabled) font.displayName else localizedText(ModuleText.ORIGINAL_FONT)
    val effectCount = listOf(settings.futureBlurEnabled, settings.cjkKaraokeAnimationEnabled, settings.lyricEffectsOptimizationEnabled)
        .count { it }
    val dpi = if (settings.appleMusicDpiOverrideDpi == ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI) {
        localizedText(ModuleText.SUMMARY_DPI_SYSTEM)
    } else settings.appleMusicDpiOverrideDpi.toString()

    fun state(enabled: Boolean) = localizedText(if (enabled) ModuleText.STATE_ON else ModuleText.STATE_OFF)

    EmbeddedSettingsGroup {
        EmbeddedSettingLink(
            localizedText(ModuleText.CATEGORY_LYRICS_MANAGEMENT),
            localizedText(ModuleText.SUMMARY_LYRICS_MANAGEMENT, state(settings.customLyricsEnabled), lyricsCount),
            EmbeddedSvgIcon.CustomLyrics,
        ) { onOpen(EmbeddedSettingsPage.CUSTOM_LYRICS) }
        EmbeddedSettingLink(
            localizedText(ModuleText.CATEGORY_LYRICS_EFFECTS),
            localizedText(ModuleText.SUMMARY_LYRICS_EFFECTS, effectCount, fontName),
            EmbeddedSvgIcon.LyricEffects,
        ) { onOpen(EmbeddedSettingsPage.LYRICS_EFFECTS) }
        EmbeddedSettingLink(
            localizedText(ModuleText.CATEGORY_INTERFACE),
            localizedText(ModuleText.SUMMARY_INTERFACE, state(settings.dualPaneEnabled), state(settings.phoneLiquidGlassEnabled)),
            EmbeddedSvgIcon.TabletDualPane,
        ) { onOpen(EmbeddedSettingsPage.INTERFACE) }
        EmbeddedSettingLink(
            localizedText(ModuleText.CATEGORY_TITLE_REGION),
            localizedText(ModuleText.SUMMARY_TITLE_REGION, state(settings.titleCorrectionEnabled),
                localizedText(settings.titleCorrectionMode.displayText)),
            EmbeddedSvgIcon.TitleMode,
        ) { onOpen(EmbeddedSettingsPage.TITLE_REGION) }
        EmbeddedSettingLink(
            localizedText(ModuleText.ADVANCED_SETTINGS),
            if (nativeBridge.supportsStartupPagePrewarm(activity)) {
                localizedText(ModuleText.SUMMARY_ADVANCED, dpi, state(settings.startupPagePrewarmEnabled))
            } else "DPI · $dpi",
            EmbeddedSvgIcon.Settings,
        ) { onOpen(EmbeddedSettingsPage.ADVANCED) }
        EmbeddedSettingLink(
            localizedText(ModuleText.PLUGINS),
            localizedText(if (plugins == null) ModuleText.PLUGIN_RUNTIME_UNAVAILABLE else ModuleText.SUMMARY_PLUGINS),
            EmbeddedSvgIcon.Plugins,
        ) { onOpen(EmbeddedSettingsPage.PLUGINS) }
    }
}

@Composable
private fun EmbeddedSettingsHost.EmbeddedSettingsCategory(
    activity: Activity,
    page: EmbeddedSettingsPage,
    settings: ModuleSettings,
    revision: Int,
    onEdit: ((ModuleSettings) -> ModuleSettings) -> Unit,
    onOpenAnimationSettings: () -> Unit,
) {
    when (page) {
        EmbeddedSettingsPage.LYRICS_EFFECTS -> {
            EmbeddedSettingsGroup {
                Setting(ModuleText.BIDIRECTIONAL_LYRIC_BLUR, ModuleText.BIDIRECTIONAL_LYRIC_BLUR_SUMMARY,
                    settings.futureBlurEnabled, EmbeddedSvgIcon.LyricsBlur) { value -> onEdit { it.copy(futureBlurEnabled = value) } }
                Setting(ModuleText.CJK_KARAOKE_ANIMATION, ModuleText.CJK_KARAOKE_ANIMATION_SUMMARY,
                    settings.cjkKaraokeAnimationEnabled, EmbeddedSvgIcon.Karaoke) { value -> onEdit { it.copy(cjkKaraokeAnimationEnabled = value) } }
                Setting(ModuleText.LYRIC_EFFECTS_OPTIMIZATION, ModuleText.LYRIC_EFFECTS_OPTIMIZATION_SUMMARY,
                    settings.lyricEffectsOptimizationEnabled, EmbeddedSvgIcon.LyricEffects) { value -> onEdit { it.copy(lyricEffectsOptimizationEnabled = value) } }
                EmbeddedSettingRange(
                    title = localizedText(ModuleText.LYRIC_BLUR_OFFSET),
                    value = settings.lyricBlurRadiusOffsetPx,
                    range = ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX..ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    unit = " px",
                ) { value -> onEdit { it.copy(lyricBlurRadiusOffsetPx = value) } }
            }
            val font = remember(revision) { runCatching { controller.currentSettings().fontManifest }.getOrDefault(settings.fontManifest) }
            EmbeddedSettingsSection(
                localizedText(ModuleText.LYRICS_FONT),
                localizedText(if (font.enabled) ModuleText.LYRICS_FONT_ENABLED_SUMMARY else ModuleText.LYRICS_FONT_IMPORT_SUMMARY),
            )
            EmbeddedSettingsGroup {
                EmbeddedSettingLink(localizedText(ModuleText.CHOOSE_FONT),
                    if (font.enabled) font.displayName else localizedText(ModuleText.ORIGINAL_FONT), EmbeddedSvgIcon.Document) {
                    launchSafPicker(activity, EmbeddedSafOperation.Font, "*/*", EMBEDDED_FONT_MIME_TYPES)
                }
                EmbeddedSettingLink(localizedText(ModuleText.RESTORE_FONT), icon = EmbeddedSvgIcon.RestoreDefault,
                    enabled = font.enabled) {
                    runAsync(activity, controller::clearFont)
                }
            }
        }
        EmbeddedSettingsPage.INTERFACE -> {
            EmbeddedSettingsGroup {
                Setting(ModuleText.TABLET_DUAL_PANE, ModuleText.TABLET_DUAL_PANE_SUMMARY,
                    settings.dualPaneEnabled, EmbeddedSvgIcon.TabletDualPane) { value -> onEdit { it.copy(dualPaneEnabled = value) } }
                if (settings.dualPaneEnabled) Setting(
                    ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR, ModuleText.SHORT_DUAL_PANE_STATUS_SUMMARY,
                    settings.dualPaneHideStatusBarEnabled, EmbeddedSvgIcon.HideStatusBar,
                ) { value -> onEdit { it.copy(dualPaneHideStatusBarEnabled = value) } }
                Setting(ModuleText.LIQUID_GLASS_NAVIGATION, ModuleText.SHORT_GLASS_SUMMARY,
                    settings.phoneLiquidGlassEnabled, EmbeddedSvgIcon.Glass) { value -> onEdit { it.copy(phoneLiquidGlassEnabled = value) } }
                if (!settings.phoneLiquidGlassEnabled) {
                    Setting(ModuleText.TABLET_BOTTOM_BAR_FIX, ModuleText.TABLET_BOTTOM_BAR_FIX_SUMMARY,
                        settings.navigationCompensationEnabled, EmbeddedSvgIcon.NavigationFix) {
                        value -> onEdit { it.copy(navigationCompensationEnabled = value) }
                    }
                } else if (nativeBridge.supportsGlassScrollCollapse(activity)) {
                    Setting(ModuleText.BOTTOM_BAR_COLLAPSE, ModuleText.BOTTOM_BAR_COLLAPSE_SUMMARY,
                        settings.phoneLiquidGlassCollapseEnabled, EmbeddedSvgIcon.BarCollapse) {
                        value -> onEdit { it.copy(phoneLiquidGlassCollapseEnabled = value) }
                    }
                    if (settings.phoneLiquidGlassCollapseEnabled) EmbeddedSettingLink(
                        localizedText(ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS),
                        localizedText(ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS_SUMMARY),
                        EmbeddedSvgIcon.BarAnimation,
                        onClick = onOpenAnimationSettings,
                    )
                }
            }
            if (settings.phoneLiquidGlassEnabled) EmbeddedSettingsGroup {
                EmbeddedSettingRange(
                    title = localizedText(ModuleText.BOTTOM_BAR_HEIGHT),
                    value = settings.phoneLiquidGlassBottomGapDp,
                    range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP..ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    unit = " dp",
                    summary = localizedText(ModuleText.BOTTOM_BAR_HEIGHT_SUMMARY),
                    defaultValue = GlassPolicy.BOTTOM_DP,
                ) { value -> onEdit { it.copy(phoneLiquidGlassBottomGapDp = value) } }
                EmbeddedSettingRange(
                    title = localizedText(ModuleText.BOTTOM_BAR_BLUR),
                    value = settings.phoneLiquidGlassPanelBlurDp,
                    range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP..ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    unit = " dp",
                    summary = localizedText(ModuleText.BOTTOM_BAR_BLUR_SUMMARY),
                    defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt(),
                ) { value -> onEdit { it.copy(phoneLiquidGlassPanelBlurDp = value) } }
            }
        }
        EmbeddedSettingsPage.TITLE_REGION -> EmbeddedSettingsGroup {
            Setting(ModuleText.TITLE_CORRECTION, ModuleText.TITLE_CORRECTION_SUMMARY,
                settings.titleCorrectionEnabled, EmbeddedSvgIcon.TitleCorrection) { value -> onEdit { it.copy(titleCorrectionEnabled = value) } }
            val modes = TitleCorrectionMode.entries
            OverlayDropdownPreference(
                items = modes.map { localizedText(it.displayText) },
                selectedIndex = modes.indexOf(settings.titleCorrectionMode),
                title = localizedText(ModuleText.TITLE_CORRECTION_MODE),
                summary = localizedText(ModuleText.VALUE_RESTART_REQUIRED, localizedText(settings.titleCorrectionMode.displayText)),
                insideMargin = EmbeddedPreferencePadding,
                showValue = false,
                onSelectedIndexChange = { index -> onEdit { it.copy(titleCorrectionMode = modes[index]) } },
            )
        }
        EmbeddedSettingsPage.ADVANCED -> EmbeddedSettingsGroup {
            if (nativeBridge.supportsStartupPagePrewarm(activity)) Setting(
                ModuleText.STARTUP_PAGE_PREWARM, ModuleText.STARTUP_PAGE_PREWARM_SUMMARY,
                settings.startupPagePrewarmEnabled, EmbeddedSvgIcon.StartupPrewarm,
            ) { value -> onEdit { it.copy(startupPagePrewarmEnabled = value) } }
            Setting(ModuleText.CELLULAR_SETTINGS, ModuleText.CELLULAR_SETTINGS_SUMMARY,
                settings.forceCellularDataEntryEnabled, EmbeddedSvgIcon.Cellular) { value -> onEdit { it.copy(forceCellularDataEntryEnabled = value) } }
            val dpi = settings.appleMusicDpiOverrideDpi
            EmbeddedSettingLink(
                localizedText(ModuleText.MUSIC_DPI),
                if (dpi == ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI) {
                    localizedText(ModuleText.MUSIC_DPI_DEFAULT_SUMMARY).substringBefore(" ·")
                } else localizedText(ModuleText.MUSIC_DPI_VALUE_SUMMARY, dpi).substringBefore(" ·"),
                EmbeddedSvgIcon.DisplayDpi,
            ) {
                showEmbeddedDpiDialog(activity, dpi) { value -> onEdit { it.copy(appleMusicDpiOverrideDpi = value) } }
            }
        }
        else -> Unit
    }
}

@Composable
private fun EmbeddedSettingsHost.Setting(
    title: ModuleText,
    summary: ModuleText,
    checked: Boolean,
    icon: EmbeddedSvgIcon,
    onCheckedChange: (Boolean) -> Unit,
) = EmbeddedSettingSwitch(localizedText(title), localizedText(summary), checked, icon = icon, onCheckedChange = onCheckedChange)

private fun EmbeddedSettingsHost.showEmbeddedDpiDialog(activity: Activity, current: Int, onSelected: (Int) -> Unit) {
    showEmbeddedMiuixDialog(activity, localizedText(ModuleText.MUSIC_DPI), maxWidth = 420.dp) { dismiss ->
        var input by remember { mutableStateOf(current.toString()) }
        var invalid by remember { mutableStateOf(false) }
        Text(localizedText(ModuleText.MUSIC_DPI_NOTICE), style = MiuixTheme.textStyles.body2)
        TextField(
            value = input,
            onValueChange = { input = it; invalid = false },
            modifier = Modifier.fillMaxWidth(),
            label = localizedText(ModuleText.MUSIC_DPI_INPUT_HINT),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (invalid) Text(localizedText(ModuleText.MUSIC_DPI_INVALID), color = MiuixTheme.colorScheme.error)
        EmbeddedDialogActions(localizedText(ModuleText.CANCEL), dismiss, localizedText(ModuleText.SAVE)) {
            val parsed = input.trim().toIntOrNull()
            if (parsed == null || !ModuleSettings.isValidAppleMusicDpi(parsed)) {
                invalid = true
            } else {
                onSelected(parsed)
                dismiss()
            }
        }
    }
}

private fun EmbeddedSettingsHost.showEmbeddedPageDetails(activity: Activity, page: EmbeddedSettingsPage, settings: ModuleSettings) {
    val entries = when (page) {
        EmbeddedSettingsPage.MAIN -> emptyList()
        EmbeddedSettingsPage.CUSTOM_LYRICS -> listOf(
            ModuleText.CUSTOM_LYRICS_REPLACEMENT to ModuleText.CUSTOM_LYRICS_REPLACEMENT_SUMMARY,
            ModuleText.AUTO_LYRICS to ModuleText.AUTO_LYRICS_SUMMARY,
            ModuleText.LLM_LYRICS_FALLBACK to ModuleText.SHORT_LLM_LYRICS_SUMMARY,
        )
        EmbeddedSettingsPage.AUTOMATIC_LYRICS -> listOf(
            ModuleText.AUTO_LYRICS_ENABLED to ModuleText.AUTO_LYRICS_SUMMARY,
            ModuleText.AUTO_LYRICS_PREFER_NATIVE to ModuleText.AUTO_LYRICS_PREFER_NATIVE_SUMMARY,
            ModuleText.AUTO_LYRICS_SOURCE_ORDER to if (settings.automaticLyricsPreferNative) {
                ModuleText.AUTO_LYRICS_SOURCE_ORDER_NATIVE_HIDDEN
            } else ModuleText.AUTO_LYRICS_SOURCE_ORDER_ALL,
        )
        EmbeddedSettingsPage.LYRICS_EFFECTS -> listOf(
            ModuleText.BIDIRECTIONAL_LYRIC_BLUR to ModuleText.BIDIRECTIONAL_LYRIC_BLUR_SUMMARY,
            ModuleText.CJK_KARAOKE_ANIMATION to ModuleText.CJK_KARAOKE_ANIMATION_SUMMARY,
            ModuleText.LYRIC_EFFECTS_OPTIMIZATION to ModuleText.LYRIC_EFFECTS_OPTIMIZATION_SUMMARY,
            ModuleText.LYRICS_FONT to ModuleText.LYRICS_FONT_IMPORT_SUMMARY,
        )
        EmbeddedSettingsPage.INTERFACE -> listOf(
            ModuleText.TABLET_DUAL_PANE to ModuleText.TABLET_DUAL_PANE_SUMMARY,
            ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR to ModuleText.TABLET_DUAL_PANE_HIDE_STATUS_BAR_SUMMARY,
            ModuleText.LIQUID_GLASS_NAVIGATION to ModuleText.LIQUID_GLASS_NAVIGATION_SUMMARY,
            ModuleText.TABLET_BOTTOM_BAR_FIX to ModuleText.TABLET_BOTTOM_BAR_FIX_SUMMARY,
            ModuleText.BOTTOM_BAR_COLLAPSE to ModuleText.BOTTOM_BAR_COLLAPSE_SUMMARY,
            ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS to ModuleText.BOTTOM_BAR_ANIMATION_SETTINGS_SUMMARY,
        )
        EmbeddedSettingsPage.TITLE_REGION -> listOf(ModuleText.TITLE_CORRECTION to ModuleText.TITLE_CORRECTION_SUMMARY)
        EmbeddedSettingsPage.ADVANCED -> listOf(
            ModuleText.STARTUP_PAGE_PREWARM to ModuleText.STARTUP_PAGE_PREWARM_SUMMARY,
            ModuleText.CELLULAR_SETTINGS to ModuleText.CELLULAR_SETTINGS_SUMMARY,
            ModuleText.MUSIC_DPI to ModuleText.MUSIC_DPI_NOTICE,
        )
        EmbeddedSettingsPage.PLUGINS -> listOf(ModuleText.PLUGINS to ModuleText.PLUGIN_RESTART_NOTICE)
        EmbeddedSettingsPage.LLM_LYRICS -> listOf(
            ModuleText.LLM_LYRICS_ENABLED to ModuleText.LLM_LYRICS_ENABLED_SUMMARY,
            ModuleText.LLM_LYRICS_BASE_URL to ModuleText.LLM_LYRICS_BASE_URL_SUMMARY,
            ModuleText.LLM_LYRICS_API_KEY to ModuleText.LLM_LYRICS_API_KEY_SUMMARY,
            ModuleText.LLM_LYRICS_MODEL to ModuleText.LLM_LYRICS_MODEL_SUMMARY,
            ModuleText.LLM_LYRICS_THINKING_FORMAT to ModuleText.LLM_LYRICS_THINKING_FORMAT_SUMMARY,
            ModuleText.LLM_LYRICS_THINKING to ModuleText.LLM_LYRICS_THINKING_SUMMARY,
            ModuleText.LLM_LYRICS_THINKING_EFFORT to ModuleText.LLM_LYRICS_THINKING_EFFORT_SUMMARY,
        )
        EmbeddedSettingsPage.BOTTOM_BAR_ANIMATION -> listOf(
            ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND to ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND_SUMMARY,
            ModuleText.BOTTOM_BAR_MINI_REBOUND to ModuleText.BOTTOM_BAR_MINI_REBOUND_SUMMARY,
            ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE to ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE_SUMMARY,
            ModuleText.BOTTOM_BAR_EXPAND_REBOUND to ModuleText.BOTTOM_BAR_EXPAND_REBOUND_SUMMARY,
        )
    }
    val heading = if (page == EmbeddedSettingsPage.MAIN) ModuleText.SETTINGS_HELP else ModuleText.SETTINGS_DETAILS
    showEmbeddedMiuixDialog(activity, localizedText(heading)) { dismiss ->
        if (page == EmbeddedSettingsPage.MAIN) {
            listOf(ModuleText.PRIVATE_STORAGE_NOTICE, ModuleText.RESTART_NOTICE,
                ModuleText.LSPOSED_HELP_SCOPE, ModuleText.LSPOSED_HELP_RESTART).forEach {
                Text(localizedText(it), style = MiuixTheme.textStyles.body1)
            }
        } else {
            entries.forEach { (title, summary) -> EmbeddedSettingsSection(localizedText(title), localizedText(summary)) }
            EmbeddedSettingsNotice(localizedText(ModuleText.RESTART_NOTICE))
        }
        EmbeddedDialogActions(localizedText(ModuleText.GOT_IT), dismiss)
    }
}
