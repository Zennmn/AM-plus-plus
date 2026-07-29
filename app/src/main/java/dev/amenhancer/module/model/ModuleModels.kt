package dev.amenhancer.module.model

import dev.amenhancer.module.ModuleConstants

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    val futureBlurEnabled: Boolean = true,
    val schemaVersion: Int = ModuleConstants.CONFIG_SCHEMA_VERSION,
)

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
