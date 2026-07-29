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
    fun `feature seam returns one result and coordinator owns reporting`() {
        val coordinator = source("dev/amenhancer/module/hook/HookCoordinator.kt")
        val featureSources = listOf(
            "DualPaneFeature.kt",
            "EditorialVideoFeature.kt",
            "PhoneLiquidGlassFeature.kt",
            "FutureLyricBlurFeature.kt",
        ).map { source("dev/amenhancer/module/hook/$it") }

        assertTrue(coordinator.contains("fun install(context: HookContext): FeatureInstallResult"))
        assertFalse(coordinator.contains("fun isEnabled(context: HookContext)"))
        assertTrue(coordinator.contains("context.report(feature.key, result)"))
        featureSources.forEach { feature ->
            assertFalse(feature.contains("context.report("))
        }
    }

    @Test
    fun `lyric blur distinguishes user choice from unsupported platform`() {
        val feature = source("dev/amenhancer/module/hook/FutureLyricBlurFeature.kt")

        assertTrue(feature.contains("FeatureInstallResult.disabled()"))
        assertTrue(feature.contains("FeatureInstallResult.unsupported(\"Requires Android 12 or newer\")"))
        assertFalse(feature.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&"))
    }
}
