package dev.amenhancer.module.hook

import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.Locale
import dev.amenhancer.module.config.CatalogLanguagePolicy

/**
 * Replaces the language used by Apple's Catalog lookup path.  The same
 * switch that enables title correction gates this target, so a configured
 * language never changes ordinary Apple Music traffic while the feature is
 * disabled.
 *
 * Each hook mirrors one AMTool 1.2 seam from amtool-1.2-analysis/REPORT.md:
 * `storeFrontLanguageOrDefault` and the MediaApi language map use the raw tag
 * (`ic.r()`), while Accept-Language headers and the iCloud helper use the
 * script-mapped value (`ic.q()`).  Every symbol resolves independently and
 * fails open: a missing or ambiguous symbol only degrades that one hook.
 */
internal class AppleMusicCatalogLanguageTarget(
    private val symbols: TargetSymbolResolver,
    rawTargetLanguage: String,
) : CatalogLanguageTarget {
    private val targetLanguage = CatalogLanguagePolicy.resolveTag(rawTargetLanguage)

    override fun install(): TargetCapabilityInstall {
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

        if (installAfterStringHook(
                storefrontResolution,
                errorName = "storefront language hook",
                errors = errors,
            ) { param ->
                param.result = targetLanguage
            }
        ) {
            installed++
        }

        if (installBeforeHook(
                headersResolution,
                errorName = "StoreApi header hook",
                errors = errors,
            ) { param ->
                val key = param.args.getOrNull(0) as? String
                if (key != null &&
                    key.equals("Accept-Language", ignoreCase = true) &&
                    param.args.size > 1 &&
                    (param.args[1] == null || param.args[1] is String)
                ) {
                    param.args[1] = CatalogLanguagePolicy.headerLanguage(targetLanguage)
                }
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                arrayResolution,
                errorName = "Accept-Language array hook",
                errors = errors,
            ) { result, param ->
                if (result is Array<*>) param.result = languageArrayFor(targetLanguage)
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                iCloudResolution,
                errorName = "iCloud Accept-Language hook",
                errors = errors,
            ) { result, param ->
                if (result is String) {
                    param.result = CatalogLanguagePolicy.headerLanguage(targetLanguage)
                }
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                headerMapResolution,
                errorName = "StoreApi header map hook",
                errors = errors,
            ) { result, param ->
                val map = result as? Map<*, *>
                if (map != null) {
                    val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(
                        map,
                        targetLanguage,
                    )
                    if (rewritten !== map) param.result = rewritten
                }
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                iTunesHeaderMapResolution,
                errorName = "iTunes header map hook",
                errors = errors,
            ) { result, param ->
                val map = result as? Map<*, *>
                if (map != null) {
                    val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(
                        map,
                        targetLanguage,
                    )
                    if (rewritten !== map) param.result = rewritten
                }
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                localeHeaderMapResolution,
                errorName = "locale header map hook",
                errors = errors,
            ) { result, param ->
                val map = result as? Map<*, *>
                if (map != null) {
                    val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(
                        map,
                        targetLanguage,
                    )
                    if (rewritten !== map) param.result = rewritten
                }
            }
        ) {
            installed++
        }

        if (installAfterValueHook(
                languageMapResolution,
                errorName = "MediaApi language map hook",
                errors = errors,
            ) { result, param ->
                val map = result as? Map<*, *>
                if (map != null) {
                    val rewritten = CatalogLanguageRewritePolicy.withRawTagLanguageValue(
                        map,
                        targetLanguage,
                    )
                    if (rewritten !== map) param.result = rewritten
                }
            }
        ) {
            installed++
        }

        if (installBeforeHook(
                setParamResolution,
                errorName = "Store lookup setParam hook",
                errors = errors,
            ) { param ->
                val key = param.args.getOrNull(0)?.toString()?.lowercase(Locale.ROOT)
                if (key in CatalogLanguageRewritePolicy.rawTagKeys && param.args.size > 1) {
                    param.args[1] = targetLanguage
                }
            }
        ) {
            installed++
        }

        if (installed == 0) {
            return TargetCapabilityInstall.Degraded(errors.joinToString("; ").ifBlank {
                "Catalog language hooks could not be installed"
            })
        }
        return TargetCapabilityInstall.Active(
            "Catalog requests use $targetLanguage; installed $installed hook(s); " +
                "suspend repository request hook skipped to preserve Continuation return contract" +
                if (errors.isEmpty()) "" else "; ${errors.joinToString("; ")}",
        )
    }

    /** Mirrors AMTool `wi`: `[Locale.forLanguageTag(r()).language, "en"]`. */
    private fun languageArrayFor(targetLanguage: String): Array<String> {
        val language = Locale.forLanguageTag(targetLanguage).language
        return arrayOf(language.ifBlank { "tr" }, "en")
    }

    private fun installAfterStringHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean {
        val method = resolution.valueOrNull()
        if (method == null) {
            errors += resolution.summary
            return false
        }
        return runCatching {
            method.isAccessible = true
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                    runCatching { block(param) }
                }
            })
            true
        }.getOrElse {
            errors += "$errorName: ${it.message.orEmpty()}"
            false
        }
    }

    private fun installAfterValueHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (Any?, ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean {
        val method = resolution.valueOrNull()
        if (method == null) {
            errors += resolution.summary
            return false
        }
        return runCatching {
            method.isAccessible = true
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                    runCatching {
                        if (!param.shouldReturnEarly()) block(param.result, param)
                    }
                }
            })
            true
        }.getOrElse {
            errors += "$errorName: ${it.message.orEmpty()}"
            false
        }
    }

    private fun installBeforeHook(
        resolution: TargetResolution<Method>,
        errorName: String,
        errors: MutableList<String>,
        block: (ModernMethodHook.MethodHookParam) -> Unit,
    ): Boolean {
        val method = resolution.valueOrNull()
        if (method == null) {
            errors += resolution.summary
            return false
        }
        return runCatching {
            method.isAccessible = true
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                    runCatching { block(param) }
                }
            })
            true
        }.getOrElse {
            errors += "$errorName: ${it.message.orEmpty()}"
            false
        }
    }
}
internal fun interface CatalogLanguageTarget {
    fun install(): TargetCapabilityInstall
}

internal object CatalogLanguageRewritePolicy {
    private val headerKeys = setOf("accept-language")

    /** `cj.java` keys plus AM++'s explicit storefront spelling. */
    internal val rawTagKeys = setOf(
        "l",
        "lang",
        "locale",
        "storefront-language",
        "storefront_language",
    )

    /**
     * AM++'s Catalog request map seam (`getEntitiesWithIds`): rewrites every
     * language-bearing key in place and adds `Accept-Language` when the
     * request carries none.  Accept-Language uses AMTool's `q()` script
     * mapping; the remaining keys use the raw `r()` tag.
     */
    fun withTargetLanguage(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        val target = CatalogLanguagePolicy.resolveTag(targetLanguage)
        val header = CatalogLanguagePolicy.headerLanguage(target)
        val copy = LinkedHashMap<Any?, Any?>(original.size + 1)
        var changed = false
        original.forEach { (key, value) ->
            when (key?.toString()?.lowercase(Locale.ROOT)) {
                in headerKeys -> {
                    if (value == null || value is String) {
                        copy[key] = header
                        changed = changed || value != header
                    } else {
                        copy[key] = value
                    }
                }
                in rawTagKeys -> {
                    if (value == null || value is String) {
                        copy[key] = target
                        changed = changed || value != target
                    } else {
                        copy[key] = value
                    }
                }
                else -> copy[key] = value
            }
        }
        if (copy.keys.none { it?.toString()?.equals("accept-language", ignoreCase = true) == true }) {
            copy["Accept-Language"] = header
            changed = true
        }
        return if (changed) copy else original as Map<Any?, Any?>
    }

    /** AMTool `vi(2)`: replace an existing Accept-Language entry with `q()`. */
    fun withHeaderLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        val header = CatalogLanguagePolicy.headerLanguage(CatalogLanguagePolicy.resolveTag(targetLanguage))
        val key = original.keys.firstOrNull {
            it?.toString()?.equals("Accept-Language", ignoreCase = true) == true
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == header) return original as Map<Any?, Any?>
        val copy = LinkedHashMap<Any?, Any?>(original.size + 1)
        original.forEach(copy::put)
        copy[key] = header
        return copy
    }

    /** AMTool `xi(2)` with `cj.a()` keys: replace the l/lang/locale value with `r()`. */
    fun withRawTagLanguageValue(original: Map<*, *>, targetLanguage: String): Map<Any?, Any?> {
        val target = CatalogLanguagePolicy.resolveTag(targetLanguage)
        val key = original.keys.firstOrNull {
            it?.toString()?.lowercase(Locale.ROOT) in rawTagKeys
        } ?: return original as Map<Any?, Any?>
        val current = original[key]
        if (current != null && current !is String) return original as Map<Any?, Any?>
        if (current?.toString() == target) return original as Map<Any?, Any?>
        val copy = LinkedHashMap<Any?, Any?>(original.size + 1)
        original.forEach(copy::put)
        copy[key] = target
        return copy
    }
}
