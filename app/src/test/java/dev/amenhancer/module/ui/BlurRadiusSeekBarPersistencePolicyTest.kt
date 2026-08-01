package dev.amenhancer.module.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurRadiusSeekBarPersistencePolicyTest {
    @Test
    fun `persists user progress changes that are not touch tracking`() {
        assertTrue(
            BlurRadiusSeekBarPersistencePolicy.shouldPersistProgressChange(
                fromUser = true,
                trackingTouch = false,
            ),
        )
    }

    @Test
    fun `defers touch progress changes until tracking stops`() {
        assertFalse(
            BlurRadiusSeekBarPersistencePolicy.shouldPersistProgressChange(
                fromUser = true,
                trackingTouch = true,
            ),
        )
    }

    @Test
    fun `ignores programmatic progress changes`() {
        assertFalse(
            BlurRadiusSeekBarPersistencePolicy.shouldPersistProgressChange(
                fromUser = false,
                trackingTouch = false,
            ),
        )
    }
}
