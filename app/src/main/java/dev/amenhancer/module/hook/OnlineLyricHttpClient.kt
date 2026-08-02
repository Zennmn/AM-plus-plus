package dev.amenhancer.module.hook

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Network surface used by the lyric clients; faked in unit tests. */
internal interface LyricHttpTransport {
    fun get(url: String): String?
    fun postForm(
        url: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String?
}

/**
 * Minimal HTTP transport for lyric sources. Strict timeouts, a hard response
 * size cap and fail-open semantics: any network problem returns `null` and
 * the caller keeps the original lyrics. Runs on the background executor only,
 * never on the parser/I2 hook or the main thread.
 */
internal class HttpLyricTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : LyricHttpTransport {

    override fun get(url: String): String? = request(method = "GET", url = url, body = null)

    override fun postForm(
        url: String,
        body: String,
        extraHeaders: Map<String, String>,
    ): String? = request(
        method = "POST",
        url = url,
        body = body,
        extraHeaders = extraHeaders + FORM_HEADERS,
    )

    private fun request(
        method: String,
        url: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            readBounded(connection)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun readBounded(connection: HttpURLConnection): String? {
        val buffer = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val chunk = ByteArray(8192)
            while (buffer.size() < maxResponseBytes) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
        }
        if (buffer.size() >= maxResponseBytes) return null
        return buffer.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        private const val USER_AGENT = "AMPlusPlus/1.2.1"
        private val FORM_HEADERS = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        )
    }
}
