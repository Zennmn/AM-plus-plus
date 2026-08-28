package dev.amenhancer.module.config

import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * Metadata presentation profile used by the title-correction feature.
 *
 * The master switch remains the opt-in gate.  When it is off no profile is
 * installed and Apple Music follows the account.  When it is on, one of the
 * explicit profiles below owns both the request locale and its cache
 * namespace for the lifetime of the Apple Music process.
 */
enum class TitleCorrectionMode(
    val storageValue: String,
    val displayName: String,
    val contentUiLanguageSelection: Int,
    val catalogStorefront: String?,
    val catalogLanguage: String?,
    val cacheNamespace: String,
) {
    ORIGINAL_HYPER(
        storageValue = "original_hyper",
        displayName = "按歌曲原地区修正",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
        catalogStorefront = null,
        catalogLanguage = null,
        cacheNamespace = "original_hyper_v1",
    ),
    MAINLAND_CHINA(
        storageValue = "mainland_china",
        displayName = "固定中国大陆",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN,
        catalogStorefront = "cn",
        catalogLanguage = "zh-CN",
        cacheNamespace = "cn_v1",
    ),
    JAPAN(
        storageValue = "japan",
        displayName = "固定日本",
        contentUiLanguageSelection = RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
        catalogStorefront = "jp",
        catalogLanguage = "ja-JP",
        cacheNamespace = "jp_v1",
    );

    companion object {
        fun decode(raw: String?): TitleCorrectionMode = values().firstOrNull {
            it.storageValue.equals(raw?.trim(), ignoreCase = true)
        } ?: ORIGINAL_HYPER

        /** Maps the v11 target-language setting into the new profile model. */
        fun fromLegacyTargetLanguage(raw: String?): TitleCorrectionMode = when (
            CatalogLanguagePolicy.normalize(raw)
        ) {
            "zh-CN" -> MAINLAND_CHINA
            "ja-JP" -> JAPAN
            else -> ORIGINAL_HYPER
        }
    }
}
