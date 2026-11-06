package dev.amenhancer.module.lyrics

import com.sun.net.httpserver.HttpServer
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import dev.amenhancer.module.model.LlmLyricsThinkingFormat
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmLyricsClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val requests = LinkedBlockingQueue<JSONObject>()
    private val paths = LinkedBlockingQueue<String>()
    @Volatile private var status = 200
    @Volatile private var content = translatedLines("你好")
    @Volatile private var reasoningContent = "Internal reasoning must not appear in the translation"
    @Volatile private var requestArrived: CountDownLatch? = null
    @Volatile private var responseGate: CountDownLatch? = null

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                requests.add(JSONObject(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }))
                paths.add("${exchange.requestMethod} ${exchange.requestURI.path}")
                requestArrived?.countDown()
                responseGate?.await(10, TimeUnit.SECONDS)
                val response = JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject()
                        .put("reasoning_content", reasoningContent)
                        .put("content", content)),
                )).toString().toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        responseGate?.countDown()
        server.stop(0)
    }

    @Test
    fun `default and disabled thinking omit effort from actual translation requests`() {
        val configs = LlmLyricsThinkingEffort.values().map { config(thinking = false, effort = it) } + config()
        for (config in configs) {
            assertEquals(lines("你好"), LlmLyricsClient().translate(config, lines("Hello")))
            val request = requests.remove()
            assertEquals("POST /v1/chat/completions", paths.remove())
            assertEquals("lyrics-model", request.getString("model"))
            assertFalse(request.getBoolean("stream"))
            assertEquals(if (config.thinkingEnabled) "enabled" else "disabled", request.getJSONObject("thinking").getString("type"))
            assertFalse("Unexpected effort for $config", request.has("reasoning_effort"))
        }
    }

    @Test
    fun `explicit levels reach the server while only final content becomes translation`() {
        for ((effort, expected) in explicitEfforts()) {
            assertEquals(lines("你好"), LlmLyricsClient().translate(config(effort = effort), lines("Hello")))
            val request = requests.remove()
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertEquals(expected, request.getString("reasoning_effort"))
            assertFalse(request.has("reasoning"))
        }
    }

    @Test
    fun `connection checks and translation send only the selected thinking fields`() {
        val configs = listOf(
            Triple(config(), "enabled", null),
            Triple(config(effort = LlmLyricsThinkingEffort.MAX), "enabled", "max"),
            Triple(config(thinking = false, effort = LlmLyricsThinkingEffort.MAX), "disabled", null),
            Triple(config(effort = LlmLyricsThinkingEffort.HIGH, format = LlmLyricsThinkingFormat.NONE), null, null),
            Triple(config(format = LlmLyricsThinkingFormat.REASONING_EFFORT), null, null),
            Triple(
                config(thinking = false, effort = LlmLyricsThinkingEffort.LOW, format = LlmLyricsThinkingFormat.REASONING_EFFORT),
                null, "low",
            ),
            Triple(
                config(effort = LlmLyricsThinkingEffort.MAX, format = LlmLyricsThinkingFormat.REASONING_EFFORT),
                null, null,
            ),
        )
        for ((config, type, effort) in configs) {
            content = translatedLines("你好")
            assertEquals(lines("你好"), LlmLyricsClient().translate(config, lines("Hello")))
            val translation = requests.remove()
            content = "OK"
            assertTrue(LlmLyricsClient().testConnection(config).isSuccess)
            val check = requests.remove()
            assertEquals(translation.getString("model"), check.getString("model"))
            assertEquals(translation.getBoolean("stream"), check.getBoolean("stream"))
            for (request in listOf(translation, check)) {
                assertEquals(type != null, request.has("thinking"))
                assertEquals(type, request.optJSONObject("thinking")?.getString("type"))
                assertEquals(effort != null, request.has("reasoning_effort"))
                assertEquals(effort, request.opt("reasoning_effort"))
            }
            assertEquals("Reply with OK only.", check.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(check.toString().contains("Hello"))
        }
    }

    @Test
    fun `shuffled vocal IDs write to their original paragraph and order`() {
        val original = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
              <body><div>
                <p itunes:key="verse" begin="00:00:01.000">Stay<span ttm:role="x-bg">Don't go</span><span ttm:role="x-bg">Come back</span></p>
                <p itunes:key="chorus" begin="00:00:02.000" xml:lang="zh">等我<span ttm:role="x-bg" xml:lang="en">Wait</span></p>
              </div></body>
            </tt>
        """.trimIndent()
        content = """{"lines":[
            {"id":"L2","translation":null,"background":[{"id":"L2.bg1","translation":"等等"}]},
            {"id":"L1","translation":"留下","background":[
                {"id":"L1.bg2","translation":"回来"},
                {"id":"L1.bg1","translation":"（(别走)）"}
            ]}
        ]}"""
        val document = checkNotNull(LlmLyricsDocument.prepare(original))
        val translations = checkNotNull(LlmLyricsClient().translate(config(), document.translationLines))
        val ttml = checkNotNull(document.withTranslations(translations))
        val subtitle = ttml.substringAfter("<translation type=\"subtitle\" xml:lang=\"zh-Hans\">")
            .substringBefore("</translation>")
            .replace(" xmlns=\"http://www.w3.org/ns/ttml\"", "")
            .replace(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"", "")
        val verse = """<text for="verse">留下 <span ttm:role="x-bg">(别走)</span> <span ttm:role="x-bg">(回来)</span></text>"""
        val chorus = """<text for="chorus">等我 <span ttm:role="x-bg">(等等)</span></text>"""
        assertEquals(verse + chorus, subtitle)
        assertEquals(original.substringAfter("<body>"), ttml.substringAfter("<body>"))
    }

    @Test
    fun `unsupported explicit effort fails without silently retrying another mode`() {
        status = 400
        val config = config(effort = LlmLyricsThinkingEffort.MAX)
        assertNull(LlmLyricsClient().translate(config, lines("Hello")))
        assertEquals(1, requests.size)
        assertEquals("max", requests.remove().getString("reasoning_effort"))

        val result = LlmLyricsClient().testConnection(config)
        assertFalse(result.isSuccess)
        assertEquals("http_status", result.failureReason)
        assertEquals(400, result.httpStatus)
        assertEquals(1, requests.size)
        assertEquals("max", requests.remove().getString("reasoning_effort"))
    }

    @Test
    fun `invalid JSON or invalid line IDs never produce a translation`() {
        val input = mapOf(
            "L1" to LlmLyricsLine("Hello", mapOf("L1.bg1" to "oh")),
            "L2" to LlmLyricsLine("Goodbye", mapOf("L2.bg1" to "ah")),
        )
        val first = """{"id":"L1","translation":"你好","background":[{"id":"L1.bg1","translation":"哦"}]}"""
        val second = """{"id":"L2","translation":"再见","background":[{"id":"L2.bg1","translation":"啊"}]}"""
        val invalidAnswers = listOf(
            "not JSON",
            """{"lines":[$first]}""",
            """{"lines":[$first,$first]}""",
            """{"lines":[$first,$second,$second]}""",
            """{"lines":[$first,${second.replace("L2.bg1", "L1.bg1")}]}""",
            """{"lines":[$first,${second.replace("再见", "")}]}""",
        )
        for (invalid in invalidAnswers) {
            content = invalid
            assertNull(LlmLyricsClient().translate(config(effort = LlmLyricsThinkingEffort.HIGH), input))
        }
        assertEquals(invalidAnswers.size, requests.size)
    }

    @Test
    fun `a cancelled call never opens a request`() {
        val client = LlmLyricsClient()
        assertNull(client.translate(config(), lines("Hello"), isCancelled = { true }))
        assertEquals("cancelled", client.testConnection(config(), isCancelled = { true }).failureReason)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cancellation disconnects a pending effort request without accepting a late reply`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        requestArrived = arrived
        responseGate = release
        val cancelled = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Map<String, LlmLyricsLine>?> {
                LlmLyricsClient(totalTimeoutMs = 10_000L).translate(
                    config(effort = LlmLyricsThinkingEffort.MAX), lines("Hello"),
                    isCancelled = cancelled::get,
                )
            }
            assertTrue("The request never reached the local server", arrived.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals("max", requests.remove().getString("reasoning_effort"))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `reasoning content counts toward the existing response limit`() {
        reasoningContent = "x".repeat(1024)
        val client = LlmLyricsClient(maxResponseBytes = 256)
        assertNull(client.translate(config(effort = LlmLyricsThinkingEffort.MAX), lines("Hello")))
        assertEquals("response_limit", client.testConnection(config()).failureReason)
    }

    private fun config(
        thinking: Boolean = true,
        effort: LlmLyricsThinkingEffort = LlmLyricsThinkingEffort.DEFAULT,
        format: LlmLyricsThinkingFormat = LlmLyricsThinkingFormat.THINKING_TYPE,
    ) = LlmLyricsConfig(
        baseUrl = baseUrl,
        model = " lyrics-model ",
        thinkingEnabled = thinking,
        thinkingEffort = effort,
        thinkingFormat = format,
    )

    private fun explicitEfforts() = listOf(
        LlmLyricsThinkingEffort.LOW to "low",
        LlmLyricsThinkingEffort.HIGH to "high",
        LlmLyricsThinkingEffort.MAX to "max",
    )

    private fun lines(main: String) = mapOf("L1" to LlmLyricsLine(main))

    private fun translatedLines(translation: String) =
        """{"lines":[{"id":"L1","translation":${JSONObject.quote(translation)},"background":[]}]}"""
}
