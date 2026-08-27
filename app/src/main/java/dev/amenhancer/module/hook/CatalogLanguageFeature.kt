package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.CatalogLanguagePolicy

/**
 * Optional request-language half of title correction.
 *
 * It is deliberately installed before HLE metadata hooks. HLE's tokenized
 * resolver requests have their own locale and later take precedence, while
 * normal Apple Music Catalog requests retain the configured target language.
 */
internal class CatalogLanguageFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CATALOG_LANGUAGE

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        if (!settings.titleCorrectionEnabled) return FeatureInstallResult.disabled()
        if (!CatalogLanguagePolicy.isConfigured(settings.titleCorrectionTargetLanguage)) {
            return FeatureInstallResult.disabled(
                "No target language configured; ordinary Catalog requests follow Apple Music",
            )
        }
        return context.target.catalogLanguage.install().toFeatureInstallResult()
    }
}
