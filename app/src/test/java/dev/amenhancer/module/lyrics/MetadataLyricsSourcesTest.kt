package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.LyricHttpResponse
import dev.amenhancer.module.hook.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLyricsSourcesTest {

    @Test
    fun `candidate matching normalizes unicode and requires an artist overlap`() {
        val query = MetadataLyricsQuery(
            title = "ＡＢＣ (Live)",
            artist = "The Artist",
            album = "Album",
            durationMs = 180_000L,
        )
        val candidates = listOf(
            MetadataLyricsCandidate(
                source = MetadataLyricsSource.QQ_MUSIC,
                externalId = "1",
                title = "ABC",
                artist = "Artist / Guest",
                album = "Album",
                durationMs = 180_100L,
            ),
            MetadataLyricsCandidate(
                source = MetadataLyricsSource.QQ_MUSIC,
                externalId = "2",
                title = "ABC",
                artist = "Other",
                album = "Album",
                durationMs = 180_000L,
            ),
        )

        assertEquals(listOf("1"), MetadataLyricsMatcher.filterAndRank(query, candidates).map { it.externalId })
    }

    @Test
    fun `artist matching does not treat generic words as artist identity`() {
        val query = MetadataLyricsQuery("Song", "The Beatles", durationMs = 180_000L)
        val candidate = MetadataLyricsCandidate(
            source = MetadataLyricsSource.QQ_MUSIC,
            externalId = "weeknd",
            title = "Song",
            artist = "The Weeknd",
            album = "Album",
            durationMs = 180_000L,
        )

        assertTrue(MetadataLyricsMatcher.filterAndRank(query, listOf(candidate)).isEmpty())
    }

    @Test
    fun `qrc parser keeps word timing and aligns translated lines`() {
        val document = MetadataLyricsParser.parseQrc(
            original = "[0,1000]你(0,500)好(500,500)\n[1200,800]世(1200,400)界(1600,400)",
            translated = "[00:00.010]Hello\n[00:01.205]World",
        )

        assertTrue(document != null)
        assertTrue(document!!.wordTimed)
        assertEquals(2, document.lines.size)
        assertEquals("你好", document.lines.first().words.joinToString("") { it.text })
        assertEquals(listOf("Hello", "World"), document.translations)
    }

    @Test
    fun `qrc xml entities are decoded before ttml escaping`() {
        val document = MetadataLyricsParser.parseQrc(
            original = """<Lyric_1 LyricType="1" LyricContent="[0,1000]A &amp; B &lt;tag&gt; &#x1F3B5;(0,1000)"/>""",
            translated = """<Lyric_1 LyricType="1" LyricContent="[0,1000]C &quot;D&quot; &apos;E&apos; &#35;(0,1000)"/>""",
        )

        assertEquals("A & B <tag> 🎵", document?.lines?.single()?.words?.joinToString("") { it.text })
        assertEquals(listOf("C \"D\" 'E' #"), document?.translations)

        val ttml = document?.let(MetadataLyricsTtmlWriter::build)
        assertTrue(ttml?.contains(">A &amp; B &lt;tag&gt; 🎵</span>") == true)
        assertTrue(ttml?.contains("<text for=\"L1\">C &quot;D&quot; &apos;E&apos; #</text>") == true)
        assertFalse(ttml?.contains("&amp;amp;") == true)
        assertFalse(ttml?.contains("&amp;lt;") == true)
    }

    @Test
    fun `yrc parser falls back to line lrc and aligns translation`() {
        val document = MetadataLyricsParser.parseYrc(
            yrc = "not a yrc payload",
            lrc = "[00:01.00]A line\n[00:03.00]Second",
            translated = "[00:01.10]第一句",
        )

        assertTrue(document != null)
        assertFalse(document!!.wordTimed)
        assertEquals(2, document.lines.size)
        assertEquals(listOf("第一句", null), document.translations)
    }

    @Test
    fun `yrc parser preserves ordinary parenthesized word text`() {
        val document = MetadataLyricsParser.parseYrc(
            yrc = "[0,1000](0,500,0)(Oh)(500,500,0)hey",
            lrc = null,
        )

        assertNotNull(document)
        val parsed = document!!
        assertTrue(parsed.hasRealWordTiming())
        assertEquals(listOf("(Oh)", "hey"), parsed.lines.single().words.map { it.text })
        assertEquals(listOf(0L, 500L), parsed.lines.single().words.map { it.startMs })
        assertEquals(listOf(500L, 1000L), parsed.lines.single().words.map { it.endMs })
    }

    @Test
    fun `lrc parser sorts duplicate timestamps before deriving line ends`() {
        val document = MetadataLyricsParser.parseLrcDocument(
            "[00:03.00]Second\n[00:01.00]First",
        )

        assertEquals(1_000L, document?.lines?.first()?.startMs)
        assertEquals(3_000L, document?.lines?.first()?.endMs)
    }

    @Test
    fun `writer fills missing metadata translation with the original line`() {
        val document = MetadataLyricsDocument(
            lines = listOf(
                MetadataLyricsLine(
                    startMs = 0L,
                    endMs = 1_000L,
                    words = listOf(
                        MetadataLyricsWord(0L, 500L, "a&"),
                        MetadataLyricsWord(500L, 1_000L, "<b>"),
                    ),
                ),
                MetadataLyricsLine(
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(MetadataLyricsWord(1_000L, 2_000L, "c")),
                ),
            ),
            translations = listOf("翻译", " // "),
            wordTimed = true,
        )

        val ttml = MetadataLyricsTtmlWriter.build(document)
        assertTrue(ttml != null)
        assertTrue(ttml!!.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("a&amp;"))
        assertTrue(ttml.contains("&lt;b&gt;"))
        assertTrue(ttml.contains("<text for=\"L2\">c</text>"))
        assertFalse(ttml.contains("<text for=\"L2\"> </text>"))
        assertFalse(ttml.contains("<text for=\"L2\"> // </text>"))
        assertTrue(TtmlInputPolicy.isAcceptable(ttml))
    }

    @Test
    fun `automatic metadata resolver checks only the first candidate and enforces one second`() {
        var fetches = 0
        val resolver = AutomaticMetadataLyricsResolver(
            listOf(
                AutomaticMetadataLyricsSource(
                    MetadataLyricsSource.QQ_MUSIC,
                    search = {
                        listOf(
                            candidate(MetadataLyricsSource.QQ_MUSIC, "first", 101_001L),
                            candidate(MetadataLyricsSource.QQ_MUSIC, "second", 100_000L),
                        )
                    },
                    fetch = { fetches += 1; englishWordDocument(translated = true) },
                ),
            ),
        )

        val result = resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L))

        assertEquals(null, result)
        assertEquals(0, fetches)
    }

    @Test
    fun `automatic metadata resolver falls back in source order and accepts translated Word lyrics`() {
        val visited = mutableListOf<MetadataLyricsSource>()
        val resolver = AutomaticMetadataLyricsResolver(
            listOf(
                automaticSource(MetadataLyricsSource.QQ_MUSIC, 102_000L, visited) {
                    englishWordDocument(translated = true)
                },
                automaticSource(MetadataLyricsSource.NETEASE_CLOUD_MUSIC, 100_999L, visited) {
                    englishWordDocument(translated = true)
                },
            ),
        )

        val result = resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L))

        assertEquals(
            listOf(MetadataLyricsSource.QQ_MUSIC, MetadataLyricsSource.NETEASE_CLOUD_MUSIC),
            visited,
        )
        assertEquals(CustomLyricsSources.NETEASE_CLOUD_MUSIC, result?.source)
        assertEquals("Song - Artist", result?.displayName)
        assertTrue(result?.ttml?.contains("itunes:timing=\"Word\"") == true)
    }

    @Test
    fun `automatic metadata sources prefer NetEase before QQ Music`() {
        assertEquals(
            listOf(MetadataLyricsSource.NETEASE_CLOUD_MUSIC, MetadataLyricsSource.QQ_MUSIC),
            automaticMetadataSourceOrder(),
        )
    }

    @Test
    fun `automatic metadata resolver rejects foreign Word lyrics without translation`() {
        val resolver = AutomaticMetadataLyricsResolver(
            listOf(automaticSource(MetadataLyricsSource.QQ_MUSIC, 100_000L) {
                englishWordDocument(translated = false)
            }),
        )

        assertEquals(
            null,
            resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L)),
        )
    }

    @Test
    fun `automatic metadata resolver accepts Chinese Word lyrics without translation`() {
        val resolver = AutomaticMetadataLyricsResolver(
            listOf(automaticSource(MetadataLyricsSource.QQ_MUSIC, 100_000L) {
                MetadataLyricsDocument(
                    lines = listOf(
                        MetadataLyricsLine(
                            0L,
                            1_000L,
                            listOf(
                                MetadataLyricsWord(0L, 500L, "你"),
                                MetadataLyricsWord(500L, 1_000L, "好"),
                            ),
                        ),
                    ),
                    wordTimed = true,
                )
            }),
        )

        assertEquals(
            CustomLyricsSources.QQ_MUSIC,
            resolver.fetch(MetadataLyricsQuery("歌", "歌手", durationMs = 100_000L))?.source,
        )
    }

    @Test
    fun `automatic metadata resolver enforces a hard deadline around a blocked source`() {
        val resolver = AutomaticMetadataLyricsResolver(
            sources = listOf(
                AutomaticMetadataLyricsSource(
                    source = MetadataLyricsSource.QQ_MUSIC,
                    search = {
                        Thread.sleep(2_000L)
                        emptyList()
                    },
                    fetch = { null },
                ),
            ),
            budgetMs = 100L,
        )

        val startedAt = System.nanoTime()
        val result = resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertEquals(null, result)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 750L)
    }

    @Test
    fun `automatic metadata resolver can retry immediately after an interrupt ignoring worker exits`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var calls = 0
        val resolver = AutomaticMetadataLyricsResolver(
            sources = listOf(
                AutomaticMetadataLyricsSource(
                    source = MetadataLyricsSource.QQ_MUSIC,
                    search = {
                        calls += 1
                        if (calls == 1) {
                            firstStarted.countDown()
                            while (releaseFirst.count == 1L) {
                                try {
                                    Thread.sleep(10L)
                                } catch (_: InterruptedException) {
                                    // Simulate a transport that ignores cancellation briefly.
                                }
                            }
                            emptyList()
                        } else {
                            listOf(candidate(MetadataLyricsSource.QQ_MUSIC, "retry", 100_000L))
                        }
                    },
                    fetch = { englishWordDocument(translated = true) },
                ),
            ),
            budgetMs = 20L,
        )

        val first = thread(start = true) {
            resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L))
        }
        try {
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS))
            assertNotNull(resolver.fetch(MetadataLyricsQuery("Song", "Artist", durationMs = 100_000L)))
            releaseFirst.countDown()
            first.join(1_000L)
        } finally {
            releaseFirst.countDown()
            first.join(1_000L)
        }

        assertTrue("worker did not finish", !first.isAlive)
        assertEquals(2, calls)
    }

    @Test
    fun `qq search keeps the song mid separate from the album id`() {
        val transport = FakeTransport(
            """
            {"req_0":{"code":0,"data":{"body":{"item_song":[{
              "id":42,"mid":"song-mid","title":"Song","singer":[{"name":"Artist"}],
              "album":{"name":"Album","mid":"album-mid"},"interval":180
            }]}}}}
            """.trimIndent(),
        )
        val result = QqMusicLyricsClient(transport).search(
            MetadataLyricsQuery("Song", "Artist", "Album"),
        )

        assertEquals(1, result.size)
        assertEquals("42", result.single().externalId)
        assertEquals("song-mid", result.single().externalMid)
        assertTrue(transport.lastBody.orEmpty().contains("DoSearchForQQMusicLite"))
    }

    @Test
    fun `qq direct MusicU search works when Lite session fields are unavailable`() {
        val search = """
            {"req_0":{"code":0,"data":{"body":{"item_song":[{
              "id":42,"mid":"song-mid","title":"Song","singer":[{"name":"Artist"}],
              "album":{"name":"Album"},"interval":180
            }]}}}}
        """.trimIndent()
        val transport = SessionlessTransport(search)
        val client = QqMusicLyricsClient(transport)

        val result = client.search(MetadataLyricsQuery("Song", "Artist", "Album"))

        assertEquals(listOf("42"), result.map { it.externalId })
        assertEquals(1, transport.requests)
        assertEquals("DoSearchForQQMusicLite", transport.method())
        assertFalse(transport.comm().has("uid"))
    }

    @Test
    fun `qq direct request omits a stale cached Lite session`() {
        val directory = java.nio.file.Files.createTempDirectory("ampp-qq-session-direct").toFile()
        try {
            val now = 100L
            val store = FileQqMusicSessionStore(java.io.File(directory, "session.json"))
            assertTrue(store.save(QqMusicSession("stale", "stale-session", "127.0.0.1", 10_000L)))
            val transport = QueueTransport(
                listOf(
                    """
                    {"req_0":{"code":0,"data":{"body":{"item_song":[{
                      "id":502891450,"mid":"002toCV74PgHBw","title":"舞台に立って",
                      "singer":[{"name":"YOASOBI"}],"album":{"name":"舞台に立って"},"interval":207
                    }]}}}}
                    """.trimIndent(),
                ),
            )
            val client = QqMusicLyricsClient(transport, store, nowMs = { now })

            val result = client.search(MetadataLyricsQuery("舞台に立って", "YOASOBI"))

            assertEquals(listOf("502891450"), result.map { it.externalId })
            assertEquals(1, transport.requests)
            val comm = org.json.JSONObject(transport.bodies.single()).getJSONObject("comm")
            assertFalse(comm.has("uid"))
            assertFalse(comm.has("sid"))
            assertFalse(comm.has("userip"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `qq Lite session store persists anonymous data`() {
        val directory = java.nio.file.Files.createTempDirectory("ampp-qq-session").toFile()
        try {
            val store = FileQqMusicSessionStore(java.io.File(directory, "session.json"))
            val expected = QqMusicSession("uid", "sid", "127.0.0.1", 123L)

            assertTrue(store.save(expected))
            assertEquals(expected, store.load())
            store.clear()
            assertEquals(null, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `qq fetch decrypts cloud qrc into word timed lyrics`() {
        val encryptedQrc =
            "0C8D67DD3E549974B64ED2680459F13881AA15D10DB4CC8324B86311D0D741BD" +
                "6AF5D8724F2B75716C3A763AFD2E129571AF815A2BE76F353DA7C356AA0D0CFF" +
                "FAAF93EBAAE303D09D2A9CEA52476FED47D80B815F418C788F03971C7781E5B7" +
                "A0EF552718E50B381387AC9C9C07CA788DC89BFE68DF67E37BF1C4B55C4F87D" +
                "6AB5BB8FA007C1EC7"
        val transport = FakeTransport(
            """{"req_0":{"code":0,"data":{"lyric":"$encryptedQrc","trans":"","roma":""}}}""",
        )
        val candidate = MetadataLyricsCandidate(
            source = MetadataLyricsSource.QQ_MUSIC,
            externalId = "42",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000L,
        )

        val document = QqMusicLyricsClient(transport).fetch(candidate)

        assertTrue(document != null)
        assertTrue(document!!.wordTimed)
        assertEquals("你好", document.lines.single().words.joinToString("") { it.text })
        assertEquals(listOf(0L, 500L), document.lines.single().words.map { it.startMs })
    }

    @Test
    fun `netease search bootstraps only an in-memory anonymous session`() {
        val transport = QueueTransport(
            listOf(
                "{\"code\":200,\"userId\":7}",
                """
                {"code":200,"data":{"resources":[{"baseInfo":{"simpleSongData":{
                  "id":99,"name":"Song","ar":[{"name":"Artist"}],
                  "al":{"name":"Album"},"dt":181000,"alia":["Live"]
                }}}]}}
                """.trimIndent(),
            ),
        )
        val client = NeteaseCloudLyricsClient(transport)

        val result = client.search(MetadataLyricsQuery("Song", "Artist", "Album"))

        assertEquals("99", result.single().externalId)
        assertEquals("Live", result.single().versionHint)
        assertEquals(2, transport.requests)
        assertTrue(transport.bodies.first().startsWith("params="))
    }

    @Test
    fun `netease client does not hold a monitor across concurrent network searches`() {
        val transport = ConcurrentNeteaseTransport()
        val client = NeteaseCloudLyricsClient(transport)
        assertEquals("99", client.search(MetadataLyricsQuery("Song", "Artist")).single().externalId)
        transport.blockSearch = true
        transport.searchCalls.set(0)

        val first = thread(start = true) {
            client.search(MetadataLyricsQuery("Song", "Artist"))
        }
        try {
            assertTrue(transport.firstSearchStarted.await(1L, TimeUnit.SECONDS))
            val second = thread(start = true) {
                client.search(MetadataLyricsQuery("Song", "Artist"))
            }
            try {
                assertTrue(
                    "second search was serialized behind the first network call",
                    transport.secondSearchStarted.await(500L, TimeUnit.MILLISECONDS),
                )
            } finally {
                transport.releaseSearches.countDown()
                second.join(1_000L)
            }
        } finally {
            transport.releaseSearches.countDown()
            first.join(1_000L)
        }

        assertTrue("first search did not finish", !first.isAlive)
    }

    private class FakeTransport(
        private val response: String,
    ) : LyricHttpTransport {
        var lastBody: String? = null

        override fun get(url: String): String? = null

        override fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            val request = body.toString(Charsets.UTF_8)
            lastBody = request
            val responseBody = if (request.contains("\"GetSession\"")) {
                """{"req_0":{"code":0,"data":{"uid":"100","sid":"session","userip":"127.0.0.1"}}}"""
            } else {
                response
            }
            return LyricHttpResponse(200, responseBody.toByteArray(Charsets.UTF_8))
        }
    }

    private class ConcurrentNeteaseTransport : LyricHttpTransport {
        val firstSearchStarted = CountDownLatch(1)
        val secondSearchStarted = CountDownLatch(1)
        val releaseSearches = CountDownLatch(1)
        val searchCalls = AtomicInteger()
        @Volatile var blockSearch = false

        override fun get(url: String): String? = null

        override fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            val response = when {
                url.contains("/eapi/register/anonimous") -> """{"code":200,"userId":7}"""
                url.contains("/eapi/search/song/list/page") -> {
                    if (blockSearch) {
                        when (searchCalls.incrementAndGet()) {
                            1 -> firstSearchStarted.countDown()
                            2 -> secondSearchStarted.countDown()
                        }
                        releaseSearches.await(1L, TimeUnit.SECONDS)
                    }
                    """
                    {"code":200,"data":{"resources":[{"baseInfo":{"simpleSongData":{
                      "id":99,"name":"Song","ar":[{"name":"Artist"}],
                      "al":{"name":"Album"},"dt":181000,"alia":[]
                    }}}]}}
                    """.trimIndent()
                }
                else -> "{}"
            }
            return LyricHttpResponse(200, response.toByteArray(Charsets.UTF_8))
        }
    }

    private class QueueTransport(
        private val responses: List<String>,
    ) : LyricHttpTransport {
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var requests: Int = 0

        override fun get(url: String): String? = null

        override fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            bodies += body.toString(Charsets.UTF_8)
            this.headers += headers
            return LyricHttpResponse(
                statusCode = 200,
                body = responses.getOrNull(requests++).orEmpty().toByteArray(Charsets.UTF_8),
            )
        }
    }

    private class SessionlessTransport(
        private val searchResponse: String,
    ) : LyricHttpTransport {
        val bodies = mutableListOf<String>()
        var requests: Int = 0

        override fun get(url: String): String? = null

        override fun post(
            url: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            val request = body.toString(Charsets.UTF_8)
            bodies += request
            requests += 1
            val response = if (request.contains("\"GetSession\"")) {
                """{"req_0":{"code":0,"data":{"uid":"","sid":"","userip":""}}}"""
            } else {
                searchResponse
            }
            return LyricHttpResponse(200, response.toByteArray(Charsets.UTF_8))
        }

        fun method(): String = org.json.JSONObject(bodies.single())
            .getJSONObject("req_0").getString("method")

        fun comm(): org.json.JSONObject = org.json.JSONObject(bodies.single()).getJSONObject("comm")
    }

    private fun automaticSource(
        source: MetadataLyricsSource,
        durationMs: Long,
        visited: MutableList<MetadataLyricsSource> = mutableListOf(),
        document: () -> MetadataLyricsDocument?,
    ) = AutomaticMetadataLyricsSource(
        source = source,
        search = {
            visited += source
            listOf(candidate(source, source.name, durationMs))
        },
        fetch = { document() },
    )

    private fun candidate(
        source: MetadataLyricsSource,
        id: String,
        durationMs: Long,
    ) = MetadataLyricsCandidate(
        source = source,
        externalId = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = durationMs,
    )

    private fun englishWordDocument(translated: Boolean) = MetadataLyricsDocument(
        lines = listOf(
            MetadataLyricsLine(
                0L,
                1_000L,
                listOf(
                    MetadataLyricsWord(0L, 500L, "hello"),
                    MetadataLyricsWord(500L, 1_000L, " world"),
                ),
            ),
        ),
        translations = if (translated) listOf("你好") else emptyList(),
        wordTimed = true,
    )
}
