package dev.amenhancer.module.hook

import android.view.MotionEvent
import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticCollapsedInterceptPolicyTest {
    @Test
    fun `missing resolved method reports a safe install failure`() {
        assertFalse(StaticCollapsedInterceptGuard.install(null))
    }

    @Test
    fun `only installs the verified 6_5_2 target contract`() {
        assertTrue(
            StaticCollapsedInterceptGuard.isSupportedBuild(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L),
            ),
        )
        assertFalse(
            StaticCollapsedInterceptGuard.isSupportedBuild(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            ),
        )
        assertFalse(
            StaticCollapsedInterceptGuard.isSupportedBuild(
                TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1590L),
            ),
        )
    }

    @Test
    fun `bypasses only an eligible transformed target region`() {
        assertTrue(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = true,
                compensationEnabled = true,
                targetCoordinator = true,
                targetChild = true,
                eventInTargetRegion = true,
            ),
        )
    }

    @Test
    fun `passes through portrait and disabled dual pane`() {
        assertFalse(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = false,
                compensationEnabled = true,
                targetCoordinator = true,
                targetChild = true,
                eventInTargetRegion = true,
            ),
        )
    }

    @Test
    fun `passes through unrelated coordinator or child`() {
        assertFalse(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = true,
                compensationEnabled = true,
                targetCoordinator = false,
                targetChild = true,
                eventInTargetRegion = true,
            ),
        )
        assertFalse(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = true,
                compensationEnabled = true,
                targetCoordinator = true,
                targetChild = false,
                eventInTargetRegion = true,
            ),
        )
    }

    @Test
    fun `passes through touches outside the player buttons`() {
        assertFalse(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = true,
                compensationEnabled = true,
                targetCoordinator = true,
                targetChild = true,
                eventInTargetRegion = false,
            ),
        )
    }

    @Test
    fun `passes through when navigation compensation is disabled`() {
        assertFalse(
            StaticCollapsedInterceptPolicy.shouldBypass(
                tabletEligible = true,
                compensationEnabled = false,
                targetCoordinator = true,
                targetChild = true,
                eventInTargetRegion = true,
            ),
        )
    }

    @Test
    fun `button down latches bypass until the gesture ends`() {
        val latch = StaticCollapsedInterceptGestureLatch()
        assertTrue(latch.onDown(true))
        assertTrue(latch.onEvent(MotionEvent.ACTION_MOVE, true, true))
        assertTrue(latch.onEvent(MotionEvent.ACTION_UP, true, true))
        assertFalse(latch.onEvent(MotionEvent.ACTION_MOVE, true, true))
    }

    @Test
    fun `disabling a gate clears the active gesture latch`() {
        val latch = StaticCollapsedInterceptGestureLatch()
        assertTrue(latch.onDown(true))
        assertFalse(latch.onEvent(MotionEvent.ACTION_MOVE, true, false))
        assertFalse(latch.onEvent(MotionEvent.ACTION_MOVE, true, true))
    }
}
