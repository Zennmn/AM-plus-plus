package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletLyricAnchorPolicyTest {
    @Test
    fun `moves the synchronized highlight anchor from stock eight percent to thirty three percent`() {
        assertEquals(
            787,
            TabletLyricAnchorPolicy.highlightOffset(
                currentOffset = 187,
                containerHeight = 2_400,
            ),
        )
    }
}
