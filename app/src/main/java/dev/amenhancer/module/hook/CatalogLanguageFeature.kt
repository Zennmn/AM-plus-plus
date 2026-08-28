package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Legacy compatibility slot for the old global Catalog-language feature.
 *
 * Fixed-region title correction now scopes the locale to HLE's tokenized
 * metadata requests. Installing the old process-wide hooks would change
 * ordinary Apple Music traffic (and can make account-available songs appear
 * unavailable), so this feature is intentionally inert for every mode.
 */
internal class CatalogLanguageFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CATALOG_LANGUAGE

    override fun install(context: HookContext): FeatureInstallResult =
        FeatureInstallResult.disabled(
            "Scoped to HLE metadata requests; ordinary Apple Music requests follow the account",
        )
}
