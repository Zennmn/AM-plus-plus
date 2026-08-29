package dev.amenhancer.module.hook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpLyricTransportTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            handle(exchange)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/ok" -> respond(exchange, 200, "hello world")
                "/post" -> {
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    respond(exchange, 200, "${exchange.requestMethod}:$body:${exchange.requestHeaders.getFirst("X-Test")}")
                }
                "/hang" -> {
                    Thread.sleep(2_000L)
                    respond(exchange, 200, "too late")
                }
                "/missing" -> respond(exchange, 404, "not found")
                "/large" -> respond(exchange, 200, "x".repeat(200))
                "/etag" -> {
                    exchange.responseHeaders.add("ETag", "\"v1\"")
                    if (exchange.requestHeaders.getFirst("If-None-Match") == "\"v1\"") {
                        exchange.sendResponseHeaders(304, -1)
                    } else {
                        respond(exchange, 200, "manifest")
                    }
                }
                else -> respond(exchange, 500, "unexpected")
            }
        } finally {
            exchange.close()
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `get returns the body for a success response`() {
        val transport = HttpLyricTransport(maxResponseBytes = 64)
        assertEquals("hello world", transport.get("$baseUrl/ok"))
    }

    @Test
    fun `get returns null for a non success response`() {
        val transport = HttpLyricTransport(maxResponseBytes = 64)
        assertNull(transport.get("$baseUrl/missing"))
    }

    @Test
    fun `get returns null when the body exceeds the cap`() {
        val transport = HttpLyricTransport(maxResponseBytes = 64)
        assertNull(transport.get("$baseUrl/large"))
    }

    @Test
    fun `get response carries etag and honors conditional requests`() {
        val transport = HttpLyricTransport(maxResponseBytes = 64)

        val first = transport.getResponse("$baseUrl/etag")
        assertEquals(200, first?.statusCode)
        assertEquals("\"v1\"", first?.etag)
        assertEquals("manifest", first?.body?.toString(Charsets.UTF_8))

        val second = transport.getResponse("$baseUrl/etag", first?.etag)
        assertEquals(304, second?.statusCode)
        assertNull(second?.body)
    }

    @Test
    fun `post sends a bounded body and caller headers`() {
        val transport = HttpLyricTransport(maxResponseBytes = 64)
        val response = transport.post(
            "$baseUrl/post",
            "params=abc".toByteArray(Charsets.UTF_8),
            mapOf("X-Test" to "yes"),
        )

        assertEquals(200, response?.statusCode)
        assertEquals("POST:params=abc:yes", response?.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `post disconnects at the end to end request deadline`() {
        val transport = HttpLyricTransport(
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            requestDeadlineMs = 100,
            maxResponseBytes = 64,
        )

        val startedAt = System.nanoTime()
        val response = transport.post("$baseUrl/hang", "body".toByteArray())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertNull(response)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 750L)
    }

}
