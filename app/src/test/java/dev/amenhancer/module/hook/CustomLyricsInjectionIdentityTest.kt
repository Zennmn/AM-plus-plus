package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomLyricsInjectionIdentityTest {
    @Test
    fun `null native pointer uses the published player item when fragment identity is stale`() {
        assertEquals(
            7335408332109193189L,
            selectLyricsInjectionAdamId(
                original = null,
                fragmentAdamId = 182861090L,
                publishedAdamId = 7335408332109193189L,
            ),
        )
    }

    @Test
    fun `non-null native pointer keeps the fragment identity`() {
        assertEquals(
            182861090L,
            selectLyricsInjectionAdamId(
                original = Any(),
                fragmentAdamId = 182861090L,
                publishedAdamId = 7335408332109193189L,
            ),
        )
    }
}
