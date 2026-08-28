package dev.amenhancer.module.hook

import dev.amenhancer.module.config.CatalogLanguagePolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Compatibility adapter for the former process-wide Catalog request-language adaptation.
 *
 * The old target cannot safely distinguish all ordinary Apple Music requests from
 * metadata lookups.  HLE now carries its locale in a resolver-owned token instead,
 * so this adapter must never install global hooks.
 */
internal class AppleMusicCatalogLanguageTarget(
    private val symbols: TargetSymbolResolver,
    private val rawTargetLanguage: String?,
) : CatalogLanguageTarget {
    override fun install(): TargetCapabilityInstall = TargetCapabilityInstall.Degraded(
        "Global Catalog locale hooks disabled; locale is scoped to HLE metadata tokens",
    )
}

internal fun interface CatalogLanguageTarget {
    fun install(): TargetCapabilityInstall
}

internal object CatalogLanguageRewritePolicy {
    internal val rawTagKeys = setOf(
        "l",
        "lang",
        "locale",
        "storefront-language",
        "storefront_language",
    )

    fun withHeaderLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        if (isHleResolverRequest(original)) return original as Map<Any?, Any?>
        val header = CatalogLanguagePolicy.headerLanguage(targetLanguage)
        val key = original.keys.firstOrNull {
            it?.toString()?.equals("Accept-Language", ignoreCase = true) == true
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == header) return original as Map<Any?, Any?>
        return LinkedHashMap<Any?, Any?>(original.size + 1).also {
            original.forEach(it::put)
            it[key] = header
        }
    }

    fun withRawTagLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        if (isHleResolverRequest(original)) return original as Map<Any?, Any?>
        val key = original.keys.firstOrNull {
            it?.toString()?.lowercase(Locale.ROOT) in rawTagKeys
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == targetLanguage) return original as Map<Any?, Any?>
        return LinkedHashMap<Any?, Any?>(original.size + 1).also {
            original.forEach(it::put)
            it[key] = targetLanguage
        }
    }

    private fun isHleResolverRequest(original: Map<*, *>): Boolean =
        original.keys.any {
            it?.toString() == AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        }
}
