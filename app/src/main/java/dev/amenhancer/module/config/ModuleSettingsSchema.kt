package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings

internal object ModuleSettingsSchema {
    fun decode(values: Map<String, *>): ModuleSettings = ModuleSettings(
        dualPaneEnabled = values.boolean(KEY_DUAL_PANE, default = true),
        disableEditorialVideoOnTablet = values.boolean(
            KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
            default = true,
        ),
        phoneLiquidGlassEnabled = values.boolean(
            KEY_PHONE_LIQUID_GLASS,
            default = false,
        ),
        futureBlurEnabled = values.boolean(KEY_FUTURE_BLUR, default = true),
        lyricBlurRadiusOffsetPx = values.number(KEY_LYRIC_BLUR_RADIUS_OFFSET)
            ?.coerceIn(
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ) ?: 0,
        fontManifest = values.fontManifest(),
        schemaVersion = values.number(KEY_SCHEMA_VERSION)
            ?: ModuleConstants.CONFIG_SCHEMA_VERSION,
    )

    fun encode(settings: ModuleSettings): Map<String, Any> =
        encodeOrdinarySettings(settings) + encodeFontManifest(settings.fontManifest)

    /**
     * Runtime write map for ordinary settings only. Never carries the
     * lyrics_font_* manifest keys, so a stale ModuleSettings captured before
     * a font import cannot overwrite a manifest that saveFontManifest
     * committed afterwards.
     */
    fun encodeOrdinarySettings(settings: ModuleSettings): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            KEY_DUAL_PANE to settings.dualPaneEnabled,
            KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET to settings.disableEditorialVideoOnTablet,
            KEY_PHONE_LIQUID_GLASS to settings.phoneLiquidGlassEnabled,
            KEY_FUTURE_BLUR to settings.futureBlurEnabled,
            KEY_LYRIC_BLUR_RADIUS_OFFSET to settings.lyricBlurRadiusOffsetPx.coerceIn(
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ),
        )
        values[KEY_SCHEMA_VERSION] = ModuleConstants.CONFIG_SCHEMA_VERSION
        return values
    }

    fun encodeFontManifest(manifest: LyricsFontManifest): Map<String, Any> {
        val safe = FontManifestPolicy.sanitize(manifest)
        return linkedMapOf(
            KEY_FONT_ENABLED to safe.enabled,
            KEY_FONT_FILE_ID to safe.fileId,
            KEY_FONT_DISPLAY_NAME to safe.displayName,
            KEY_FONT_SIZE_BYTES to safe.sizeBytes,
            KEY_FONT_SHA256 to safe.sha256,
        )
    }

    private fun Map<String, *>.fontManifest(): LyricsFontManifest {
        val raw = LyricsFontManifest(
            enabled = boolean(KEY_FONT_ENABLED, default = false),
            fileId = string(KEY_FONT_FILE_ID),
            displayName = string(KEY_FONT_DISPLAY_NAME),
            sizeBytes = long(KEY_FONT_SIZE_BYTES) ?: 0L,
            sha256 = string(KEY_FONT_SHA256),
        )
        return FontManifestPolicy.sanitize(raw)
    }

    private fun Map<String, *>.string(key: String): String = this[key] as? String ?: ""

    private fun Map<String, *>.long(key: String): Long? = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    fun upgrade(
        storedValues: Map<String, *>,
        legacyValues: Map<String, *>,
    ): Map<String, Any>? {
        val storedVersion = storedValues.number(KEY_SCHEMA_VERSION)
        if (storedVersion != null && storedVersion >= ModuleConstants.CONFIG_SCHEMA_VERSION) return null
        val source = if (storedValues.hasSettingValue()) storedValues else legacyValues
        return encode(decode(source))
    }

    private fun Map<String, *>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default

    private fun Map<String, *>.number(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, *>.hasSettingValue(): Boolean = settingKeys.any(::containsKey)

    private val settingKeys = setOf(
        KEY_DUAL_PANE,
        KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
        KEY_PHONE_LIQUID_GLASS,
        KEY_FUTURE_BLUR,
        KEY_LYRIC_BLUR_RADIUS_OFFSET,
        KEY_FONT_ENABLED,
        KEY_FONT_FILE_ID,
        KEY_FONT_DISPLAY_NAME,
        KEY_FONT_SIZE_BYTES,
        KEY_FONT_SHA256,
    )

    private const val KEY_DUAL_PANE = "dual_pane_enabled"
    private const val KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET =
        "disable_editorial_video_on_tablet"
    private const val KEY_PHONE_LIQUID_GLASS = "phone_liquid_glass_enabled"
    private const val KEY_FUTURE_BLUR = "future_blur_enabled"
    private const val KEY_LYRIC_BLUR_RADIUS_OFFSET = "lyric_blur_radius_offset_px"
    private const val KEY_FONT_ENABLED = "lyrics_font_enabled"
    private const val KEY_FONT_FILE_ID = "lyrics_font_file_id"
    private const val KEY_FONT_DISPLAY_NAME = "lyrics_font_display_name"
    private const val KEY_FONT_SIZE_BYTES = "lyrics_font_size_bytes"
    private const val KEY_FONT_SHA256 = "lyrics_font_sha256"
    private const val KEY_SCHEMA_VERSION = "schema_version"
}
