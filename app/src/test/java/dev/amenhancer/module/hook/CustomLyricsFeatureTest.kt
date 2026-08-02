package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsFeatureTest {

    @Test
    fun `disabled setting reports disabled and never touches the capability`() {
        var capabilityCalled = false
        val target = TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            customLyrics = CustomLyricsTarget {
                capabilityCalled = true
                TargetCapabilityInstall.Active("installed")
            },
        )

        val result = CustomLyricsFeature().install(
            HookContext(config(customLyricsEnabled = false), target),
        )

        assertEquals(FeatureState.DISABLED, result.state)
        assertFalse(capabilityCalled)
    }

    @Test
    fun `enabled setting maps the capability result`() {
        val target = TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            customLyrics = CustomLyricsTarget {
                TargetCapabilityInstall.Active("replacement installed")
            },
        )

        val result = CustomLyricsFeature().install(
            HookContext(config(customLyricsEnabled = true), target),
        )

        assertEquals(FeatureState.ACTIVE, result.state)
        assertTrue(result.message.contains("replacement installed"))
    }

    @Test
    fun `enabled setting maps a degraded capability as degraded`() {
        val target = TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            customLyrics = CustomLyricsTarget {
                TargetCapabilityInstall.Degraded("symbol missing")
            },
        )

        val result = CustomLyricsFeature().install(
            HookContext(config(customLyricsEnabled = true), target),
        )

        assertEquals(FeatureState.DEGRADED, result.state)
        assertTrue(result.message.contains("symbol missing"))
    }

    private fun config(customLyricsEnabled: Boolean): TargetConfigClient =
        TargetConfigClient(
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getAll" ->
                        mapOf<String, Any>(
                            "custom_lyrics_enabled" to customLyricsEnabled,
                        )
                    "toString" -> "custom-lyrics-feature-test-preferences"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> null
                }
            } as SharedPreferences,
        )
}
