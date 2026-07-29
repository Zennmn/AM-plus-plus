package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class DualPaneTargetBehaviorTest {
    @Test
    fun `enabled feature calls only the dual pane capability and preserves its result`() {
        var dualPaneCalls = 0
        var unrelatedCalls = 0
        val context = context(
            enabled = true,
            dualPane = DualPaneTarget {
                dualPaneCalls += 1
                TargetCapabilityInstall.Degraded("optional nav measure unavailable")
            },
            unrelated = {
                unrelatedCalls += 1
                TargetCapabilityInstall.Active("unexpected")
            },
        )

        val result = DualPaneFeature().install(context)

        assertEquals(1, dualPaneCalls)
        assertEquals(0, unrelatedCalls)
        assertEquals(FeatureState.DEGRADED, result.state)
        assertEquals("optional nav measure unavailable", result.message)
    }

    @Test
    fun `disabled feature never touches target adaptation`() {
        var targetCalls = 0
        val countingCapability = {
            targetCalls += 1
            TargetCapabilityInstall.Active("unexpected")
        }
        val context = context(
            enabled = false,
            dualPane = DualPaneTarget(countingCapability),
            unrelated = countingCapability,
        )

        val result = DualPaneFeature().install(context)

        assertEquals(FeatureState.DISABLED, result.state)
        assertEquals(0, targetCalls)
    }

    @Test
    fun `dual pane capability exposes only a parameterless semantic install operation`() {
        val methods = DualPaneTarget::class.java.declaredMethods.toList()

        assertEquals(listOf("install"), methods.map { it.name })
        assertEquals(0, methods.single().parameterCount)
        assertEquals(TargetCapabilityInstall::class.java, methods.single().returnType)
    }

    private fun context(
        enabled: Boolean,
        dualPane: DualPaneTarget,
        unrelated: () -> TargetCapabilityInstall,
    ): HookContext = HookContext(
        config = TargetConfigClient(preferences(enabled)),
        target = TargetAdaptation(
            identity = "test target",
            dualPane = dualPane,
            editorialVideo = EditorialVideoTarget(unrelated),
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget(unrelated),
        ),
    )

    private fun preferences(enabled: Boolean): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAll" -> mapOf("dual_pane_enabled" to enabled)
            "toString" -> "dual-pane-test-preferences"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as SharedPreferences
}
