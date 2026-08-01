package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFontManifestSchemaTest {
    @Test
    fun `missing font values decode to the original font`() {
        val settings = ModuleSettingsSchema.decode(emptyMap<String, Any?>())

        assertFalse(settings.fontManifest.enabled)
        assertEquals(LyricsFontManifest.disabled(), settings.fontManifest)
        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, settings.schemaVersion)
    }

    @Test
    fun `encoding keeps the manifest inside shared preferences primitives`() {
        val encoded = ModuleSettingsSchema.encode(
            ModuleSettings(
                fontManifest = LyricsFontManifest(
                    enabled = true,
                    fileId = "font_abc123",
                    displayName = "Noto Sans.ttf",
                    sizeBytes = 7L,
                    sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
                ),
            ),
        )

        assertEquals(true, encoded["lyrics_font_enabled"])
        assertEquals("font_abc123", encoded["lyrics_font_file_id"])
        assertEquals("Noto Sans.ttf", encoded["lyrics_font_display_name"])
        assertEquals(7L, encoded["lyrics_font_size_bytes"])
        assertEquals(
            "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            encoded["lyrics_font_sha256"],
        )
        assertTrue(encoded.values.all { value ->
            value is Boolean || value is Int || value is String || value is Long || value is Set<*>
        })
    }

    @Test
    fun `schema four upgrades without inventing a font`() {
        val upgraded = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 4,
                "dual_pane_enabled" to false,
            ),
            legacyValues = emptyMap<String, Any?>(),
        )

        assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded?.get("schema_version"))
        assertEquals(false, upgraded?.get("dual_pane_enabled"))
        assertEquals(false, upgraded?.get("lyrics_font_enabled"))
        assertEquals(0L, upgraded?.get("lyrics_font_size_bytes"))
    }

    @Test
    fun `invalid enabled manifest values safely fall back to disabled`() {
        val invalidValues = listOf(
            mapOf(
                "lyrics_font_enabled" to true,
                "lyrics_font_file_id" to "font.with.dot",
                "lyrics_font_size_bytes" to 7L,
                "lyrics_font_sha256" to "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            ),
            mapOf(
                "lyrics_font_enabled" to true,
                "lyrics_font_file_id" to "font_valid",
                "lyrics_font_size_bytes" to 0L,
                "lyrics_font_sha256" to "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            ),
            mapOf(
                "lyrics_font_enabled" to true,
                "lyrics_font_file_id" to "font_valid",
                "lyrics_font_size_bytes" to 7L,
                "lyrics_font_sha256" to "not-a-sha256",
            ),
            mapOf(
                "lyrics_font_enabled" to "yes",
                "lyrics_font_file_id" to 42,
                "lyrics_font_size_bytes" to "seven",
                "lyrics_font_sha256" to 42,
            ),
            mapOf(
                "lyrics_font_enabled" to true,
                "lyrics_font_file_id" to "font_valid",
                "lyrics_font_size_bytes" to 7.5,
                "lyrics_font_sha256" to "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            ),
        )

        invalidValues.forEach { values ->
            assertEquals(LyricsFontManifest.disabled(), ModuleSettingsSchema.decode(values).fontManifest)
        }
    }
}
