package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the phone-only liquid-glass resource and configuration contract. */
class PhoneLiquidGlassStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `persists the setting with its embedded phone entry`() {
        val models = source("dev/amenhancer/module/model/ModuleModels.kt")
        val session = source("dev/amenhancer/module/config/EmbeddedConfigurationSession.kt")
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")
        val storage = source("dev/amenhancer/module/config/HostPrivateEmbeddedStorage.kt")
        val settings = source("dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(models.contains("val phoneLiquidGlassEnabled: Boolean = false"))
        assertTrue(session.contains("ModuleSettingsSchema.encodeOrdinarySettings(settings)"))
        assertTrue(session.contains("ModuleSettingsSchema.encodeFontManifest(manifest)"))
        listOf(
            "lyrics_font_enabled",
            "lyrics_font_file_id",
            "lyrics_font_display_name",
            "lyrics_font_size_bytes",
            "lyrics_font_sha256",
        ).forEach { key -> assertTrue(schema.contains("\"$key\"")) }
        assertTrue(schema.contains("\"phone_liquid_glass_enabled\""))
        assertTrue(storage.contains("ampp-embedded-settings"))
        assertTrue(client.contains("valuesProvider"))
        assertFalse(settings.contains("手机 Liquid Glass"))
        assertTrue(settings.contains("phoneLiquidGlassEnabled = it"))
    }

    @Test
    fun `uses api 102 remote preferences and keeps liquid glass fail closed`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val client = source("dev/amenhancer/module/config/TargetConfigClient.kt")

        assertFalse(manifest.contains("ConfigProvider"))
        assertTrue(client.contains("phoneLiquidGlassEnabled = false"))
        assertFalse(client.contains("contentResolver.call"))
    }

    @Test
    fun `reference sources retain their pinned provenance`() {
        val provenance = projectFile("backdrop/UPSTREAM.md")
        assertTrue(provenance.contains("65ab177e90e5c1d8c62e70cf7755841982da65f6"))
        assertTrue(projectFile("backdrop/LICENSE").contains("Apache License"))
        assertTrue(projectFile("settings.gradle.kts").contains(":backdrop"))
    }
}
