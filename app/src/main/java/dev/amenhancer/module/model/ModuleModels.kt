package dev.amenhancer.module.model

import dev.amenhancer.module.ModuleConstants

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    val futureBlurEnabled: Boolean = true,
    val lyricBlurRadiusOffsetPx: Int = 0,
    val schemaVersion: Int = ModuleConstants.CONFIG_SCHEMA_VERSION,
) {
    companion object {
        const val MIN_LYRIC_BLUR_RADIUS_OFFSET_PX = -10
        const val MAX_LYRIC_BLUR_RADIUS_OFFSET_PX = 10
    }
}

enum class FeatureState {
    ACTIVE,
    DISABLED,
    UNSUPPORTED,
    DEGRADED,
    FAILED,
}

data class FeatureHealth(
    val feature: String,
    val state: FeatureState,
    val message: String,
    val targetVersion: String = "",
)
