package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the liquid-glass resource and configuration contract across host forms. */
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
        assertTrue(schema.contains("\"phone_liquid_glass_bottom_gap_dp\""))
        assertTrue(schema.contains("\"phone_liquid_glass_panel_blur_dp\""))
        assertTrue(storage.contains("ampp-embedded-settings"))
        assertTrue(client.contains("valuesProvider"))
        assertFalse(settings.contains("手机 Liquid Glass"))
        assertTrue(settings.contains("phoneLiquidGlassEnabled = it"))
        // The height/blur extras are add-ons of the liquid-glass toggle: their rows must
        // stay gated behind it and their values must live in the settings model.
        assertTrue(models.contains("val phoneLiquidGlassBottomGapDp: Int"))
        assertTrue(models.contains("val phoneLiquidGlassPanelBlurDp: Int"))
        assertTrue(settings.contains("if (settings.phoneLiquidGlassEnabled)"))
        assertTrue(settings.contains("底栏高度"))
        assertTrue(settings.contains("底栏背景模糊强度"))
        // Each gated row carries a small one-tap restore button for its own default.
        assertTrue(settings.contains("\"恢复默认\""))
        assertTrue(settings.contains("defaultValue = GlassPolicy.BOTTOM_DP"))
        assertTrue(settings.contains("defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt()"))
        // The restore button uses the AM++-authored SVG glyph, not the legacy drawable.
        assertTrue(settings.contains("EmbeddedSvgIcon.RestoreDefault"))
    }

    @Test
    fun `keeps the phone form semantics and routes the tablet dual-pane form`() {
        val policy = projectFile("glass/src/main/kotlin/dev/amenhancer/glass/GlassPolicy.kt")
        val runtime = source("dev/amenhancer/module/hook/PhoneGlassRuntime.kt")

        // The pre-form supports() overload survives with its phone-only meaning.
        assertTrue(policy.contains("fun supports(sdk: Int, versionCode: Long, versionName: String, tablet: Boolean)"))
        // The phone path keeps excluding official tablets and keeps its own session.
        assertTrue(runtime.contains("isOfficialTablet"))
        assertTrue(runtime.contains("PhoneGlassSession("))
        // Eligible tablets are routed to the dedicated tablet session.
        assertTrue(runtime.contains("TabletDualPaneGlassSession"))
        // The form and geometry seams live in the Android-free policy.
        assertTrue(policy.contains("GlassHostForm"))
        assertTrue(policy.contains("GlassGeometry"))
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

    @Test
    fun `keeps pager pages under the glass`() {
        val session = source("dev/amenhancer/module/hook/PhoneGlassSession.kt")
        // ViewPager2 lays its pages out inside an internal RecyclerView. Padding that
        // RecyclerView shrinks every page, so the page stops above the glass and the bar
        // samples empty background (Search results looked opaque). The pager host must stay
        // out of the padding targets and previously padded targets must be released.
        assertTrue(session.contains("androidx.viewpager2.widget.ViewPager2"))
        assertTrue(session.contains("!isViewPagerPageHost(view)"))
        assertTrue(session.contains("state.scrollPaddingActive && terminal.none"))
        assertTrue(session.contains("restoreScroll(view)"))
    }
}
