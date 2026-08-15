package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the cache's deterministic, thread-safe access-order core. */
class CatalogTitleCacheLruTest {

    @Test
    fun `get makes an entry most recently used before eviction`() {
        val cache = AccessOrderLruMap<String, String>(2)
        assertEquals(emptyList<String>(), cache.put("a", "A"))
        assertEquals(emptyList<String>(), cache.put("b", "B"))
        assertEquals(listOf("a", "b"), cache.orderedKeys())

        assertEquals("A", cache["a"])
        assertEquals(listOf("b", "a"), cache.orderedKeys())
        assertEquals(listOf("b"), cache.put("c", "C"))

        assertFalse(cache.containsKey("b"))
        assertTrue(cache.containsKey("a"))
        assertEquals(listOf("c", "a"), cache.orderedKeys())
    }

    @Test
    fun `successful contains check also updates recency`() {
        val cache = AccessOrderLruMap<String, String>(2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertTrue(cache.containsKey("a"))
        assertEquals(listOf("b", "a"), cache.orderedKeys())
        assertEquals(listOf("b"), cache.put("c", "C"))
        assertFalse(cache.containsKey("b"))
    }

    @Test
    fun `replacement refreshes recency without growing the map`() {
        val cache = AccessOrderLruMap<String, String>(2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals(emptyList<String>(), cache.put("a", "A2"))
        assertEquals(2, cache.size)
        assertEquals(listOf("b", "a"), cache.orderedKeys())
        assertEquals("A2", cache["a"])
    }
}
