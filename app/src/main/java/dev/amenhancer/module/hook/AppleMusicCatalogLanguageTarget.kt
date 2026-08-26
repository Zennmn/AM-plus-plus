package dev.amenhancer.module.hook

import dev.amenhancer.module.config.CatalogLanguagePolicy
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Restores AM++'s lower-level Catalog request-language adaptation.
 *
 * This target intentionally changes only ordinary host traffic. A map carrying
 * HLE's catalog token is left untouched so the original-region resolver can
 * query the locale it detected for that media item.
 */
internal class AppleMusicCatalogLanguageTarget(
    private val symbols: TargetSymbolResolver,
    rawTargetLanguage: String,
) : CatalogLanguageTarget {
    private val targetLanguage = CatalogLanguagePolicy.normalize(rawTargetLanguage)

    override fun install(): TargetCapabilityInstall {
        if (targetLanguage.isBlank()) {
            return TargetCapabilityInstall.Degraded("Catalog target language was not configured")
        }

        val storefrontResolution = symbols.resolve(
            AppleMusicSymbols.ConfigurationStoreStoreFrontLanguageMethod,
        )
        val headersResolution = symbols.resolve(AppleMusicSymbols.StoreApiHeadersSetMethod)
        val arrayResolution = symbols.resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)
        val iCloudResolution = symbols.resolve(AppleMusicSymbols.ICloudAcceptLanguageHelperMethod)
        val headerMapResolution = symbols.resolve(AppleMusicSymbols.StoreApiHeaderMapMethod)
        val iTunesHeaderMapResolution = symbols.resolve(AppleMusicSymbols.ITunesGetHeadersMapMethod)
        val localeHeaderMapResolution = symbols.resolve(AppleMusicSymbols.LocaleHeaderMapMethod)
        val languageMapResolution = symbols.resolve(AppleMusicSymbols.MediaApiLanguageParamMethod)
        val setParamResolution = symbols.resolve(AppleMusicSymbols.StoreLookupSetParamMethod)

        var installed = 0
        val errors = mutableListOf<String>()

        if (installAfterStringHook(storefrontResolution, "storefront language hook", errors) { param ->
                param.result = targetLanguage
            }
        ) installed++

        if (installBeforeHook(headersResolution, "StoreApi header hook", errors) { param ->
                val key = param.args.getOrNull(0) as? String
                if (
                    key != null &&
                    key.equals("Accept-Language", ignoreCase = true) &&
                    param.args.size > 1 &&
                    (param.args[1] == null || param.args[1] is String)
                ) {
                    param.args[1] = CatalogLanguagePolicy.headerLanguage(targetLanguage)
                }
            }
        ) installed++

        if (installAfterValueHook(arrayResolution, "Accept-Language array hook", errors) { result, param ->
                if (result is Array<*>) param.result = languageArrayFor(targetLanguage)
            }
        ) installed++

        if (installAfterValueHook(iCloudResolution, "iCloud Accept-Language hook", errors) { result, param ->
                if (result is String) {
                    param.result = CatalogLanguagePolicy.headerLanguage(targetLanguage)
                }
            }
        ) installed++

        if (installAfterValueHook(headerMapResolution, "StoreApi header map hook", errors) { result, param ->
                val map = result as? Map<*, *> ?: return@installAfterValueHook
                val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(map, targetLanguage)
                if (rewritten !== map) param.result = rewritten
            }
        ) installed++

        if (installAfterValueHook(
                iTunesHeaderMapResolution,
                "iTunes header map hook",
                errors,
            ) { result, param ->
                val map = result as? Map<*, *> ?: return@installAfterValueHook
                val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(map, targetLanguage)
                if (rewritten !== map) param.result = rewritten
            }
        ) installed++

        if (installAfterValueHook(
                localeHeaderMapResolution,
                "locale header map hook",
                errors,
            ) { result, param ->
                val map = result as? Map<*, *> ?: return@installAfterValueHook
                val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(map, targetLanguage)
                if (rewritten !== map) param.result = rewritten
            }
        ) installed++

        if (installAfterValueHook(
                languageMapResolution,
                "MediaApi language map hook",
                errors,
            ) { result, param ->
                val map = result as? Map<*, *> ?: return@installAfterValueHook
                val rewritten = CatalogLanguageRewritePolicy.withRawTagLanguageValue(map, targetLanguage)
                if (rewritten !== map) param.result = rewritten
            }
        ) installed++

        if (installBeforeHook(setParamResolution, "Store lookup setParam hook", errors) { param ->
                val key = param.args.getOrNull(0)?.toString()?.lowercase(Locale.ROOT)
                if (key in CatalogLanguageRewritePolicy.rawTagKeys && param.args.size > 1) {
                    param.args[1] = targetLanguage
                }
            }
        ) installed++

        if (installed == 0) {
            return TargetCapabilityInstall.Degraded(
                errors.joinToString("; ").ifBlank {
                    "Catalog language hooks could not be installed"
                },
            )
        }
        return TargetCapabilityInstall.Active(
            "Ordinary Catalog requests use $targetLanguage; installed $installed hook(s)" +
                if (errors.isEmpty()) "" else "; " + errors.joinToString("; "),
        )
    }

    private fun languageArrayFor(language: String): Array<String> {
        val primary = Locale.forLanguageTag(language).language.ifBlank { "en" }
        return arrayOf(primary, "en")
    }

    private fun installAfterStringHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean = installHook(resolution, errorName, errors) { method ->
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runCatching { block(param) }
            }
        })
    }

    private fun installAfterValueHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (Any?, ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean = installHook(resolution, errorName, errors) { method ->
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runCatching {
                    if (!param.shouldReturnEarly()) block(param.result, param)
                }
            }
        })
    }

    private fun installBeforeHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean = installHook(resolution, errorName, errors) { method ->
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runCatching { block(param) }
            }
        })
    }

    private fun installHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        install: (Method) -> Unit,
    ): Boolean {
        val method = resolution.valueOrNull()
        if (method == null) {
            errors += resolution.summary
            return false
        }
        return runCatching {
            method.isAccessible = true
            install(method)
            true
        }.getOrElse {
            errors += errorName + ": " + it.message.orEmpty()
            false
        }
    }
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
