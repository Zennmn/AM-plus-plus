package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards ConfigStore.saveSettings' write contract: an ordinary runtime write
 * must never carry a remote-file manifest key, so a stale ModuleSettings
 * cannot overwrite a manifest committed by a file transaction afterwards.
 */
class OrdinarySettingsWritePolicyTest {
    private val committedManifest = LyricsFontManifest(
        enabled = true,
        fileId = "font_abc123",
        displayName = "Noto Sans.ttf",
        sizeBytes = 7L,
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
    )

    /** Captured before the import: it still holds the disabled default manifest. */
    private val staleSettings = ModuleSettings(
        dualPaneEnabled = false,
        futureBlurEnabled = false,
        fontManifest = LyricsFontManifest.disabled(),
    )

    private val fontKeys = listOf(
        "lyrics_font_enabled",
        "lyrics_font_file_id",
        "lyrics_font_display_name",
        "lyrics_font_size_bytes",
        "lyrics_font_sha256",
    )
    private val customLyricsManifest = CustomLyricsManifest(
        entries = listOf(
            CustomLyricsEntry(
                appleMusicId = 42L,
                displayName = "Example",
                fileId = "lyrics_abc123",
                sizeBytes = 42L,
                sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
                source = CustomLyricsSources.MANUAL,
            ),
        ),
    )

    @Test
    fun `stale ordinary settings write after a new manifest commit leaves the manifest unchanged`() {
        val currentPreferences = mutableMapOf<String, Any>().apply {
            putAll(ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings()))
            putAll(ModuleSettingsSchema.encodeFontManifest(committedManifest))
            putAll(ModuleSettingsSchema.encodeCustomLyricsManifest(customLyricsManifest))
        }

        val ordinaryWrite = ModuleSettingsSchema.encodeOrdinarySettings(staleSettings)
        val merged = currentPreferences + ordinaryWrite

        assertFalse(
            "ordinary write must not touch any font manifest key",
            ordinaryWrite.keys.any(fontKeys::contains),
        )
        val decoded = ModuleSettingsSchema.decode(merged)
        assertEquals(committedManifest, decoded.fontManifest)
        assertEquals(false, decoded.dualPaneEnabled)
        assertEquals(false, decoded.futureBlurEnabled)
        assertEquals(customLyricsManifest, decoded.customLyricsManifest)
    }

    @Test
    fun `ordinary settings encode carries exactly the runtime toggles and the current schema version`() {
        val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
            ModuleSettings(
                dualPaneEnabled = false,
                disableEditorialVideoOnTablet = false,
                phoneLiquidGlassEnabled = true,
                futureBlurEnabled = false,
                lyricBlurRadiusOffsetPx = 6,
                fontManifest = committedManifest,
            ),
        )

        assertEquals(
            mapOf(
                "dual_pane_enabled" to false,
                "disable_editorial_video_on_tablet" to false,
                "phone_liquid_glass_enabled" to true,
                "future_blur_enabled" to false,
                "lyric_blur_radius_offset_px" to 6,
                "custom_lyrics_enabled" to false,
                "schema_version" to ModuleConstants.CONFIG_SCHEMA_VERSION,
            ),
            encoded,
        )
        assertFalse(encoded.keys.any(fontKeys::contains))
        assertFalse(encoded.containsKey("custom_lyrics_manifest"))
    }

    @Test
    fun `full schema encode keeps writing the font manifest for migration and upgrade`() {
        val encoded = ModuleSettingsSchema.encode(
            ModuleSettings(fontManifest = committedManifest),
        )

        fontKeys.forEach { key -> assertTrue("full encode must keep $key", encoded.containsKey(key)) }
        assertEquals(committedManifest, ModuleSettingsSchema.decode(encoded).fontManifest)
        assertEquals(
            CustomLyricsManifest.empty(),
            ModuleSettingsSchema.decode(encoded).customLyricsManifest,
        )
    }
}
