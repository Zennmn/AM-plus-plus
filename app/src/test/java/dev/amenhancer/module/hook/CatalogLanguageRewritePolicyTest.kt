package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogLanguageRewritePolicyTest {
    @Test
    fun `uses AMTool header language normalization`() {
        assertEquals("zh-Hans", dev.amenhancer.module.config.CatalogLanguagePolicy.headerLanguage("zh-CN"))
        assertEquals("zh-Hant", dev.amenhancer.module.config.CatalogLanguagePolicy.headerLanguage("zh-TW"))
        assertEquals("tr-TR", dev.amenhancer.module.config.CatalogLanguagePolicy.headerLanguage("tr-TR"))
    }

    @Test
    fun `rewrites explicit language fields and adds Apple header`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("Accept-Language" to "zh-CN", "foo" to "bar"),
            "tr-TR",
        )
        assertEquals("tr-TR", rewritten["Accept-Language"])
        assertEquals("bar", rewritten["foo"])
    }

    @Test
    fun `covers the l lang and locale map entries with the raw tag`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("l" to "en-US", "lang" to "en-US", "locale" to "en-US", "other" to "keep"),
            "zh-CN",
        )
        assertEquals("zh-CN", rewritten["l"])
        assertEquals("zh-CN", rewritten["lang"])
        assertEquals("zh-CN", rewritten["locale"])
        assertEquals("keep", rewritten["other"])
    }

    @Test
    fun `matches language keys case insensitively`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("Locale" to "en-US"),
            "tr-TR",
        )
        assertEquals("tr-TR", rewritten["Locale"])
    }

    @Test
    fun `accept language header uses the script mapped value`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("Accept-Language" to "en-US", "lang" to "en-US"),
            "zh-CN",
        )
        assertEquals("zh-Hans", rewritten["Accept-Language"])
        assertEquals("zh-CN", rewritten["lang"])
    }

    @Test
    fun `empty target resolves to the AMTool default`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("foo" to "bar"),
            "",
        )
        assertEquals("tr-TR", rewritten["Accept-Language"])
        assertEquals("bar", rewritten["foo"])
    }

    @Test
    fun `malformed target resolves to the AMTool default`() {
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(
            mapOf("Accept-Language" to "zh-CN"),
            "bad tag",
        )
        assertEquals("tr-TR", rewritten["Accept-Language"])
    }

    @Test
    fun `non-string language values are left untouched`() {
        val original = mapOf("Accept-Language" to 7, "lang" to Any())
        val rewritten = CatalogLanguageRewritePolicy.withTargetLanguage(original, "zh-CN")

        assertSame(original, rewritten)
    }

    @Test
    fun `header map helper only rewrites an existing Accept-Language entry`() {
        val original = mapOf("foo" to "bar")
        val unchanged = CatalogLanguageRewritePolicy.withHeaderLanguageValue(
            original,
            "tr-TR",
        )
        assertSame(original, unchanged)

        val rewritten = CatalogLanguageRewritePolicy.withHeaderLanguageValue(
            mapOf("Accept-Language" to "en-US"),
            "zh-CN",
        )
        assertEquals("zh-Hans", rewritten["Accept-Language"])
    }

    @Test
    fun `raw tag map helper only rewrites an existing language entry`() {
        val original = mapOf("foo" to "bar")
        val unchanged = CatalogLanguageRewritePolicy.withRawTagLanguageValue(
            original,
            "tr-TR",
        )
        assertSame(original, unchanged)

        val rewritten = CatalogLanguageRewritePolicy.withRawTagLanguageValue(
            mapOf("locale" to "en-US", "foo" to "bar"),
            "tr-TR",
        )
        assertEquals("tr-TR", rewritten["locale"])
        assertEquals("bar", rewritten["foo"])
    }
}
