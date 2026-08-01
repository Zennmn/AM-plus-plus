package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Independent setting/health adapter for the all-player-lyrics typeface capability. */
internal class LyricsTypefaceFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRICS_TYPEFACE

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().fontManifest.enabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.lyricsTypeface.install().toFeatureInstallResult()
    }
}
