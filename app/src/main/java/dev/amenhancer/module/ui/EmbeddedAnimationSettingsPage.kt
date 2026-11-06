package dev.amenhancer.module.ui

import androidx.compose.runtime.Composable
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.EnhancementDefaults
import dev.amenhancer.module.model.ModuleSettings

@Composable
internal fun EmbeddedSettingsHost.EmbeddedAnimationSettingsPage(
    settings: ModuleSettings,
    onEdit: ((ModuleSettings) -> ModuleSettings) -> Unit,
) {
    EmbeddedSettingsGroup {
        EmbeddedSettingRange(
            title = localizedText(ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND),
            value = settings.phoneLiquidGlassCollapseReboundPercent,
            range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT..ModuleSettings.MAX_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            unit = "%",
            summary = localizedText(ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND_SUMMARY),
            defaultValue = EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT,
        ) { value -> onEdit { it.copy(phoneLiquidGlassCollapseReboundPercent = value) } }
        EmbeddedSettingRange(
            title = localizedText(ModuleText.BOTTOM_BAR_MINI_REBOUND),
            value = settings.phoneLiquidGlassMiniReboundPercent,
            range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT..ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            unit = "%",
            summary = localizedText(ModuleText.BOTTOM_BAR_MINI_REBOUND_SUMMARY),
            defaultValue = EnhancementDefaults.GLASS_MINI_REBOUND_PERCENT,
        ) { value -> onEdit { it.copy(phoneLiquidGlassMiniReboundPercent = value) } }
        EmbeddedSettingRange(
            title = localizedText(ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE),
            value = settings.phoneLiquidGlassMiniSinkBounceDp,
            range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP..ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            unit = " dp",
            summary = localizedText(ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE_SUMMARY),
            defaultValue = EnhancementDefaults.GLASS_MINI_SINK_BOUNCE_DP,
        ) { value -> onEdit { it.copy(phoneLiquidGlassMiniSinkBounceDp = value) } }
        EmbeddedSettingRange(
            title = localizedText(ModuleText.BOTTOM_BAR_EXPAND_REBOUND),
            value = settings.phoneLiquidGlassExpandReboundPercent,
            range = ModuleSettings.MIN_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT..ModuleSettings.MAX_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            unit = "%",
            summary = localizedText(ModuleText.BOTTOM_BAR_EXPAND_REBOUND_SUMMARY),
            defaultValue = EnhancementDefaults.GLASS_EXPAND_REBOUND_PERCENT,
        ) { value -> onEdit { it.copy(phoneLiquidGlassExpandReboundPercent = value) } }
    }
}
