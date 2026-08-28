package dev.amenhancer.module.config

import java.util.Locale

/**
 * Validation and formatting for the optional language used by Apple's
 * ordinary Catalog requests.
 *
 * HLE's own original-metadata requests do not use this value: they carry a
 * request token and are localized to the detected source language instead.
 */
internal object CatalogLanguagePolicy {
    /** An empty value deliberately means "leave Apple Music's request language alone". */
    const val DISABLED_TARGET_LANGUAGE = ""

    private const val DEFAULT_SCRIPT_SIMPLIFIED = "zh-Hans"
    private const val DEFAULT_SCRIPT_TRADITIONAL = "zh-Hant"

    fun normalize(raw: String?): String {
        val candidate = raw.orEmpty().trim().replace('_', '-')
        if (candidate.isEmpty()) return DISABLED_TARGET_LANGUAGE
        val tag = buildLocale(candidate)?.toLanguageTag().orEmpty()
        return tag.takeUnless {
            it.isBlank() || it.equals("und", ignoreCase = true)
        }.orEmpty()
    }

    fun isConfigured(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun isValid(raw: String?): Boolean = normalize(raw).isNotEmpty()

    /** Uses the script spelling Apple Music expects in Accept-Language. */
    fun headerLanguage(tag: String): String = when (val normalized = normalize(tag)) {
        "zh-CN", "zh-SG" -> DEFAULT_SCRIPT_SIMPLIFIED
        "zh-TW", "zh-HK", "zh-MO" -> DEFAULT_SCRIPT_TRADITIONAL
        "ja-JP" -> "ja"
        else -> normalized
    }

    fun displayName(tag: String?, displayLocale: Locale = Locale.getDefault()): String {
        val normalized = normalize(tag)
        if (normalized.isEmpty()) return "不改写（跟随 Apple Music）"
        val locale = Locale.forLanguageTag(normalized)
        val language = locale.getDisplayLanguage(displayLocale).ifBlank { normalized }
        return "$language（$normalized）"
    }

    private fun buildLocale(tag: String): Locale? =
        runCatching { Locale.Builder().setLanguageTag(tag).build() }
            .getOrNull()
            ?: runCatching { Locale.forLanguageTag(tag) }.getOrNull()
}
