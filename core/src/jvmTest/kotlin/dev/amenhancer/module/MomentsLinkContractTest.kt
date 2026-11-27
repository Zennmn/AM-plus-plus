package dev.amenhancer.module

import org.junit.Assert.*
import org.junit.Test

class MomentsLinkContractTest {
    @org.junit.Test fun chinaStorefrontPreservesEncodedPathTrackIdAndQuery() {
        val original = "https://music.apple.com/tr/album/%E6%AD%8C/123?i=456&ls=1#section"
        val converted = "https://music.apple.com/cn/album/%E6%AD%8C/123?i=456&ls=1#section"
        org.junit.Assert.assertEquals(converted, MomentsLinkContract.withChinaStorefront(original, true))
        org.junit.Assert.assertEquals(original, MomentsLinkContract.withChinaStorefront(original, false))
        org.junit.Assert.assertEquals(converted, MomentsLinkContract.withChinaStorefront(converted, true))
        org.junit.Assert.assertNull(MomentsLinkContract.withChinaStorefront("https://evil.test/tr/song/123", true))
    }
    @Test fun `retains Apple URL path query and country without allowing lookalike hosts`() {
        val url = "https://music.apple.com/cn/album/a/123?i=456"
        assertEquals(url, MomentsLinkContract.extract("Listen to this $url"))
        for (bad in listOf("https://music.apple.com.evil.test/song", "https://music.apple.com@evil.test/song", "javascript:alert(1)", "http://music.apple.com/song", "https://music.apple.com:999/song")) {
            assertNull(MomentsLinkContract.validate(bad))
        }
        assertNull(MomentsLinkContract.validate("https://music.apple.com/" + "x".repeat(4096)))
    }
}
