package dev.amenhancer.module.config

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLanguagePolicyTest {
    @Test
    fun `normalizes common BCP 47 spellings`() {
        assertEquals("tr-TR", CatalogLanguagePolicy.normalize(" tr_TR "))
        assertEquals("zh-CN", CatalogLanguagePolicy.normalize("zh-cn"))
        assertEquals("en-US", CatalogLanguagePolicy.normalize("en_us"))
    }

    @Test
    fun `empty input falls back to AMTool default`() {
        assertEquals("tr-TR", CatalogLanguagePolicy.normalize(""))
        assertEquals("tr-TR", CatalogLanguagePolicy.normalize("   "))
        assertEquals("tr-TR", CatalogLanguagePolicy.normalize(null))
    }

    @Test
    fun `malformed or oversized language values stay blank for the schema`() {
        assertEquals("", CatalogLanguagePolicy.normalize("not a tag"))
        assertEquals("", CatalogLanguagePolicy.normalize("a"))
        assertEquals("", CatalogLanguagePolicy.normalize("und"))
        assertEquals("", CatalogLanguagePolicy.normalize("x-" + "a".repeat(40)))
    }

    @Test
    fun `runtime resolution falls back to tr-TR instead of no-op`() {
        assertEquals("tr-TR", CatalogLanguagePolicy.resolveTag(""))
        assertEquals("tr-TR", CatalogLanguagePolicy.resolveTag("not a tag"))
        assertEquals("tr-TR", CatalogLanguagePolicy.resolveTag("und"))
        assertEquals("ja-JP", CatalogLanguagePolicy.resolveTag("ja_JP"))
    }

    @Test
    fun `UI boundary rejects empty and invalid input`() {
        assertFalse(CatalogLanguagePolicy.isValid(""))
        assertFalse(CatalogLanguagePolicy.isValid("   "))
        assertFalse(CatalogLanguagePolicy.isValid("not a tag"))
        assertFalse(CatalogLanguagePolicy.isValid("und"))
        assertTrue(CatalogLanguagePolicy.isValid("tr-TR"))
        assertTrue(CatalogLanguagePolicy.isValid("tr-tr"))
        assertTrue(CatalogLanguagePolicy.isValid("zh-cn"))
        assertTrue(CatalogLanguagePolicy.isValid("tr"))
    }

    @Test
    fun `header language applies AMTool script mapping`() {
        assertEquals("zh-Hans", CatalogLanguagePolicy.headerLanguage("zh-CN"))
        assertEquals("zh-Hans", CatalogLanguagePolicy.headerLanguage("zh-SG"))
        assertEquals("zh-Hant", CatalogLanguagePolicy.headerLanguage("zh-TW"))
        assertEquals("zh-Hant", CatalogLanguagePolicy.headerLanguage("zh-HK"))
        assertEquals("zh-Hant", CatalogLanguagePolicy.headerLanguage("zh-MO"))
        assertEquals("tr-TR", CatalogLanguagePolicy.headerLanguage("tr-TR"))
        assertEquals("tr-TR", CatalogLanguagePolicy.headerLanguage(""))
        assertEquals("ja-JP", CatalogLanguagePolicy.headerLanguage("ja-JP"))
    }

    @Test
    fun `AMTool preset languages stay valid and canonical`() {
        val presets = listOf("zh-CN", "zh-TW", "ja-JP", "en-US", "tr-TR")
        presets.forEach { preset ->
            assertTrue(CatalogLanguagePolicy.isValid(preset))
            assertEquals(preset, CatalogLanguagePolicy.normalize(preset))
        }
    }

    @Test
    fun `display name includes canonical tag`() {
        assertEquals(
            "土耳其语（tr-TR）",
            CatalogLanguagePolicy.displayName("tr-TR", Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "土耳其语（tr-TR）",
            CatalogLanguagePolicy.displayName("", Locale.SIMPLIFIED_CHINESE),
        )
    }
}
