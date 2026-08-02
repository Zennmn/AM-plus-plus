package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsInstallArgumentPolicyTest {
    @Test
    fun `null pointer is accepted for songs without native lyrics`() {
        assertTrue(acceptsLyricsInstallArguments(arrayOf(null), Pointer::class.java))
    }

    @Test
    fun `the expected pointer type is accepted`() {
        assertTrue(acceptsLyricsInstallArguments(arrayOf(Pointer()), Pointer::class.java))
    }

    @Test
    fun `an unrelated non null argument is rejected`() {
        assertFalse(acceptsLyricsInstallArguments(arrayOf("not a pointer"), Pointer::class.java))
    }

    @Test
    fun `missing install argument is rejected`() {
        assertFalse(acceptsLyricsInstallArguments(emptyArray(), Pointer::class.java))
    }

    private class Pointer
}
