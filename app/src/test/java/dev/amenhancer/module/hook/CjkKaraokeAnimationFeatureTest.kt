package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkKaraokeAnimationFeatureTest {
    @Test
    fun `disabled setting does not install the target hook`() {
        var installed = false
        val result = CjkKaraokeAnimationFeature().install(
            HookContext(
                config = config(false),
                target = target {
                    installed = true
                    TargetCapabilityInstall.Active("installed")
                },
            ),
        )

        assertEquals(FeatureState.DISABLED, result.state)
        assertFalse(installed)
    }

    @Test
    fun `missing setting defaults to enabled and installs the target hook`() {
        var installed = false
        val result = CjkKaraokeAnimationFeature().install(
            HookContext(
                config = config(null),
                target = target {
                    installed = true
                    TargetCapabilityInstall.Active("installed")
                },
            ),
        )

        assertEquals(FeatureState.ACTIVE, result.state)
        assertTrue(installed)
    }

    private fun target(install: () -> TargetCapabilityInstall): TargetAdaptation =
        TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            cjkKaraokeAnimation = CjkKaraokeAnimationTarget(install),
        )

    private fun config(enabled: Boolean?): TargetConfigClient = TargetConfigClient(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> enabled?.let {
                    mapOf<String, Any>("cjk_karaoke_animation_enabled" to it)
                } ?: emptyMap<String, Any>()
                "toString" -> "cjk-karaoke-feature-test-preferences"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SharedPreferences,
    )
}
