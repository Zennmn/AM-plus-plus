package dev.amenhancer.module.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleCorrectionModeTest {
    @Test
    fun `profiles map to their fixed storefront and language`() {
        assertEquals("cn", TitleCorrectionMode.MAINLAND_CHINA.catalogStorefront)
        assertEquals("zh-CN", TitleCorrectionMode.MAINLAND_CHINA.catalogLanguage)
        assertEquals("jp", TitleCorrectionMode.JAPAN.catalogStorefront)
        assertEquals("ja-JP", TitleCorrectionMode.JAPAN.catalogLanguage)
        assertNull(TitleCorrectionMode.ORIGINAL_HYPER.catalogLanguage)
    }

    @Test
    fun `legacy target language migration only recognizes mainland and japan`() {
        assertEquals(
            TitleCorrectionMode.MAINLAND_CHINA,
            TitleCorrectionMode.fromLegacyTargetLanguage("zh_cn"),
        )
        assertEquals(
            TitleCorrectionMode.JAPAN,
            TitleCorrectionMode.fromLegacyTargetLanguage("ja-JP"),
        )
        assertEquals(
            TitleCorrectionMode.ORIGINAL_HYPER,
            TitleCorrectionMode.fromLegacyTargetLanguage("ko-KR"),
        )
    }

}
