package dev.amenhancer.module.config

import java.util.Locale

/**
 * Shared validation for the language used by Apple Music's Catalog requests.
 * Mirrors AMTool 1.2's `ic.a()`/`ic.r()`/`ic.q()` semantics (see
 * amtool-1.2-analysis/REPORT.md): the UI rejects empty or invalid input, and a
 * runtime read of an empty or unparsable stored value falls back to AMTool's
 * `tr-TR` default.  The AM++-specific "empty means keep Apple's language"
 * no-op is not a default behavior anymore.
 */
internal object CatalogLanguagePolicy {
    /** AMTool's persisted/default target in version 1.2. */
    const val DEFAULT_TARGET_LANGUAGE = "tr-TR"

    private const val DEFAULT_SCRIPT_SIMPLIFIED = "zh-Hans"
    private const val DEFAULT_SCRIPT_TRADITIONAL = "zh-Hant"

    /**
     * AMTool `ic.a()`: trim, `_` -> `-`, `Locale.Builder` with a
     * `Locale.forLanguageTag` fallback.  Empty input is AMTool's `tr-TR`
     * fallback; input that cannot be parsed into a usable tag stays blank here
     * so the config schema keeps rejecting garbage, while [resolveTag]
     * applies AMTool's runtime fallback on the hook path.
     */
    fun normalize(raw: String?): String {
        val candidate = raw.orEmpty().trim().replace('_', '-')
        if (candidate.isEmpty()) return DEFAULT_TARGET_LANGUAGE
        val tag = buildLocale(candidate)?.toLanguageTag().orEmpty()
        if (tag.isBlank() || tag.equals("und", ignoreCase = true)) return ""
        return tag
    }

    /**
     * AMTool `ic.r()`: the value a hook must actually send.  Empty or
     * malformed stored values resolve to the hardcoded `tr-TR` default rather
     * than becoming a no-op.
     */
    fun resolveTag(raw: String?): String =
        normalize(raw).ifBlank { DEFAULT_TARGET_LANGUAGE }

    /**
     * AMTool `qq.java` acceptance rule: empty input is rejected, and input
     * that only normalizes to the default (for example `und`) is rejected too
     * unless the user actually typed the default.
     */
    fun isValid(raw: String?): Boolean {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isEmpty()) return false
        return normalize(trimmed).isNotEmpty()
    }

    /** AMTool `ic.q()`: script mapping used for Accept-Language header values. */
    fun headerLanguage(tag: String?): String {
        val normalized = resolveTag(tag)
        return when (normalized.lowercase(Locale.ROOT)) {
            "zh-cn", "zh-sg" -> DEFAULT_SCRIPT_SIMPLIFIED
            "zh-tw", "zh-hk", "zh-mo" -> DEFAULT_SCRIPT_TRADITIONAL
            else -> normalized
        }
    }

    fun displayName(tag: String?, displayLocale: Locale = Locale.getDefault()): String {
        val normalized = resolveTag(tag)
        val locale = Locale.forLanguageTag(normalized)
        val language = locale.getDisplayLanguage(displayLocale).ifBlank { normalized }
        return "$language（$normalized）"
    }

    /** AMTool `ic.a()`/`ic.f()`: Builder first, lenient parser as fallback. */
    private fun buildLocale(tag: String): Locale? =
        runCatching { Locale.Builder().setLanguageTag(tag).build() }
            .getOrNull()
            ?: runCatching { Locale.forLanguageTag(tag) }.getOrNull()
}
