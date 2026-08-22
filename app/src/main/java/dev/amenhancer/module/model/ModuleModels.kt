package dev.amenhancer.module.model

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.CatalogLanguagePolicy

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    /** Legacy storage key; Editorial Video suppression now follows dualPaneEnabled. */
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    val futureBlurEnabled: Boolean = true,
    /** Enables the native rush-gradient adaptation for CJK karaoke lyrics. */
    val cjkKaraokeAnimationEnabled: Boolean = true,
    val navigationCompensationEnabled: Boolean = false,
    val lyricBlurRadiusOffsetPx: Int = 0,
    val titleCorrectionEnabled: Boolean = false,
    /** BCP-47 language used for Apple Music Catalog title lookups; AMTool defaults to Turkish. */
    val titleCorrectionTargetLanguage: String = CatalogLanguagePolicy.DEFAULT_TARGET_LANGUAGE,
    val customLyricsEnabled: Boolean = false,
    /** Enables background AMLL/Lunabeat/user-repository lyric completion. */
    val automaticLyricsEnabled: Boolean = true,
    val fontManifest: LyricsFontManifest = LyricsFontManifest.disabled(),
    val customLyricsManifest: CustomLyricsManifest = CustomLyricsManifest.empty(),
    val schemaVersion: Int = ModuleConstants.CONFIG_SCHEMA_VERSION,
) {
    companion object {
        const val MIN_LYRIC_BLUR_RADIUS_OFFSET_PX = -10
        const val MAX_LYRIC_BLUR_RADIUS_OFFSET_PX = 10
    }
}

/** Shared, Android-free description of the font file selected by the user. */
data class LyricsFontManifest(
    val enabled: Boolean = false,
    val fileId: String = "",
    val displayName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
) {
    companion object {
        fun disabled(): LyricsFontManifest = LyricsFontManifest()
    }
}

/** One user-managed Apple Music ID -> TTML file mapping. */
data class CustomLyricsEntry(
    val appleMusicId: Long,
    val displayName: String,
    val fileId: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: String,
    val enabled: Boolean = true,
)
/** Small index shared through remote preferences; TTML bodies stay in remote files. */
data class CustomLyricsManifest(
    val entries: List<CustomLyricsEntry> = emptyList(),
) {
    companion object {
        fun empty(): CustomLyricsManifest = CustomLyricsManifest()
    }
}

object CustomLyricsSources {
    const val MANUAL = "manual"
    const val AUTO_CACHE = "auto-cache"
    const val AMLL = "amll-ttml-db"
    const val AM_LYRICS = "am-lyrics"
    const val LUNABEAT = "lunabeat-ttml-hub"
}

enum class FeatureState {
    ACTIVE,
    DISABLED,
    UNSUPPORTED,
    DEGRADED,
    FAILED,
}

data class FeatureHealth(
    val feature: String,
    val state: FeatureState,
    val message: String,
    val targetVersion: String = "",
)
