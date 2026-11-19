package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBackdropPoolPolicyTest {
    @Test
    fun `forbids self-capture when consumer equals or descends from backdrop source`() {
        assertFalse(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = true, sourceContainsConsumer = false))
        assertFalse(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = true))
        assertTrue(BackdropSafetyPolicy.isSafeHierarchy(consumerIsSource = false, sourceContainsConsumer = false))
        assertFalse(BackdropSafetyPolicy.isSafeBackdropSource(null, null))
    }
}
