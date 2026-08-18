package dev.amenhancer.module.hook

import androidx.preference.AmbiguousPreferenceFragment
import androidx.preference.TestPreferenceFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSettingsFragmentMethodResolverTest {
    @Test
    fun `finds the unique preference setup method in the AndroidX hierarchy`() {
        assertEquals(
            "setPreferences",
            EmbeddedSettingsFragmentMethodResolver.findPreferenceSetup(
                TestPreferenceFragment::class.java,
            )?.name,
        )
    }

    @Test
    fun `rejects an ambiguous preference setup hierarchy`() {
        assertNull(
            EmbeddedSettingsFragmentMethodResolver.findPreferenceSetup(
                AmbiguousPreferenceFragment::class.java,
            ),
        )
    }
}
