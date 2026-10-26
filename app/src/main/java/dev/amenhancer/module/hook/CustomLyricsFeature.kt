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

/** Native background repair shares the existing lyric effects optimization setting. */
internal class LyricsParserFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRICS_PARSER

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyricEffectsOptimizationEnabled) {
            return FeatureInstallResult.disabled("歌词效果优化已关闭")
        }
        return context.target.lyricsParser.install().toFeatureInstallResult()
    }
}
