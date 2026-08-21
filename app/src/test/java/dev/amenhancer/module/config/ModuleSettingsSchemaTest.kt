package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleSettingsSchemaTest {
    @Test
    fun `empty values decode to the documented defaults`() {
        assertEquals(
            ModuleSettings(
                dualPaneEnabled = true,
                disableEditorialVideoOnTablet = true,
                phoneLiquidGlassEnabled = false,
                futureBlurEnabled = true,
                cjkKaraokeAnimationEnabled = true,
                navigationCompensationEnabled = false,
                lyricBlurRadiusOffsetPx = 0,
                titleCorrectionEnabled = false,
                schemaVersion = ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()),
        )
    }

    @Test
    fun `encoding writes every setting with the current schema version`() {
        val encoded = ModuleSettingsSchema.encode(
            ModuleSettings(
                dualPaneEnabled = false,
                disableEditorialVideoOnTablet = false,
                phoneLiquidGlassEnabled = true,
                futureBlurEnabled = false,
                lyricBlurRadiusOffsetPx = 6,
                schemaVersion = 1,
            ),
        )

        assertEquals(
            mapOf(
                "dual_pane_enabled" to false,
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to true,
                "future_blur_enabled" to false,
                "cjk_karaoke_animation_enabled" to true,
                "navigation_compensation_enabled" to false,
                "lyric_blur_radius_offset_px" to 6,
                "title_correction_enabled" to false,
                "title_correction_target_language" to "tr-TR",
                "custom_lyrics_enabled" to false,
                "lyrics_font_enabled" to false,
                "lyrics_font_file_id" to "",
                "lyrics_font_display_name" to "",
                "lyrics_font_size_bytes" to 0L,
                "lyrics_font_sha256" to "",
                "custom_lyrics_manifest" to CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()),
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            encoded,
        )
    }

    @Test
    fun `an empty remote store upgrades from legacy values`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = emptyMap<String, Any?>(),
            legacyValues = mapOf(
                "dual_pane_enabled" to false,
                "phone_liquid_glass_enabled" to true,
            ),
        )

        assertEquals(
            mapOf(
                "dual_pane_enabled" to false,
                "disable_editorial_video_on_tablet" to true,
                "phone_liquid_glass_enabled" to true,
                "future_blur_enabled" to true,
                "cjk_karaoke_animation_enabled" to true,
                "navigation_compensation_enabled" to false,
                "lyric_blur_radius_offset_px" to 0,
                "title_correction_enabled" to false,
                "title_correction_target_language" to "tr-TR",
                "custom_lyrics_enabled" to false,
                "lyrics_font_enabled" to false,
                "lyrics_font_file_id" to "",
                "lyrics_font_display_name" to "",
                "lyrics_font_size_bytes" to 0L,
                "lyrics_font_sha256" to "",
                "custom_lyrics_manifest" to CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()),
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            upgraded,
        )
    }

    @Test
    fun `a current remote schema does not trigger a rewrite`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
                "dual_pane_enabled" to false,
            ),
            legacyValues = mapOf("dual_pane_enabled" to true),
        )

        assertEquals(null, upgraded)
    }

    @Test
    fun `an old remote schema upgrades its own values instead of legacy values`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 2,
                "dual_pane_enabled" to false,
            ),
            legacyValues = mapOf("dual_pane_enabled" to true),
        )

        assertEquals(false, upgraded?.get("dual_pane_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `malformed values safely fall back without changing valid values`() {
        val decoded = ModuleSettingsSchema.decode(
            mapOf(
                "dual_pane_enabled" to "not-a-boolean",
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to 1,
                "future_blur_enabled" to false,
                "lyric_blur_radius_offset_px" to "too-strong",
                "schema_version" to "three",
            ),
        )

        assertEquals(
            ModuleSettings(
                dualPaneEnabled = true,
                disableEditorialVideoOnTablet = false,
                phoneLiquidGlassEnabled = false,
                futureBlurEnabled = false,
                cjkKaraokeAnimationEnabled = true,
                navigationCompensationEnabled = false,
                lyricBlurRadiusOffsetPx = 0,
                titleCorrectionEnabled = false,
                schemaVersion = ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            decoded,
        )
    }

    @Test
    fun `a future schema is never downgraded`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf("schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION + 1),
            legacyValues = mapOf("dual_pane_enabled" to false),
        )

        assertEquals(null, upgraded)
    }

    @Test
    fun `blur radius offset is clamped to the supported range`() {
        assertEquals(
            ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ModuleSettingsSchema.decode(
                mapOf("lyric_blur_radius_offset_px" to 99),
            ).lyricBlurRadiusOffsetPx,
        )
        assertEquals(
            ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
            ModuleSettingsSchema.decode(
                mapOf("lyric_blur_radius_offset_px" to -99),
            ).lyricBlurRadiusOffsetPx,
        )
    }

    @Test
    fun `custom lyrics defaults to disabled and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).customLyricsEnabled,
        )
        assertEquals(
            false,
            ModuleSettingsSchema.decode(
                mapOf("custom_lyrics_enabled" to "not-a-boolean"),
            ).customLyricsEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(customLyricsEnabled = true),
        )
        assertEquals(true, encoded["custom_lyrics_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).customLyricsEnabled,
        )
    }

    @Test
    fun `title correction defaults off and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).titleCorrectionEnabled,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(titleCorrectionEnabled = true),
        )
        assertEquals(true, encoded["title_correction_enabled"])
        assertEquals(true, ModuleSettingsSchema.decode(encoded).titleCorrectionEnabled)
    }

    @Test
    fun `target language normalizes and invalid values fall back to automatic`() {
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(titleCorrectionTargetLanguage = "tr_TR"),
        )
        assertEquals("tr-TR", encoded["title_correction_target_language"])
        assertEquals(
            "tr-TR",
            ModuleSettingsSchema.decode(encoded).titleCorrectionTargetLanguage,
        )
        assertEquals(
            "",
            ModuleSettingsSchema.decode(
                mapOf("title_correction_target_language" to "not a language"),
            ).titleCorrectionTargetLanguage,
        )
    }

    @Test
    fun `navigation compensation defaults off and round trips`() {
        assertEquals(
            false,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).navigationCompensationEnabled,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(navigationCompensationEnabled = true),
        )
        assertEquals(true, encoded["navigation_compensation_enabled"])
        assertEquals(
            true,
            ModuleSettingsSchema.decode(encoded).navigationCompensationEnabled,
        )
    }

    @Test
    fun `cjk karaoke animation defaults on and round trips`() {
        assertEquals(
            true,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).cjkKaraokeAnimationEnabled,
        )
        assertEquals(
            true,
            ModuleSettingsSchema.decode(
                mapOf("cjk_karaoke_animation_enabled" to "not-a-boolean"),
            ).cjkKaraokeAnimationEnabled,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(cjkKaraokeAnimationEnabled = false),
        )
        assertEquals(false, encoded["cjk_karaoke_animation_enabled"])
        assertEquals(
            false,
            ModuleSettingsSchema.decode(encoded).cjkKaraokeAnimationEnabled,
        )
    }

    @Test
    fun `an old online lyric setting migrates to the custom lyrics gate`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 5,
                "online_lyric_replacement_enabled" to true,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )

        assertEquals(true, upgraded?.get("custom_lyrics_enabled"))
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `index pointer round trips through its preference keys`() {
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_abc123",
            generation = 7L,
            sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            sizeBytes = 4096L,
        )

        assertEquals(
            pointer,
            ModuleSettingsSchema.decodeIndexPointer(ModuleSettingsSchema.encodeIndexPointer(pointer)),
        )
    }

    @Test
    fun `malformed index pointers fail closed`() {
        val base = mapOf(
            "custom_lyrics_index_file_id" to "index_abc123",
            "custom_lyrics_index_generation" to 1L,
            "custom_lyrics_index_sha256" to "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            "custom_lyrics_index_size_bytes" to 4096L,
        )

        assertNull(ModuleSettingsSchema.decodeIndexPointer(emptyMap<String, Any>()))
        assertNull(ModuleSettingsSchema.decodeIndexPointer(base - "custom_lyrics_index_file_id"))
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_file_id" to "../bad"),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_generation" to 0L),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_sha256" to "not-a-hash"),
            ),
        )
        assertNull(
            ModuleSettingsSchema.decodeIndexPointer(
                base + ("custom_lyrics_index_size_bytes" to 0L),
            ),
        )
    }

    @Test
    fun `legacy manifest decode reads the v1 preference string`() {
        val values = mapOf(
            "custom_lyrics_manifest" to
                """{"version":1,"entries":[{"appleMusicId":42,"displayName":"Old","fileId":"lyrics_old","sizeBytes":42,"sha256":"0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53","source":"manual","enabled":true}]}""",
        )

        assertEquals(
            listOf(42L),
            ModuleSettingsSchema.decodeLegacyCustomLyricsManifest(values).entries.map { it.appleMusicId },
        )
    }

    @Test
    fun `AMTool module settings keys are documented but never migrated or decoded`() {
        assertEquals("modify_locale", ModuleSettingsSchema.AMTOOL_MODIFY_LOCALE_KEY)
        assertEquals(
            "modify_locale_target_tag",
            ModuleSettingsSchema.AMTOOL_MODIFY_LOCALE_TARGET_TAG_KEY,
        )

        val decoded = ModuleSettingsSchema.decode(
            mapOf(
                "modify_locale" to true,
                "modify_locale_target_tag" to "zh-CN",
            ),
        )
        assertEquals(false, decoded.titleCorrectionEnabled)
        assertEquals("tr-TR", decoded.titleCorrectionTargetLanguage)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(decoded)
        assertFalse(encoded.containsKey("modify_locale"))
        assertFalse(encoded.containsKey("modify_locale_target_tag"))

        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "modify_locale" to true,
                "modify_locale_target_tag" to "zh-CN",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )
        assertFalse(upgraded!!.containsKey("modify_locale"))
        assertFalse(upgraded.containsKey("modify_locale_target_tag"))
        assertEquals("tr-TR", upgraded["title_correction_target_language"])
    }
}
