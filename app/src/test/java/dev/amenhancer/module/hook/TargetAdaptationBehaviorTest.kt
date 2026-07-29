package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetAdaptationBehaviorTest {
    @Test
    fun `capability results map to existing feature outcomes`() {
        val active = EditorialVideoFeature().install(context(
            editorialEnabled = true,
            dualPane = DualPaneTarget { TargetCapabilityInstall.Degraded("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("installed") },
            lyricBlur = BidirectionalLyricBlurTarget { TargetCapabilityInstall.Degraded("unused") },
        ))
        val degraded = EditorialVideoFeature().install(context(
            editorialEnabled = true,
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Degraded("missing symbol") },
            lyricBlur = BidirectionalLyricBlurTarget { TargetCapabilityInstall.Active("unused") },
        ))

        assertEquals(FeatureState.ACTIVE, active.state)
        assertEquals("installed", active.message)
        assertEquals(
            FeatureState.DEGRADED,
            degraded.state,
        )
        assertEquals("missing symbol", degraded.message)
    }

    @Test
    fun `capability results reject blank diagnostics`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetCapabilityInstall.Active("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetCapabilityInstall.Degraded("")
        }
    }

    @Test
    fun `editorial feature calls only editorial capability`() {
        var dualPaneCalls = 0
        var editorialCalls = 0
        var lyricBlurCalls = 0
        val context = context(
            editorialEnabled = true,
            dualPane = DualPaneTarget {
                dualPaneCalls += 1
                TargetCapabilityInstall.Active("dual pane")
            },
            editorialVideo = EditorialVideoTarget {
                editorialCalls += 1
                TargetCapabilityInstall.Degraded("selector unavailable")
            },
            lyricBlur = BidirectionalLyricBlurTarget {
                lyricBlurCalls += 1
                TargetCapabilityInstall.Active("lyric blur")
            },
        )

        val result = EditorialVideoFeature().install(context)

        assertEquals(FeatureState.DEGRADED, result.state)
        assertEquals("selector unavailable", result.message)
        assertEquals(0, dualPaneCalls)
        assertEquals(1, editorialCalls)
        assertEquals(0, lyricBlurCalls)
    }

    @Test
    fun `disabled editorial feature never touches target adaptation`() {
        var targetCalls = 0
        val countingCapability = {
            targetCalls += 1
            TargetCapabilityInstall.Active("unexpected")
        }
        val context = context(
            editorialEnabled = false,
            dualPane = DualPaneTarget(countingCapability),
            editorialVideo = EditorialVideoTarget(countingCapability),
            lyricBlur = BidirectionalLyricBlurTarget(countingCapability),
        )

        val result = EditorialVideoFeature().install(context)

        assertEquals(FeatureState.DISABLED, result.state)
        assertEquals(0, targetCalls)
    }

    @Test
    fun `coordinator maps unexpected capability exception to failed`() {
        val context = context(
            editorialEnabled = true,
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("dual pane") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("editorial") },
            lyricBlur = BidirectionalLyricBlurTarget { TargetCapabilityInstall.Active("lyric blur") },
        )
        val throwingFeature = object : FeatureHook {
            override val key = "throwing-feature"

            override fun install(context: HookContext): FeatureInstallResult =
                error("unexpected adapter failure")
        }
        var reportedKey = ""

        val result = throwingFeature.installSafely(context) { message, _ -> reportedKey = message }

        assertEquals(FeatureState.FAILED, result.state)
        assertEquals("StringBuilder: unexpected adapter failure", result.message)
        assertEquals("throwing-feature failed", reportedKey)
    }

    private fun context(
        editorialEnabled: Boolean,
        dualPane: DualPaneTarget,
        editorialVideo: EditorialVideoTarget,
        lyricBlur: BidirectionalLyricBlurTarget,
    ): HookContext = HookContext(
        config = TargetConfigClient(preferences(editorialEnabled)),
        target = TargetAdaptation(
            identity = "test target",
            dualPane = dualPane,
            editorialVideo = editorialVideo,
            bidirectionalLyricBlur = lyricBlur,
        ),
    )

    private fun preferences(editorialEnabled: Boolean): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAll" -> mapOf("disable_editorial_video_on_tablet" to editorialEnabled)
            "toString" -> "target-adaptation-test-preferences"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as SharedPreferences
}
