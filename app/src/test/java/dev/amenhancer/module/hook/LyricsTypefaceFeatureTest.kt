package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.LyricsFontManifest
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTypefaceFeatureTest {
    @Test
    fun `disabled font never asks the target capability to install`() {
        var calls = 0
        val result = LyricsTypefaceFeature().install(context(
            manifest = LyricsFontManifest.disabled(),
            target = LyricsTypefaceTarget {
                calls += 1
                TargetCapabilityInstall.Active("unexpected")
            },
        ))

        assertEquals(FeatureState.DISABLED, result.state)
        assertEquals(0, calls)
    }

    @Test
    fun `target capability health maps to active and degraded outcomes`() {
        val active = LyricsTypefaceFeature().install(context(
            manifest = validManifest(),
            target = LyricsTypefaceTarget { TargetCapabilityInstall.Active("font installed") },
        ))
        val degraded = LyricsTypefaceFeature().install(context(
            manifest = validManifest(),
            target = LyricsTypefaceTarget { TargetCapabilityInstall.Degraded("remote font unreadable") },
        ))

        assertEquals(FeatureState.ACTIVE, active.state)
        assertEquals(FeatureState.DEGRADED, degraded.state)
        assertEquals("remote font unreadable", degraded.message)
    }

    private fun validManifest() = LyricsFontManifest(
        enabled = true,
        fileId = "font_abc123",
        displayName = "Noto Sans.ttf",
        sizeBytes = 7L,
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
    )

    private fun context(
        manifest: LyricsFontManifest,
        target: LyricsTypefaceTarget,
    ) = HookContext(
        config = TargetConfigClient(preferences(manifest)),
        target = TargetAdaptation(
            identity = "test target",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            lyricsTypeface = target,
        ),
    )

    private fun preferences(manifest: LyricsFontManifest): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAll" -> mapOf(
                "lyrics_font_enabled" to manifest.enabled,
                "lyrics_font_file_id" to manifest.fileId,
                "lyrics_font_display_name" to manifest.displayName,
                "lyrics_font_size_bytes" to manifest.sizeBytes,
                "lyrics_font_sha256" to manifest.sha256,
            )
            "toString" -> "lyrics-typeface-test-preferences"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as SharedPreferences
}
