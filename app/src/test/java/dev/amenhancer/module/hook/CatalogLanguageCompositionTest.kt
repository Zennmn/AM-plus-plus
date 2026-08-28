package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogLanguageCompositionTest {
    @Test
    fun ordinaryCatalogRequestReceivesConfiguredTargetLanguage() {
        val source = linkedMapOf<Any?, Any?>(
            "l" to "en-US",
            "Accept-Language" to "en-US",
        )

        val raw = CatalogLanguageRewritePolicy.withRawTagLanguageValue(source, "ja-JP")
        val headers = CatalogLanguageRewritePolicy.withHeaderLanguageValue(source, "ja-JP")

        assertEquals("ja-JP", raw["l"])
        assertEquals("ja", headers["Accept-Language"])
    }

    @Test
    fun hleTokenizedOriginalLanguageRequestBypassesTargetLanguageRewrite() {
        val source = linkedMapOf<Any?, Any?>(
            "l" to "ko-KR",
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM to "hle-42",
        )

        val rewritten = CatalogLanguageRewritePolicy.withRawTagLanguageValue(source, "ja-JP")

        assertSame(source, rewritten)
        assertEquals("ko-KR", rewritten["l"])
    }
}
