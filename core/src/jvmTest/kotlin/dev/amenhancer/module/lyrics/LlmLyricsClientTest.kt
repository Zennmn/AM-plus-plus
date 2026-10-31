package dev.amenhancer.module.lyrics

import com.sun.net.httpserver.HttpServer
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
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
    @Volatile private var content = """{"translated_lyrics":["你好"]}"""
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
            assertEquals(listOf("你好"), LlmLyricsClient().translate(config, listOf("Hello")))
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
            assertEquals(listOf("你好"), LlmLyricsClient().translate(config(effort = effort), listOf("Hello")))
            val request = requests.remove()
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertEquals(expected, request.getString("reasoning_effort"))
            assertFalse(request.has("reasoning"))
        }
    }

    @Test
    fun `connection checks and translation share the selected thinking parameters`() {
        val configs = (listOf(LlmLyricsThinkingEffort.DEFAULT) + explicitEfforts().map { it.first })
            .map { config(effort = it) } + config(thinking = false, effort = LlmLyricsThinkingEffort.MAX)
        for (config in configs) {
            content = """{"translated_lyrics":["你好"]}"""
            assertEquals(listOf("你好"), LlmLyricsClient().translate(config, listOf("Hello")))
            val translation = requests.remove()
            content = "OK"
            assertTrue(LlmLyricsClient().testConnection(config).isSuccess)
            val check = requests.remove()
            assertEquals(translation.getString("model"), check.getString("model"))
            assertEquals(translation.getBoolean("stream"), check.getBoolean("stream"))
            assertEquals(translation.getJSONObject("thinking").toString(), check.getJSONObject("thinking").toString())
            assertEquals(translation.has("reasoning_effort"), check.has("reasoning_effort"))
            assertEquals(translation.opt("reasoning_effort"), check.opt("reasoning_effort"))
            assertEquals("Reply with OK only.", check.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(check.toString().contains("Hello"))
        }
    }

    @Test
    fun `unsupported explicit effort fails without silently retrying another mode`() {
        status = 400
        val config = config(effort = LlmLyricsThinkingEffort.MAX)
        assertNull(LlmLyricsClient().translate(config, listOf("Hello")))
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
    fun `invalid JSON or a mismatched line count never produces a translation`() {
        for (invalid in listOf("not JSON", """{"translated_lyrics":["一","二"]}""", """{"translated_lyrics":[""]}""")) {
            content = invalid
            assertNull(LlmLyricsClient().translate(config(effort = LlmLyricsThinkingEffort.HIGH), listOf("Hello")))
        }
        assertEquals(3, requests.size)
    }

    @Test
    fun `a cancelled call never opens a request`() {
        val client = LlmLyricsClient()
        assertNull(client.translate(config(), listOf("Hello"), isCancelled = { true }))
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
            val result = worker.submit<List<String>?> {
                LlmLyricsClient(totalTimeoutMs = 10_000L).translate(
                    config(effort = LlmLyricsThinkingEffort.MAX), listOf("Hello"),
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
        assertNull(client.translate(config(effort = LlmLyricsThinkingEffort.MAX), listOf("Hello")))
        assertEquals("response_limit", client.testConnection(config()).failureReason)
    }

    private fun config(
        thinking: Boolean = true,
        effort: LlmLyricsThinkingEffort = LlmLyricsThinkingEffort.DEFAULT,
    ) = LlmLyricsConfig(baseUrl = baseUrl, model = " lyrics-model ", thinkingEnabled = thinking, thinkingEffort = effort)

    private fun explicitEfforts() = listOf(
        LlmLyricsThinkingEffort.LOW to "low",
        LlmLyricsThinkingEffort.HIGH to "high",
        LlmLyricsThinkingEffort.MAX to "max",
    )
}
