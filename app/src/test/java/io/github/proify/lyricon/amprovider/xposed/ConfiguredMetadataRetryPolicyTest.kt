package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredMetadataRetryPolicyTest {
    @Test
    fun `override store expires configured misses instead of keeping a permanent tombstone`() {
        val store = AppleMetadataOverrideStore()
        store.markConfiguredMiss("cn:SONG:42:42", nowUptimeMillis = 1_000L)

        assertTrue(store.isConfiguredMiss("cn:SONG:42:42", nowUptimeMillis = 1_001L))
        assertFalse(store.isConfiguredMiss("cn:SONG:42:42", nowUptimeMillis = 11_000L))
    }

    @Test
    fun `recent configured miss is suppressed but expires`() {
        assertTrue(
            ConfiguredMetadataRetryPolicy.shouldSkip(
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 1_001L,
            ),
        )
        assertFalse(
            ConfiguredMetadataRetryPolicy.shouldSkip(
                lastMissUptimeMillis = 1_000L,
                nowUptimeMillis = 11_000L,
            ),
        )
    }

    @Test
    fun `clock rollback does not suppress forever`() {
        assertFalse(
            ConfiguredMetadataRetryPolicy.shouldSkip(
                lastMissUptimeMillis = 2_000L,
                nowUptimeMillis = 1_999L,
            ),
        )
    }
}
