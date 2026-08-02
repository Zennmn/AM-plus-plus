package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentItemAdamIdTest {

    @Test
    fun `apple current item string id is parsed`() {
        assertEquals(42L, parseCurrentItemAdamId("42"))
    }

    @Test
    fun `invalid current item ids fail open`() {
        assertNull(parseCurrentItemAdamId(null))
        assertNull(parseCurrentItemAdamId(""))
        assertNull(parseCurrentItemAdamId("not-a-number"))
        assertNull(parseCurrentItemAdamId("0"))
        assertNull(parseCurrentItemAdamId("-1"))
    }
}
