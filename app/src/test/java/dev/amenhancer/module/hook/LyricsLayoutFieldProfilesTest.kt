package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsLayoutFieldProfilesTest {
    @Test
    fun `resolves official 650 lyrics fields`() {
        val profile = LyricsLayoutFieldProfiles.resolve(OfficialFragment::class.java)

        assertEquals("g0", profile?.binding)
        assertEquals("S", profile?.container)
        assertEquals("Y", profile?.recycler)
        assertEquals("e0", profile?.gradients)
        assertEquals(listOf("x0", "y0"), profile?.synchronizedMetrics)
    }

    @Test
    fun `resolves adapted landscape 650 lyrics fields`() {
        val profile = LyricsLayoutFieldProfiles.resolve(AdaptedFragment::class.java)

        assertEquals("i0", profile?.binding)
        assertEquals("U", profile?.container)
        assertEquals("a0", profile?.recycler)
        assertEquals("g0", profile?.gradients)
        assertEquals(listOf("z0", "A0"), profile?.synchronizedMetrics)
    }

    @Test
    fun `rejects partial lyrics field contracts`() {
        assertNull(LyricsLayoutFieldProfiles.resolve(UnknownFragment::class.java))
    }

    private class Metrics {
        @JvmField var a = 0
        @JvmField var b = 0
        @JvmField var c = 0
    }

    private class OfficialBinding {
        @JvmField var S: Any? = null
        @JvmField var Y: Any? = null
        @JvmField var e0: Any? = null
    }

    private class OfficialFragment {
        @JvmField var g0 = OfficialBinding()
        @JvmField var x0 = Metrics()
        @JvmField var y0 = Metrics()
    }

    private class AdaptedBinding {
        @JvmField var U: Any? = null
        @JvmField var a0: Any? = null
        @JvmField var g0: Any? = null
    }

    private class AdaptedFragment {
        @JvmField var i0 = AdaptedBinding()
        @JvmField var z0 = Metrics()
        @JvmField var A0 = Metrics()
    }

    private class UnknownFragment {
        @JvmField var g0 = Any()
    }
}
