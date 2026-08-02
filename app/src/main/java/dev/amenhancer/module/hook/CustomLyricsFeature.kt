package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Module setting and health adapter around user-managed lyric mappings. */
internal class CustomLyricsFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CUSTOM_LYRICS

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().customLyricsEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.customLyrics.install().toFeatureInstallResult()
    }
}
