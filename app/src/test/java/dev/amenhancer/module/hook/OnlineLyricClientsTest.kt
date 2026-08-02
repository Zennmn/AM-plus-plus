package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.NeteaseEapi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricClientsTest {

    private class FakeTransport(
        var getResult: String? = null,
        var postResult: String? = null,
    ) : LyricHttpTransport {
        val getUrls = mutableListOf<String>()
        val postCalls = mutableListOf<Triple<String, String, Map<String, String>>>()

        override fun get(url: String): String? {
            getUrls += url
            return getResult
        }

        override fun postForm(
            url: String,
            body: String,
            extraHeaders: Map<String, String>,
        ): String? {
            postCalls += Triple(url, body, extraHeaders)
            return postResult
        }
    }

    private val eapiResponseJson = """
        {"code":200,
         "yrc":{"version":13,"lyric":"[190871,1984](190871,361,0)For (191232,172,0)the (191404,134,0)longest (191538,301,0)time"},
         "lrc":{"lyric":"[03:10.871]For the longest time"}}
    """.trimIndent()

    @Test
    fun `amll client fetches the exact adam id ttml url`() {
        val transport = FakeTransport(getResult = "<tt>lyrics</tt>")
        val client = AmllTtmlClient(transport)

        assertEquals("<tt>lyrics</tt>", client.fetch(42L))
        assertEquals(
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/am-lyrics/42.ttml",
            transport.getUrls.single(),
        )
    }

    @Test
    fun `amll client returns null when the transport fails`() {
        val transport = FakeTransport(getResult = null)
        assertNull(AmllTtmlClient(transport).fetch(42L))
    }

    @Test
    fun `netease yrc uses the eapi post with the fixed url and headers`() {
        val transport = FakeTransport(postResult = eapiResponseJson)
        val client = NeteaseLyricClient(transport)

        val document = client.fetchYrc(33_241_436)

        assertNotNull(document)
        assertEquals(1, document!!.lines.size)
        assertEquals("For ", document.lines.single().words.first().text)
        val (url, body, headers) = transport.postCalls.single()
        assertEquals(NeteaseEapi.LYRIC_V1_URL, url)
        assertTrue(body.startsWith("params="))
        assertTrue(body.length > "params=".length)
        assertEquals("https://music.163.com", headers["Origin"])
        assertTrue(headers["User-Agent"].orEmpty().contains("Chrome"))
    }

    @Test
    fun `netease yrc fails open on non 200 responses`() {
        val transport = FakeTransport(
            postResult = """{"code":404,"message":"resource not found"}""",
        )
        assertNull(NeteaseLyricClient(transport).fetchYrc(1L))
    }

    @Test
    fun `netease yrc without a yrc track returns null`() {
        val transport = FakeTransport(
            postResult = """{"code":200,"lrc":{"lyric":"[03:10.871]line only"}}""",
        )
        assertNull(NeteaseLyricClient(transport).fetchYrc(1L))
    }

    @Test
    fun `netease yrc without word timing returns null`() {
        val transport = FakeTransport(
            postResult = """{"code":200,"yrc":{"lyric":"[190871,1984]plain line"}}""",
        )
        assertNull(NeteaseLyricClient(transport).fetchYrc(1L))
    }

    @Test
    fun `netease yrc fails open when the transport fails`() {
        assertNull(NeteaseLyricClient(FakeTransport(postResult = null)).fetchYrc(1L))
        assertNull(NeteaseLyricClient(FakeTransport(postResult = "garbage")).fetchYrc(1L))
    }
}
