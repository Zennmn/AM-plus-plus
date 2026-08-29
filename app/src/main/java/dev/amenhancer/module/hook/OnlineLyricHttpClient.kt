package dev.amenhancer.module.hook

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Network surface used by the lyric clients; faked in unit tests. */
internal data class LyricHttpResponse(
    val statusCode: Int,
    val body: ByteArray?,
    val etag: String? = null,
    /** Response headers are needed by the short-lived NetEase anonymous session. */
    val headers: Map<String, List<String>> = emptyMap(),
)

internal interface LyricHttpTransport {
    fun get(url: String): String?

    /** Raw response bytes for callers that must verify remote size and hash. */
    fun getBytes(url: String): ByteArray? = get(url)?.toByteArray(Charsets.UTF_8)

    /** Optional response metadata used by catalog clients for conditional GET. */
    fun getResponse(url: String, ifNoneMatch: String? = null): LyricHttpResponse? =
        getBytes(url)?.let { bytes -> LyricHttpResponse(HttpURLConnection.HTTP_OK, bytes) }

    /** Optional POST seam used by the explicit QQ/NetEase settings import. */
    fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): LyricHttpResponse? = null

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): LyricHttpResponse? = post(url, body.toByteArray(Charsets.UTF_8), headers)
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
    private val requestDeadlineMs: Int = DEFAULT_REQUEST_DEADLINE_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : LyricHttpTransport {

    override fun get(url: String): String? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }
            ?.body?.toString(Charsets.UTF_8)

    override fun getBytes(url: String): ByteArray? =
        getResponse(url)?.takeIf { it.statusCode == HttpURLConnection.HTTP_OK }?.body

    override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? =
        requestResponse(url, ifNoneMatch)

    override fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): LyricHttpResponse? = requestResponse(
        url = url,
        ifNoneMatch = null,
        method = "POST",
        requestBody = body,
        requestHeaders = headers,
    )

    private fun requestResponse(
        url: String,
        ifNoneMatch: String?,
        method: String = "GET",
        requestBody: ByteArray? = null,
        requestHeaders: Map<String, String> = emptyMap(),
    ): LyricHttpResponse? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        val deadline = requestWatchdog.schedule(
            { connection.disconnect() },
            requestDeadlineMs.coerceAtLeast(1).toLong(),
            TimeUnit.MILLISECONDS,
        )
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
            // The small JSON/QRC clients do not depend on an inflater for an
            // HTTP content-encoding; request identity so Android's
            // HttpURLConnection never hands them a gzip stream.
            connection.setRequestProperty("Accept-Encoding", "identity")
            requestHeaders.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
            }
            if (!ifNoneMatch.isNullOrBlank()) {
                connection.setRequestProperty("If-None-Match", ifNoneMatch)
            }
            if (requestBody != null) {
                connection.doOutput = true
                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                }
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { output -> output.write(requestBody) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key.orEmpty() }
                .mapValues { (_, values) -> values.filterNotNull() }
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@runCatching LyricHttpResponse(
                    status,
                    body = null,
                    etag = connection.etag(),
                    headers = headers,
                )
            }
            if (status !in 200..299) {
                // Preserve non-2xx status for callers that need to distinguish
                // a transient session response, but do not read untrusted
                // error pages beyond the same hard cap.
                return@runCatching LyricHttpResponse(
                    status,
                    body = readBounded(connection, errorStream = true),
                    etag = connection.etag(),
                    headers = headers,
                )
            }
            val bytes = readBounded(connection) ?: return@runCatching null
            LyricHttpResponse(status, bytes, connection.etag(), headers)
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }.getOrNull()

    private fun HttpURLConnection.etag(): String? = getHeaderField("ETag")?.trim()

    private fun readBounded(
        connection: HttpURLConnection,
        errorStream: Boolean = false,
    ): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val stream = if (errorStream) connection.errorStream else connection.inputStream
        stream?.use { input ->
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
        const val DEFAULT_REQUEST_DEADLINE_MS = 25_000
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        private const val USER_AGENT = "AMPlusPlus/1.2.1"
        private val requestWatchdog = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "ampp-lyric-http-watchdog").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }
}
