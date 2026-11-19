package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Feature gate for Apple Music Classical synchronized Listening Guide & Editorial Notes translation. */
internal class ClassicalGuideTranslationFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CLASSICAL_GUIDE_TRANSLATION

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().llmLyricsEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.classicalGuideTranslation.install().toFeatureInstallResult()
    }
}
