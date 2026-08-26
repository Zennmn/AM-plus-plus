package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class TitleCorrectionFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_TITLE_CORRECTION

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().titleCorrectionEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.hleMetadata.install().toFeatureInstallResult()
    }
}
