package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleSettingsSchemaTest {
    @Test
    fun `dual pane status bar hiding defaults off and round trips`() {
        assertFalse(ModuleSettings().dualPaneHideStatusBarEnabled)
        assertFalse(ModuleSettingsSchema.decode(emptyMap<String, Any>()).dualPaneHideStatusBarEnabled)
        assertFalse(ModuleSettingsSchema.decode(
            mapOf("dual_pane_hide_status_bar_enabled" to "true"),
        ).dualPaneHideStatusBarEnabled)
        for (enabled in listOf(false, true)) {
            val values = ModuleSettingsSchema.encodeOrdinarySettings(
                ModuleSettings(dualPaneHideStatusBarEnabled = enabled),
            )
            assertEquals(enabled, values["dual_pane_hide_status_bar_enabled"])
            assertEquals(enabled, ModuleSettingsSchema.decode(values).dualPaneHideStatusBarEnabled)
        }
    }

    @Test
    fun `startup prewarm defaults on and preserves an explicit opt out`() {
        assertEquals(true, ModuleSettings().startupPagePrewarmEnabled)
        assertEquals(true, ModuleSettingsSchema.decode(emptyMap<String, Any>()).startupPagePrewarmEnabled)
        for (enabled in listOf(false, true)) {
            val values = ModuleSettingsSchema.encodeOrdinarySettings(
                ModuleSettings(startupPagePrewarmEnabled = enabled),
            )
            assertEquals(enabled, values["startup_page_prewarm_enabled"])
            assertEquals(enabled, ModuleSettingsSchema.decode(values).startupPagePrewarmEnabled)
        }
    }

    @Test
    fun `cellular entry defaults off rejects malformed values and round trips`() {
        assertFalse(ModuleSettingsSchema.decode(emptyMap<String, Any>()).forceCellularDataEntryEnabled)
        assertFalse(ModuleSettingsSchema.decode(
            mapOf("force_cellular_data_entry_enabled" to "true"),
        ).forceCellularDataEntryEnabled)
        val values = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(forceCellularDataEntryEnabled = true),
        )
        assertEquals(true, values["force_cellular_data_entry_enabled"])
        assertEquals(true, ModuleSettingsSchema.decode(values).forceCellularDataEntryEnabled)
    }

    @Test
    fun `cellular entry alone identifies a legacy configuration during upgrade`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf("force_cellular_data_entry_enabled" to true),
            legacyValues = mapOf("dual_pane_enabled" to false),
        )!!
        assertEquals(true, upgraded["force_cellular_data_entry_enabled"])
        assertEquals(true, upgraded["dual_pane_enabled"])
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
    }

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
                "dual_pane_hide_status_bar_enabled" to false,
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to true,
                "phone_liquid_glass_bottom_gap_dp" to 16,
                "phone_liquid_glass_panel_blur_dp" to 4,
                "phone_liquid_glass_collapse_enabled" to true,
                "phone_liquid_glass_tablet_collapse_enabled" to false,
                "phone_liquid_glass_collapse_rebound_percent" to 35,
                "phone_liquid_glass_expand_rebound_percent" to 100,
                "phone_liquid_glass_mini_rebound_percent" to 100,
                "phone_liquid_glass_mini_sink_bounce_dp" to 4,
                "future_blur_enabled" to false,
                "cjk_karaoke_animation_enabled" to true,
                "lyric_effects_optimization_enabled" to false,
                "navigation_compensation_enabled" to false,
                "force_cellular_data_entry_enabled" to false,
                "startup_page_prewarm_enabled" to true,
                "lyric_blur_radius_offset_px" to 6,
                "apple_music_dpi_override_dpi" to 0,
                "title_correction_enabled" to false,
                "title_correction_mode" to "original_hyper",
                "custom_lyrics_enabled" to false,
                "automatic_lyrics_enabled" to true,
                "automatic_lyrics_prefer_native" to true,
                "automatic_lyrics_source_order" to "amll-ttml-db,am-lyrics,am-native,lunabeat-ttml-hub",
                "llm_lyrics_enabled" to false,
                "llm_lyrics_base_url" to "",
                "llm_lyrics_api_key" to "",
                "llm_lyrics_model" to "",
                "llm_lyrics_thinking_enabled" to false,
                "llm_lyrics_thinking_effort" to "default",
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
                "dual_pane_hide_status_bar_enabled" to false,
                "disable_editorial_video_on_tablet" to true,
                "phone_liquid_glass_enabled" to true,
                "phone_liquid_glass_bottom_gap_dp" to 16,
                "phone_liquid_glass_panel_blur_dp" to 4,
                "phone_liquid_glass_collapse_enabled" to true,
                "phone_liquid_glass_tablet_collapse_enabled" to false,
                "phone_liquid_glass_collapse_rebound_percent" to 35,
                "phone_liquid_glass_expand_rebound_percent" to 100,
                "phone_liquid_glass_mini_rebound_percent" to 100,
                "phone_liquid_glass_mini_sink_bounce_dp" to 4,
                "future_blur_enabled" to true,
                "cjk_karaoke_animation_enabled" to true,
                "lyric_effects_optimization_enabled" to false,
                "navigation_compensation_enabled" to false,
                "force_cellular_data_entry_enabled" to false,
                "startup_page_prewarm_enabled" to true,
                "lyric_blur_radius_offset_px" to 0,
                "apple_music_dpi_override_dpi" to 0,
                "title_correction_enabled" to false,
                "title_correction_mode" to "original_hyper",
                "custom_lyrics_enabled" to false,
                "automatic_lyrics_enabled" to true,
                "automatic_lyrics_prefer_native" to true,
                "automatic_lyrics_source_order" to "amll-ttml-db,am-lyrics,am-native,lunabeat-ttml-hub",
                "llm_lyrics_enabled" to false,
                "llm_lyrics_base_url" to "",
                "llm_lyrics_api_key" to "",
                "llm_lyrics_model" to "",
                "llm_lyrics_thinking_enabled" to false,
                "llm_lyrics_thinking_effort" to "default",
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
    fun `schema 18 upgrades to 19 with saved LLM configuration and default effort`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 18,
                "dual_pane_enabled" to false,
                "lyric_effects_optimization_enabled" to true,
                "llm_lyrics_enabled" to true,
                "llm_lyrics_base_url" to "https://example.test/v1",
                "llm_lyrics_api_key" to "test-api-key",
                "llm_lyrics_model" to "reasoning-model",
                "llm_lyrics_thinking_enabled" to true,
            ),
            legacyValues = mapOf(
                "llm_lyrics_enabled" to false,
                "llm_lyrics_thinking_enabled" to false,
            ),
        )!!

        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])
        assertEquals(false, upgraded["dual_pane_enabled"])
        assertEquals(true, upgraded["lyric_effects_optimization_enabled"])
        assertEquals(true, upgraded["llm_lyrics_enabled"])
        assertEquals("https://example.test/v1", upgraded["llm_lyrics_base_url"])
        assertEquals("test-api-key", upgraded["llm_lyrics_api_key"])
        assertEquals("reasoning-model", upgraded["llm_lyrics_model"])
        assertEquals(true, upgraded["llm_lyrics_thinking_enabled"])
        assertEquals("default", upgraded["llm_lyrics_thinking_effort"])
    }

    @Test
    fun `schema 18 upgrade retains an explicitly saved LLM thinking effort`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 18,
                "llm_lyrics_thinking_enabled" to false,
                "llm_lyrics_thinking_effort" to "max",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!
        val decoded = ModuleSettingsSchema.decode(upgraded)

        assertEquals("max", upgraded["llm_lyrics_thinking_effort"])
        assertEquals(LlmLyricsThinkingEffort.MAX, decoded.llmLyricsThinkingEffort)
        assertEquals(false, decoded.llmLyricsThinkingEnabled)
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
    fun `a future schema with saved LLM effort is never downgraded or rewritten`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION + 1,
                "llm_lyrics_enabled" to true,
                "llm_lyrics_thinking_enabled" to true,
                "llm_lyrics_thinking_effort" to "high",
            ),
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
    fun `liquid glass extras default to the shipped geometry and round trip`() {
        val defaults = ModuleSettingsSchema.decode(emptyMap<String, Any?>())
        assertEquals(16, defaults.phoneLiquidGlassBottomGapDp)
        assertEquals(4, defaults.phoneLiquidGlassPanelBlurDp)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(phoneLiquidGlassBottomGapDp = 32, phoneLiquidGlassPanelBlurDp = 12),
        )
        assertEquals(32, encoded["phone_liquid_glass_bottom_gap_dp"])
        assertEquals(12, encoded["phone_liquid_glass_panel_blur_dp"])
        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(32, decoded.phoneLiquidGlassBottomGapDp)
        assertEquals(12, decoded.phoneLiquidGlassPanelBlurDp)
    }

    @Test
    fun `liquid glass extras clamp out-of-range values and reject malformed values`() {
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to 99),
            ).phoneLiquidGlassBottomGapDp,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to -1),
            ).phoneLiquidGlassBottomGapDp,
        )
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_panel_blur_dp" to 99),
            ).phoneLiquidGlassPanelBlurDp,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_panel_blur_dp" to -5),
            ).phoneLiquidGlassPanelBlurDp,
        )
        assertEquals(
            16,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_bottom_gap_dp" to "high"),
            ).phoneLiquidGlassBottomGapDp,
        )
    }

    @Test
    fun `Apple Music DPI accepts fixed values, reset, and fails open for malformed values`() {
        assertEquals(
            480,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 480),
            ).appleMusicDpiOverrideDpi,
        )
        assertEquals(
            ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 641),
            ).appleMusicDpiOverrideDpi,
        )
        assertEquals(
            240,
            ModuleSettingsSchema.decode(
                mapOf("apple_music_dpi_override_dpi" to 240),
            ).appleMusicDpiOverrideDpi,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(appleMusicDpiOverrideDpi = 240),
        )
        assertEquals(240, encoded["apple_music_dpi_override_dpi"])
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
    fun `automatic lyrics defaults to enabled and round trips`() {
        assertEquals(
            true,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).automaticLyricsEnabled,
        )
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(automaticLyricsEnabled = false),
        )
        assertEquals(false, encoded["automatic_lyrics_enabled"])
        assertEquals(
            false,
            ModuleSettingsSchema.decode(encoded).automaticLyricsEnabled,
        )
    }

    @Test
    fun `LLM thinking effort persists every saved choice while thinking is disabled`() {
        val choices = listOf(
            LlmLyricsThinkingEffort.DEFAULT to "default",
            LlmLyricsThinkingEffort.LOW to "low",
            LlmLyricsThinkingEffort.HIGH to "high",
            LlmLyricsThinkingEffort.MAX to "max",
        )

        for ((effort, storageValue) in choices) {
            val values = ModuleSettingsSchema.encodeOrdinarySettings(
                ModuleSettings(
                    llmLyricsThinkingEnabled = false,
                    llmLyricsThinkingEffort = effort,
                ),
            )
            val decoded = ModuleSettingsSchema.decode(values)

            assertEquals(storageValue, values["llm_lyrics_thinking_effort"])
            assertEquals(effort, decoded.llmLyricsThinkingEffort)
            assertEquals(false, decoded.llmLyricsThinkingEnabled)
        }
    }

    @Test
    fun `missing malformed and unknown LLM thinking efforts safely default`() {
        assertEquals(
            LlmLyricsThinkingEffort.DEFAULT,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).llmLyricsThinkingEffort,
        )

        for (invalid in listOf<Any?>(true, 4, "medium")) {
            assertEquals(
                LlmLyricsThinkingEffort.DEFAULT,
                ModuleSettingsSchema.decode(
                    mapOf("llm_lyrics_thinking_effort" to invalid),
                ).llmLyricsThinkingEffort,
            )
        }
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
    fun `title correction mode defaults to original and round trips`() {
        assertEquals(
            TitleCorrectionMode.ORIGINAL_HYPER,
            ModuleSettingsSchema.decode(emptyMap<String, Any?>()).titleCorrectionMode,
        )

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(titleCorrectionMode = TitleCorrectionMode.JAPAN),
        )
        assertEquals("japan", encoded["title_correction_mode"])
        assertEquals(TitleCorrectionMode.JAPAN, ModuleSettingsSchema.decode(encoded).titleCorrectionMode)
    }

    @Test
    fun `schema v11 target language migrates to the selected profile`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 11,
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ja_jp",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )

        assertEquals("japan", upgraded?.get("title_correction_mode"))
        assertFalse(upgraded?.containsKey("title_correction_target_language") == true)
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
    }

    @Test
    fun `legacy mainland target maps to mainland profile and unsupported target maps to original`() {
        val mainland = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_target_language" to "zh-CN",
            ),
        )
        val unsupported = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ko-KR",
            ),
        )
        assertEquals(TitleCorrectionMode.MAINLAND_CHINA, mainland.titleCorrectionMode)
        assertEquals(TitleCorrectionMode.ORIGINAL_HYPER, unsupported.titleCorrectionMode)
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
        assertFalse(decoded.titleCorrectionEnabled)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(decoded)
        assertFalse(encoded.containsKey("modify_locale"))
        assertFalse(encoded.containsKey("modify_locale_target_tag"))
        assertEquals("original_hyper", encoded["title_correction_mode"])

        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "modify_locale" to true,
                "modify_locale_target_tag" to "zh-CN",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )
        assertFalse(upgraded!!.containsKey("modify_locale"))
        assertFalse(upgraded.containsKey("modify_locale_target_tag"))
        assertEquals("original_hyper", upgraded["title_correction_mode"])
    }

    @Test
    fun `collapse animation amplitudes default and round trip`() {
        val defaults = ModuleSettingsSchema.decode(emptyMap<String, Any?>())
        // 35% is the recommended, deliberately understated collapse rebound; the expand rebound and
        // the mini's own rebound keep the reference 100%, and the sink dip stays at 4dp.
        assertEquals(35, defaults.phoneLiquidGlassCollapseReboundPercent)
        assertEquals(100, defaults.phoneLiquidGlassExpandReboundPercent)
        assertEquals(100, defaults.phoneLiquidGlassMiniReboundPercent)
        assertEquals(4, defaults.phoneLiquidGlassMiniSinkBounceDp)

        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                phoneLiquidGlassCollapseReboundPercent = 150,
                phoneLiquidGlassExpandReboundPercent = 40,
                phoneLiquidGlassMiniReboundPercent = 60,
                phoneLiquidGlassMiniSinkBounceDp = 7,
            ),
        )
        assertEquals(150, encoded["phone_liquid_glass_collapse_rebound_percent"])
        assertEquals(40, encoded["phone_liquid_glass_expand_rebound_percent"])
        assertEquals(60, encoded["phone_liquid_glass_mini_rebound_percent"])
        assertEquals(7, encoded["phone_liquid_glass_mini_sink_bounce_dp"])
        val decoded = ModuleSettingsSchema.decode(encoded)
        assertEquals(150, decoded.phoneLiquidGlassCollapseReboundPercent)
        assertEquals(40, decoded.phoneLiquidGlassExpandReboundPercent)
        assertEquals(60, decoded.phoneLiquidGlassMiniReboundPercent)
        assertEquals(7, decoded.phoneLiquidGlassMiniSinkBounceDp)

        // 0 is a meaningful value on every one of them (no rebound / no dip), not "unset":
        // decoding must keep it rather than falling back to the default.
        val off = ModuleSettingsSchema.decode(
            mapOf(
                "phone_liquid_glass_collapse_rebound_percent" to 0,
                "phone_liquid_glass_expand_rebound_percent" to 0,
                "phone_liquid_glass_mini_rebound_percent" to 0,
                "phone_liquid_glass_mini_sink_bounce_dp" to 0,
            ),
        )
        assertEquals(0, off.phoneLiquidGlassCollapseReboundPercent)
        assertEquals(0, off.phoneLiquidGlassExpandReboundPercent)
        assertEquals(0, off.phoneLiquidGlassMiniReboundPercent)
        assertEquals(0, off.phoneLiquidGlassMiniSinkBounceDp)
    }

    @Test
    fun `collapse animation amplitudes clamp out-of-range and reject malformed values`() {
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_collapse_rebound_percent" to 9999),
            ).phoneLiquidGlassCollapseReboundPercent,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_collapse_rebound_percent" to -50),
            ).phoneLiquidGlassCollapseReboundPercent,
        )
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_expand_rebound_percent" to 9999),
            ).phoneLiquidGlassExpandReboundPercent,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_expand_rebound_percent" to -50),
            ).phoneLiquidGlassExpandReboundPercent,
        )
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_rebound_percent" to 9999),
            ).phoneLiquidGlassMiniReboundPercent,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_rebound_percent" to -50),
            ).phoneLiquidGlassMiniReboundPercent,
        )
        assertEquals(
            ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_sink_bounce_dp" to 99),
            ).phoneLiquidGlassMiniSinkBounceDp,
        )
        assertEquals(
            ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_sink_bounce_dp" to -3),
            ).phoneLiquidGlassMiniSinkBounceDp,
        )
        // Malformed values fall back to the defaults rather than throwing. The collapse rebound's
        // default is the recommended 35, not the reference 100.
        assertEquals(
            35,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_collapse_rebound_percent" to "lots"),
            ).phoneLiquidGlassCollapseReboundPercent,
        )
        assertEquals(
            100,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_expand_rebound_percent" to "lots"),
            ).phoneLiquidGlassExpandReboundPercent,
        )
        assertEquals(
            100,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_rebound_percent" to "lots"),
            ).phoneLiquidGlassMiniReboundPercent,
        )
        assertEquals(
            4,
            ModuleSettingsSchema.decode(
                mapOf("phone_liquid_glass_mini_sink_bounce_dp" to "deep"),
            ).phoneLiquidGlassMiniSinkBounceDp,
        )
    }
}
