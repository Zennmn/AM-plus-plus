package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicalTranslationPolicyTest {
    @Test
    fun `skips already CJK text and very short strings`() {
        assertFalse(ClassicalTranslationPolicy.needsTranslation(null))
        assertFalse(ClassicalTranslationPolicy.needsTranslation("Op. 55"))
        assertFalse(ClassicalTranslationPolicy.needsTranslation("这是贝多芬第三交响曲的第一乐章介绍。"))
        assertTrue(ClassicalTranslationPolicy.needsTranslation("Beethoven opens with two decisive E-flat chords."))
    }

    @Test
    fun `formats bilingual output cleanly and stays idempotent`() {
        val orig = "First movement: Allegro con brio"
        val trans = "第一乐章：有活力的快板"
        val combined = ClassicalTranslationPolicy.formatBilingual(orig, trans)
        assertEquals("$orig\n$trans", combined)
        assertEquals(combined, ClassicalTranslationPolicy.formatBilingual(combined, trans))
        assertEquals(orig, ClassicalTranslationPolicy.formatBilingual(orig, ""))
        assertEquals(orig, ClassicalTranslationPolicy.formatBilingual(orig, orig))
    }

    @Test
    fun `cache evicts oldest entries cleanly when full`() {
        val cache = ClassicalTranslationCache(maxEntries = 2)
        cache.put("m1", "First entry text", "第一条")
        cache.put("m1", "Second entry text", "第二条")
        assertEquals("第一条", cache.get("m1", "First entry text"))
        cache.put("m1", "Third entry text", "第三条")
        assertEquals(2, cache.size())
        assertNull(cache.get("m1", "Second entry text"))
        assertEquals("第一条", cache.get("m1", "First entry text"))
        assertEquals("第三条", cache.get("m1", "Third entry text"))
    }
}
