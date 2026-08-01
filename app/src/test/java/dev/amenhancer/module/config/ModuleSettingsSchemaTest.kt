package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
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
                lyricBlurRadiusOffsetPx = 0,
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
                "lyric_blur_radius_offset_px" to 6,
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
                "lyric_blur_radius_offset_px" to 0,
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
                lyricBlurRadiusOffsetPx = 0,
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
}
