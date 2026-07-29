package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Setting gate around the target-specific dual-pane capability. */
internal class DualPaneFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_DUAL_PANE

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().dualPaneEnabled) return FeatureInstallResult.disabled()
        return context.target.dualPane.install().toFeatureInstallResult()
    }
}
