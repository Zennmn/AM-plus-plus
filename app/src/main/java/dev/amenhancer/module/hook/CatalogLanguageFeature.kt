package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** The request-language half of title correction; it shares the same gate. */
internal class CatalogLanguageFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CATALOG_LANGUAGE

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        if (!settings.titleCorrectionEnabled) return FeatureInstallResult.disabled()
        return context.target.catalogLanguage.install().toFeatureInstallResult()
    }
}
