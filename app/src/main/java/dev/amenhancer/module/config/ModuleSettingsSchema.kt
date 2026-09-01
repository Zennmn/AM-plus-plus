package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings

internal object ModuleSettingsSchema {
    /** Keys removed by the profile migration. */
    internal val obsoleteKeys: Set<String> = setOf(KEY_TITLE_CORRECTION_TARGET_LANGUAGE)

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
        cjkKaraokeAnimationEnabled = values.boolean(
            KEY_CJK_KARAOKE_ANIMATION_ENABLED,
            default = true,
        ),
        navigationCompensationEnabled = values.boolean(
            KEY_NAVIGATION_COMPENSATION,
            default = false,
        ),
        lyricBlurRadiusOffsetPx = values.number(KEY_LYRIC_BLUR_RADIUS_OFFSET)
            ?.coerceIn(
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ) ?: 0,
        appleMusicDpiOverrideDpi = ModuleSettings.normalizeAppleMusicDpi(
            values.number(KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI) ?: ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
        ),
        titleCorrectionEnabled = values.boolean(
            KEY_TITLE_CORRECTION_ENABLED,
            default = false,
        ),
        titleCorrectionMode = values.titleCorrectionMode(),
        customLyricsEnabled = values.boolean(
            KEY_CUSTOM_LYRICS_ENABLED,
            default = values.boolean(KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT, default = false),
        ),
        automaticLyricsEnabled = values.boolean(KEY_AUTOMATIC_LYRICS_ENABLED, default = true),
        fontManifest = values.fontManifest(),
        customLyricsManifest = values.customLyricsManifest(),
        schemaVersion = values.number(KEY_SCHEMA_VERSION)
            ?: ModuleConstants.CONFIG_SCHEMA_VERSION,
    )

    fun encode(settings: ModuleSettings): Map<String, Any> =
        encodeOrdinarySettings(settings) +
            encodeFontManifest(settings.fontManifest) +
            encodeCustomLyricsManifest(settings.customLyricsManifest)

    /**
     * Runtime write map for ordinary settings only. Never carries the
     * lyrics_font_* or custom_lyrics_manifest keys, so a stale ModuleSettings
     * captured before a remote-file transaction cannot overwrite a manifest
     * committed afterwards.
     */
    fun encodeOrdinarySettings(settings: ModuleSettings): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            KEY_DUAL_PANE to settings.dualPaneEnabled,
            KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET to settings.disableEditorialVideoOnTablet,
            KEY_PHONE_LIQUID_GLASS to settings.phoneLiquidGlassEnabled,
            KEY_FUTURE_BLUR to settings.futureBlurEnabled,
            KEY_CJK_KARAOKE_ANIMATION_ENABLED to settings.cjkKaraokeAnimationEnabled,
            KEY_NAVIGATION_COMPENSATION to settings.navigationCompensationEnabled,
            KEY_LYRIC_BLUR_RADIUS_OFFSET to settings.lyricBlurRadiusOffsetPx.coerceIn(
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ),
            KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI to ModuleSettings.normalizeAppleMusicDpi(
                settings.appleMusicDpiOverrideDpi,
            ),
            KEY_TITLE_CORRECTION_ENABLED to settings.titleCorrectionEnabled,
            KEY_TITLE_CORRECTION_MODE to settings.titleCorrectionMode.storageValue,
            KEY_CUSTOM_LYRICS_ENABLED to settings.customLyricsEnabled,
            KEY_AUTOMATIC_LYRICS_ENABLED to settings.automaticLyricsEnabled,
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

    fun encodeCustomLyricsManifest(manifest: CustomLyricsManifest): Map<String, Any> =
        linkedMapOf(KEY_CUSTOM_LYRICS_MANIFEST to CustomLyricsManifestCodec.encode(manifest))

    /**
     * Pointer keys point at the remote index file. They are index state, not
     * settings: never emitted by [encode] or [encodeOrdinarySettings], so an
     * ordinary settings write can never overwrite a published index pointer.
     */
    fun encodeIndexPointer(pointer: CustomLyricsIndexPointer): Map<String, Any> =
        linkedMapOf(
            KEY_CUSTOM_LYRICS_INDEX_FILE_ID to pointer.fileId,
            KEY_CUSTOM_LYRICS_INDEX_GENERATION to pointer.generation,
            KEY_CUSTOM_LYRICS_INDEX_SHA256 to pointer.sha256,
            KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES to pointer.sizeBytes,
        )

    /** Fails closed: a pointer must be complete, well-formed, and published at least once. */
    fun decodeIndexPointer(values: Map<String, *>): CustomLyricsIndexPointer? {
        val fileId = values.string(KEY_CUSTOM_LYRICS_INDEX_FILE_ID)
        val generation = values.long(KEY_CUSTOM_LYRICS_INDEX_GENERATION)
        val sha256 = values.string(KEY_CUSTOM_LYRICS_INDEX_SHA256)
        val sizeBytes = values.long(KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES)
        if (generation == null || generation < 1L) return null
        if (sizeBytes == null || sizeBytes !in 1L..CustomLyricsManifestPolicy.MAX_INDEX_BYTES.toLong()) {
            return null
        }
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) return null
        if (!CustomLyricsManifestPolicy.isValidSha256(sha256)) return null
        return CustomLyricsIndexPointer(
            fileId = fileId,
            generation = generation,
            sha256 = sha256.lowercase(),
            sizeBytes = sizeBytes,
        )
    }

    fun hasIndexPointerValues(values: Map<String, *>): Boolean =
        indexPointerKeys.any(values::containsKey)

    /** Avoid turning an unrelated/empty remote group into a completed migration. */
    fun hasMigratableValues(values: Map<String, *>): Boolean =
        values.keys.any { it in settingKeys || it in obsoleteKeys || it in indexPointerKeys }

    /** Legacy v1 preference-string manifest, kept for pre-migration reads. */
    fun decodeLegacyCustomLyricsManifest(values: Map<String, *>): CustomLyricsManifest =
        CustomLyricsManifestCodec.decode(values.string(KEY_CUSTOM_LYRICS_MANIFEST))

    fun legacyCustomLyricsManifestRaw(values: Map<String, *>): String =
        values.string(KEY_CUSTOM_LYRICS_MANIFEST)

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

    private fun Map<String, *>.customLyricsManifest(): CustomLyricsManifest =
        decodeLegacyCustomLyricsManifest(this)

    private fun Map<String, *>.titleCorrectionMode(): TitleCorrectionMode {
        val storedMode = string(KEY_TITLE_CORRECTION_MODE)
        if (storedMode.isNotBlank()) return TitleCorrectionMode.decode(storedMode)
        if (!boolean(KEY_TITLE_CORRECTION_ENABLED, default = false)) {
            return TitleCorrectionMode.ORIGINAL_HYPER
        }
        return TitleCorrectionMode.fromLegacyTargetLanguage(
            string(KEY_TITLE_CORRECTION_TARGET_LANGUAGE),
        )
    }

    /**
     * Returns the host-local values required before removing the v11 target
     * language key.  This is intentionally independent of the schema version:
     * an already-initialized embedded store skips remote migration, so it must
     * still be able to upgrade its own legacy value in place.
     */
    internal fun legacyTitleCorrectionMigrationValues(values: Map<String, *>): Map<String, Any> {
        if (!values.containsKey(KEY_TITLE_CORRECTION_TARGET_LANGUAGE) ||
            values.string(KEY_TITLE_CORRECTION_MODE).isNotBlank()
        ) {
            return emptyMap()
        }
        return linkedMapOf(
            KEY_TITLE_CORRECTION_MODE to values.titleCorrectionMode().storageValue,
            KEY_SCHEMA_VERSION to ModuleConstants.CONFIG_SCHEMA_VERSION,
        )
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
        // Deliberately no AMTool key migration here: AMTool 1.2's
        // modify_locale / modify_locale_target_tag live in the private
        // "module_settings" file of the separate com.mukapp.applemusictool
        // app and are unreadable from AM++ (see AMTOOL_MODIFY_LOCALE_KEY).
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
        KEY_CJK_KARAOKE_ANIMATION_ENABLED,
        KEY_NAVIGATION_COMPENSATION,
        KEY_LYRIC_BLUR_RADIUS_OFFSET,
        KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI,
        KEY_TITLE_CORRECTION_ENABLED,
        KEY_TITLE_CORRECTION_MODE,
        KEY_TITLE_CORRECTION_TARGET_LANGUAGE,
        KEY_CUSTOM_LYRICS_ENABLED,
        KEY_AUTOMATIC_LYRICS_ENABLED,
        KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT,
        KEY_FONT_ENABLED,
        KEY_FONT_FILE_ID,
        KEY_FONT_DISPLAY_NAME,
        KEY_FONT_SIZE_BYTES,
        KEY_FONT_SHA256,
        KEY_CUSTOM_LYRICS_MANIFEST,
    )

    private val indexPointerKeys = setOf(
        KEY_CUSTOM_LYRICS_INDEX_FILE_ID,
        KEY_CUSTOM_LYRICS_INDEX_GENERATION,
        KEY_CUSTOM_LYRICS_INDEX_SHA256,
        KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES,
    )

    private const val KEY_DUAL_PANE = "dual_pane_enabled"
    private const val KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET =
        "disable_editorial_video_on_tablet"
    private const val KEY_PHONE_LIQUID_GLASS = "phone_liquid_glass_enabled"
    private const val KEY_FUTURE_BLUR = "future_blur_enabled"
    private const val KEY_CJK_KARAOKE_ANIMATION_ENABLED = "cjk_karaoke_animation_enabled"
    private const val KEY_NAVIGATION_COMPENSATION = "navigation_compensation_enabled"
    private const val KEY_LYRIC_BLUR_RADIUS_OFFSET = "lyric_blur_radius_offset_px"
    private const val KEY_APPLE_MUSIC_DPI_OVERRIDE_DPI = "apple_music_dpi_override_dpi"
    private const val KEY_TITLE_CORRECTION_ENABLED = "title_correction_enabled"
    private const val KEY_TITLE_CORRECTION_MODE = "title_correction_mode"
    private const val KEY_TITLE_CORRECTION_TARGET_LANGUAGE = "title_correction_target_language"
    private const val KEY_CUSTOM_LYRICS_ENABLED = "custom_lyrics_enabled"
    private const val KEY_AUTOMATIC_LYRICS_ENABLED = "automatic_lyrics_enabled"
    private const val KEY_LEGACY_ONLINE_LYRIC_REPLACEMENT = "online_lyric_replacement_enabled"
    private const val KEY_FONT_ENABLED = "lyrics_font_enabled"
    private const val KEY_FONT_FILE_ID = "lyrics_font_file_id"
    private const val KEY_FONT_DISPLAY_NAME = "lyrics_font_display_name"
    private const val KEY_FONT_SIZE_BYTES = "lyrics_font_size_bytes"
    private const val KEY_FONT_SHA256 = "lyrics_font_sha256"
    private const val KEY_CUSTOM_LYRICS_MANIFEST = "custom_lyrics_manifest"
    private const val KEY_CUSTOM_LYRICS_INDEX_FILE_ID = "custom_lyrics_index_file_id"
    private const val KEY_CUSTOM_LYRICS_INDEX_GENERATION = "custom_lyrics_index_generation"
    private const val KEY_CUSTOM_LYRICS_INDEX_SHA256 = "custom_lyrics_index_sha256"
    private const val KEY_CUSTOM_LYRICS_INDEX_SIZE_BYTES = "custom_lyrics_index_size_bytes"
    private const val KEY_SCHEMA_VERSION = "schema_version"

    /**
     * AMTool 1.2's own config keys, verified from AMTool_1.2.apk: the
     * `com.mukapp.applemusictool` module stores exactly `modify_locale` and
     * `modify_locale_target_tag` in ITS private `module_settings`
     * SharedPreferences and never writes them into the host's preferences.
     *
     * These constants are documentation only.  Android app-data isolation
     * means neither the AM++ settings process nor the Apple Music hook
     * process can open another package's private storage, so a real read
     * migration is not implementable: [decode] ignores the keys, [encode]
     * never emits them, and they must NOT be added to [settingKeys] — doing
     * so would make an AMTool-owned key look like a migrated AM++ setting.
     * A user moving from AMTool to AM++ re-enters the target language once.
     */
    internal const val AMTOOL_MODIFY_LOCALE_KEY = "modify_locale"
    internal const val AMTOOL_MODIFY_LOCALE_TARGET_TAG_KEY = "modify_locale_target_tag"
}
