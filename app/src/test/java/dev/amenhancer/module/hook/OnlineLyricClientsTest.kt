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
        val getResults: MutableList<String?> = mutableListOf(),
        var postResult: String? = null,
    ) : LyricHttpTransport {
        val getUrls = mutableListOf<String>()
        val postCalls = mutableListOf<Triple<String, String, Map<String, String>>>()

        override fun get(url: String): String? {
            getUrls += url
            return if (getResults.isNotEmpty()) getResults.removeAt(0) else getResult
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
    fun `am lyrics client resolves an id and encodes the indexed path`() {
        val ttml = "<tt><body><p><span>lyrics</span></p></body></tt>"
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ttml.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val index = """
            {"version":1,"layout":"artist-title-id","entries":[
              {"appleMusicId":1609445854,"enabled":true,
               "path":"am-lyrics/八神纯子 - みずいろの雨 - 1609445854.ttml",
               "sizeBytes":${ttml.toByteArray(Charsets.UTF_8).size},"sha256":"$sha256"}
            ]}
        """.trimIndent()
        val transport = FakeTransport(
            getResults = mutableListOf(index, ttml),
        )

        assertEquals(ttml, AmLyricsClient(transport).fetch(1609445854L))
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/Zennmn/am-lyrics/main/index.json",
                "https://raw.githubusercontent.com/Zennmn/am-lyrics/main/" +
                    "am-lyrics/%E5%85%AB%E7%A5%9E%E7%BA%AF%E5%AD%90%20-%20" +
                    "%E3%81%BF%E3%81%9A%E3%81%84%E3%82%8D%E3%81%AE%E9%9B%A8%20-%20" +
                    "1609445854.ttml",
            ),
            transport.getUrls,
        )
    }

    @Test
    fun `am lyrics client resolves alternate ids including a long numeric string`() {
        val ttml = "<tt><body><p><span>lyrics</span></p></body></tt>"
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val index = """
            {"version":1,"layout":"artist-title-id","entries":[
              {"appleMusicId":42,"alternateIds":["7335408332109193189",84],
               "displayName":"Song","enabled":true,"path":"am-lyrics/42.ttml",
               "sizeBytes":${bytes.size},"sha256":"$sha256"}
            ]}
        """.trimIndent()
        val transport = FakeTransport(getResults = mutableListOf(index, ttml))

        assertEquals(ttml, AmLyricsClient(transport).fetch(7_335_408_332_109_193_189L))
        assertEquals(
            listOf(
                AmLyricsClient.AM_LYRICS_INDEX_URL,
                "https://raw.githubusercontent.com/Zennmn/am-lyrics/main/am-lyrics/42.ttml",
            ),
            transport.getUrls,
        )
    }

    @Test
    fun `am lyrics client preserves a bare 19 digit primary id`() {
        val ttml = "<tt><body><p><span>lyrics</span></p></body></tt>"
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val primaryId = 7_335_408_332_109_193_189L
        val index = """
            {"version":1,"layout":"artist-title-id","entries":[
              {"appleMusicId":7335408332109193189,"enabled":true,
               "path":"am-lyrics/7335408332109193189.ttml",
               "sizeBytes":${bytes.size},"sha256":"$sha256"}
            ]}
        """.trimIndent()
        val transport = FakeTransport(getResults = mutableListOf(index, ttml))

        assertEquals(ttml, AmLyricsClient(transport).fetch(primaryId))
    }

    @Test
    fun `am lyrics client rejects a downloaded file whose size or hash differs`() {
        val index = """
            {"version":1,"layout":"artist-title-id","entries":[
              {"appleMusicId":42,"enabled":true,"path":"am-lyrics/42.ttml",
               "sizeBytes":4,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}
            ]}
        """.trimIndent()
        val transport = FakeTransport(getResults = mutableListOf(index, "<tt>wrong</tt>"))

        assertNull(AmLyricsClient(transport).fetch(42L))
        assertEquals(2, transport.getUrls.size)
    }

    @Test
    fun `am lyrics client fails open for missing ids and malformed paths`() {
        val ttml = "<tt><body><p><span>lyrics</span></p></body></tt>"
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val missing = FakeTransport(
            getResult = """{"version":1,"layout":"artist-title-id","entries":[
                {"appleMusicId":42,"path":"am-lyrics/42.ttml","sizeBytes":${bytes.size},"sha256":"$sha256"}
            ]}""",
        )
        assertNull(AmLyricsClient(missing).fetch(43L))
        assertEquals(listOf(AmLyricsClient.AM_LYRICS_INDEX_URL), missing.getUrls)

        val malformed = FakeTransport(
            getResult = """{"version":1,"layout":"artist-title-id","entries":[
                {"appleMusicId":42,"path":"am-lyrics/../outside.ttml","sizeBytes":${bytes.size},"sha256":"$sha256"}
            ]}""",
        )
        assertNull(AmLyricsClient(malformed).fetch(42L))
        assertEquals(listOf(AmLyricsClient.AM_LYRICS_INDEX_URL), malformed.getUrls)
    }

    @Test
    fun `am lyrics client fails open for malformed index and disabled entries`() {
        val malformed = FakeTransport(getResult = "not json")
        assertNull(AmLyricsClient(malformed).fetch(42L))
        assertEquals(listOf(AmLyricsClient.AM_LYRICS_INDEX_URL), malformed.getUrls)

        val disabled = FakeTransport(
            getResult = """{"version":1,"layout":"artist-title-id","entries":[
                {"appleMusicId":42,"enabled":false,"path":"am-lyrics/42.ttml",
                 "sizeBytes":1,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}
            ]}""",
        )
        assertNull(AmLyricsClient(disabled).fetch(42L))
        assertEquals(listOf(AmLyricsClient.AM_LYRICS_INDEX_URL), disabled.getUrls)
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
