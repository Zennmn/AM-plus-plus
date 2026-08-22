package dev.amenhancer.module.hook

import java.net.HttpURLConnection
import java.net.URL

/** Network surface used by the lyric clients; faked in unit tests. */
internal data class LyricHttpResponse(
    val statusCode: Int,
    val body: ByteArray?,
    val etag: String? = null,
)

internal interface LyricHttpTransport {
    fun get(url: String): String?

    /** Raw response bytes for callers that must verify remote size and hash. */
    fun getBytes(url: String): ByteArray? = get(url)?.toByteArray(Charsets.UTF_8)

    /** Optional response metadata used by catalog clients for conditional GET. */
    fun getResponse(url: String, ifNoneMatch: String? = null): LyricHttpResponse? =
        getBytes(url)?.let { bytes -> LyricHttpResponse(HttpURLConnection.HTTP_OK, bytes) }
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

    override fun get(url: String): String? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }
            ?.body?.toString(Charsets.UTF_8)

    override fun getBytes(url: String): ByteArray? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body

    override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? =
        requestResponse(url, ifNoneMatch)

    private fun requestResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
            if (!ifNoneMatch.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@runCatching LyricHttpResponse(status, body = null, etag = connection.etag())
            }
            if (status != HttpURLConnection.HTTP_OK) return@runCatching null
            val bytes = readBounded(connection) ?: return@runCatching null
            LyricHttpResponse(status, bytes, connection.etag())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun HttpURLConnection.etag(): String? = getHeaderField("ETag")?.trim()

    private fun readBounded(connection: HttpURLConnection): ByteArray? {
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
        return buffer.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        private const val USER_AGENT = "AMPlusPlus/1.2.1"
    }
}
