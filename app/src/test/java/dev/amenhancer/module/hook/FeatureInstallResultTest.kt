package dev.amenhancer.module.hook

import dev.amenhancer.module.model.FeatureState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureInstallResultTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `factories preserve distinct install outcomes`() {
        assertEquals(FeatureState.ACTIVE, FeatureInstallResult.active("installed").state)
        assertEquals(FeatureState.DISABLED, FeatureInstallResult.disabled().state)
        assertEquals(
            FeatureState.UNSUPPORTED,
            FeatureInstallResult.unsupported("requires Android 12").state,
        )
        assertEquals(FeatureState.DEGRADED, FeatureInstallResult.degraded("fallback").state)
        assertEquals(FeatureState.FAILED, FeatureInstallResult.failed("boom").state)
    }

    @Test
    fun `install outcomes reject blank diagnostics`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeatureInstallResult.active("   ")
        }
    }

    @Test
    fun `entry delegates both installation phases to the complete module`() {
        val entry = source("dev/amenhancer/module/hook/HookEntry.kt")
        val installation = source("dev/amenhancer/module/hook/FeatureInstallation.kt")

        assertTrue(entry.contains("FeatureInstallation.install(config, param.classLoader)"))
        listOf(
            "DualPaneResourceHook.install",
            "PhoneLiquidGlassResourceHook.install",
            "LayoutInflationRegistry.install",
            "Application::class.java",
            "FeatureHook",
        ).forEach { leaked -> assertFalse("entry leaked $leaked", entry.contains(leaked)) }

        val orderedFeatures = listOf(
            "DualPaneFeature()",
            "EditorialVideoFeature()",
            "PhoneLiquidGlassFeature()",
            "FutureLyricBlurFeature()",
        ).map(installation::indexOf)
        assertTrue(orderedFeatures.all { it >= 0 })
        assertEquals(orderedFeatures.sorted(), orderedFeatures)
        assertTrue(installation.contains("DualPaneResourceHook.install()"))
        assertTrue(installation.contains("PhoneLiquidGlassResourceHook::install"))
        assertTrue(installation.contains("LayoutInflationRegistry::install"))
    }

    @Test
    fun `lyric blur distinguishes user choice from unsupported platform`() {
        val feature = source("dev/amenhancer/module/hook/FutureLyricBlurFeature.kt")

        assertTrue(feature.contains("FeatureInstallResult.disabled()"))
        assertTrue(feature.contains("FeatureInstallResult.unsupported(\"Requires Android 12 or newer\")"))
        assertFalse(feature.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&"))
    }
}
