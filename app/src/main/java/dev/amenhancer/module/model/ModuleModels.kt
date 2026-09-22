package dev.amenhancer.module.model

import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TitleCorrectionMode

data class ModuleSettings(
    val dualPaneEnabled: Boolean = true,
    /** Legacy storage key; Editorial Video suppression now follows dualPaneEnabled. */
    val disableEditorialVideoOnTablet: Boolean = true,
    val phoneLiquidGlassEnabled: Boolean = false,
    /** Distance in dp between the bottom-bar capsule and the screen bottom; glass-gated. */
    val phoneLiquidGlassBottomGapDp: Int = GlassPolicy.BOTTOM_DP,
    /** Backdrop blur radius in dp shared by the nav panel and the mini-player. */
    val phoneLiquidGlassPanelBlurDp: Int = GlassPolicy.PANEL_BLUR_DP.toInt(),
    val futureBlurEnabled: Boolean = true,
    /** Enables the native rush-gradient adaptation for CJK karaoke lyrics. */
    val cjkKaraokeAnimationEnabled: Boolean = true,
    val navigationCompensationEnabled: Boolean = false,
    val lyricBlurRadiusOffsetPx: Int = 0,
    /** Fixed logical density for Apple Music; 0 follows the system density. */
    val appleMusicDpiOverrideDpi: Int = FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
    val titleCorrectionEnabled: Boolean = false,
    /** Selected metadata profile; ignored while [titleCorrectionEnabled] is false. */
    val titleCorrectionMode: TitleCorrectionMode = TitleCorrectionMode.ORIGINAL_HYPER,
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
        const val FOLLOW_SYSTEM_APPLE_MUSIC_DPI = 0
        const val MIN_APPLE_MUSIC_DPI = 160
        const val MAX_APPLE_MUSIC_DPI = 640
        const val MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP = 48
        const val MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 0
        const val MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP = 24

        fun isValidAppleMusicDpi(value: Int): Boolean =
            value == FOLLOW_SYSTEM_APPLE_MUSIC_DPI || value in MIN_APPLE_MUSIC_DPI..MAX_APPLE_MUSIC_DPI

        fun normalizeAppleMusicDpi(value: Int): Int =
            value.takeIf(::isValidAppleMusicDpi) ?: FOLLOW_SYSTEM_APPLE_MUSIC_DPI

        fun normalizePhoneLiquidGlassBottomGapDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP, MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP)

        fun normalizePhoneLiquidGlassPanelBlurDp(value: Int): Int =
            value.coerceIn(MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP, MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP)
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
