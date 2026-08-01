package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphaGradientEdgeFieldProfilesTest {
    @Test
    fun `resolves official 650 edge fields`() {
        val profile = AlphaGradientEdgeFieldProfiles.resolve(
            mapOf(
                "P" to Boolean::class.javaPrimitiveType!!,
                "Q" to Boolean::class.javaPrimitiveType!!,
                "R" to Boolean::class.javaPrimitiveType!!,
                "S" to Boolean::class.javaPrimitiveType!!,
                "T" to Int::class.javaPrimitiveType!!,
                "U" to Int::class.javaPrimitiveType!!,
                "V" to Int::class.javaPrimitiveType!!,
                "W" to Int::class.javaPrimitiveType!!,
            ),
        )

        assertEquals(listOf("P", "Q"), profile?.vertical)
        assertEquals(listOf("R", "S"), profile?.horizontal)
    }

    @Test
    fun `resolves landscape 650 edge fields`() {
        val profile = AlphaGradientEdgeFieldProfiles.resolve(
            mapOf(
                "R" to Boolean::class.javaPrimitiveType!!,
                "S" to Boolean::class.javaPrimitiveType!!,
                "T" to Boolean::class.javaPrimitiveType!!,
                "U" to Boolean::class.javaPrimitiveType!!,
                "V" to Int::class.javaPrimitiveType!!,
                "W" to Int::class.javaPrimitiveType!!,
            ),
        )

        assertEquals(listOf("R", "S"), profile?.vertical)
        assertEquals(listOf("T", "U"), profile?.horizontal)
    }

    @Test
    fun `rejects unknown edge field contracts`() {
        assertNull(
            AlphaGradientEdgeFieldProfiles.resolve(
                mapOf("R" to Boolean::class.javaPrimitiveType!!),
            ),
        )
    }
}
