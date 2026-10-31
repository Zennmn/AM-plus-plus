package dev.amenhancer.module.lyrics

// Adapted from YouLyPlus src/background/services/translation/providers/CustomLLMProvider.js
// and src/background/services/translation/prompts.js (createTranslationPrompt).
// Upstream declares AGPL-3.0-only; the ported prompt text and request/validation
// behaviour keep that provenance for THIRD_PARTY_NOTICES purposes.

import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-provided OpenAI-compatible endpoint. The key is optional because local
 * gateways usually run without one, and it is never handed to the cache.
 */
data class LlmLyricsConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val thinkingEnabled: Boolean = false,
    val thinkingEffort: LlmLyricsThinkingEffort = LlmLyricsThinkingEffort.DEFAULT,
) {
    /** Normalized `.../chat/completions` target, or null when the address is unusable. */
    val endpoint: String? get() = LlmLyricsEndpoint.normalize(baseUrl)

    val normalizedModel: String get() = model.trim()

    /** A saved effort has no request or cache effect while thinking is disabled. */
    val effectiveThinkingEffort: LlmLyricsThinkingEffort
        get() = if (thinkingEnabled) thinkingEffort else LlmLyricsThinkingEffort.DEFAULT

    val isConfigured: Boolean get() = endpoint != null && normalizedModel.isNotEmpty()
}

/** Address validation mirroring the upstream customLLMEndpoint helper. */
object LlmLyricsEndpoint {
    private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    private const val DEFAULT_API_PATH = "/v1"

    /**
     * Accepts a bare host, a `/v1` base or a full `/chat/completions` URL and
     * returns the canonical POST target. Embedded credentials, query strings and
     * fragments are rejected. The input is parsed as a URI instead of relying on
     * lenient URL parsing, so the returned string is always the request target.
     */
    fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return@runCatching null
            if (!uri.rawUserInfo.isNullOrEmpty()) return@runCatching null
            if (!uri.rawQuery.isNullOrEmpty()) return@runCatching null
            if (!uri.rawFragment.isNullOrEmpty()) return@runCatching null
            val host = uri.host
            if (host.isNullOrBlank()) return@runCatching null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val endpointPath = if (path.endsWith(CHAT_COMPLETIONS_PATH)) {
                path
            } else {
                path.ifEmpty { DEFAULT_API_PATH } + CHAT_COMPLETIONS_PATH
            }
            "$scheme://$host$port$endpointPath"
        }.getOrNull()
    }
}

/**
 * Minimal OpenAI-compatible chat-completions client for lyric translation.
 *
 * The port keeps the upstream request shape: one non-streaming POST with a
 * system instruction plus a single user prompt, an optional `Bearer` key, and
 * a JSON-only answer whose line count must match the input. What the hook
 * context adds is a hard total timeout, cooperative cancellation that actually
 * drops the in-flight connection, bounded request and response bodies, and
 * fail-open semantics: any problem returns null and the caller keeps the
 * original lyrics. There is no retry, no alternate provider and no logging, so
 * the API key can never reach a cache entry or an error message.
 *
 * No cookie header is added by this client and redirects are never followed.
 * Instances are stateless and safe to share across threads.
 */
class LlmLyricsClient(
    private val userAgent: String = USER_AGENT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {

    /**
     * Translates [texts] line by line into [TARGET_LANGUAGE]. Returns one
     * translated string per input line, or null when the configuration is
     * unusable, the call failed, ran out of time, was cancelled, or the answer
     * did not match the input line count.
     */
    fun translate(
        config: LlmLyricsConfig,
        texts: List<String>,
        title: String? = null,
        artist: String? = null,
        sourceLanguage: String? = null,
        backgroundIndices: Set<Int> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): List<String>? {
        if (texts.isEmpty()) return emptyList()
        val endpoint = config.endpoint ?: return null
        val model = config.normalizedModel
        if (model.isEmpty()) return null
        if (isCancelled()) return null
        if (!isWithinInputBounds(texts)) return null
        if (backgroundIndices.any { it !in texts.indices }) return null
        val body = requestBody(config, texts, title, artist, sourceLanguage, backgroundIndices) ?: return null
        val response = post(endpoint, config.apiKey, body, isCancelled) ?: return null
        if (isCancelled()) return null
        val content = messageContent(response) ?: return null
        return parseTranslation(content, texts)
    }

    /** Only fixed reason codes/statuses may cross into the settings UI. */
    data class ConnectionResult(val failureReason: String? = null, val httpStatus: Int? = null) {
        val isSuccess: Boolean get() = failureReason == null
    }

    /**
     * Explicit settings action: exercises the configured model and Bearer auth
     * through the same transport as translation, without lyrics or cache access.
     * The caller owns the background thread and page-lifecycle cancellation.
     */
    fun testConnection(
        config: LlmLyricsConfig,
        isCancelled: () -> Boolean = { false },
    ): ConnectionResult {
        val endpoint = config.endpoint ?: return ConnectionResult("invalid_endpoint")
        val model = config.normalizedModel
        if (model.isEmpty()) return ConnectionResult("missing_model")
        if (isCancelled()) return ConnectionResult("cancelled")
        val body = runCatching {
            encodeRequest(
                config,
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Reply briefly."))
                    .put(JSONObject().put("role", "user").put("content", "Reply with OK only.")),
            )
        }.getOrNull() ?: return ConnectionResult("request_encoding_or_limit")
        var failure = ConnectionResult("transport")
        val response = post(endpoint, config.apiKey, body, isCancelled) { reason, status ->
            failure = ConnectionResult(reason, status)
        } ?: return failure
        if (isCancelled()) return ConnectionResult("cancelled")
        if (messageContent(response).isNullOrBlank()) return ConnectionResult("invalid_response")
        return ConnectionResult()
    }

    private fun messageContent(response: String): String? = runCatching {
        JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.opt("content") as? String
    }.getOrNull()

    /**
     * Runs one request with a watchdog that enforces the total deadline and
     * cancellation even while the socket is blocked: it disconnects the live
     * connection, which fails the blocked read instead of leaking it until the
     * platform timeout expires.
     */
    private fun post(
        endpoint: String,
        apiKey: String,
        body: ByteArray,
        isCancelled: () -> Boolean,
        onFailure: (String, Int?) -> Unit = { _, _ -> },
    ): String? {
        val connection = runCatching {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: run {
            onFailure("connection_unavailable", null)
            return null
        }
        val deadlineNanos = System.nanoTime() + totalTimeoutMs * NANOS_PER_MILLI
        val finished = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val watchdog = Thread(
            Runnable {
                try {
                    var watching = true
                    while (watching && !finished.await(WATCHDOG_POLL_MS, TimeUnit.MILLISECONDS)) {
                        val cancelled = runCatching(isCancelled).getOrDefault(false)
                        if (cancelled || System.nanoTime() >= deadlineNanos) {
                            watching = false
                            aborted.set(true)
                            runCatching { connection.disconnect() }
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            WATCHDOG_THREAD_NAME,
        ).apply { isDaemon = true }
        watchdog.start()
        try {
            val result = runCatching { exchange(connection, apiKey, body, onFailure) }
            val cancelled = runCatching(isCancelled).getOrDefault(false)
            if (cancelled || aborted.get() || System.nanoTime() >= deadlineNanos) {
                onFailure(if (cancelled) "cancelled" else "timeout", null)
                return null
            }
            result.exceptionOrNull()?.let { error ->
                val reason = when (error) {
                    is java.net.SocketTimeoutException -> "timeout"
                    is java.net.UnknownHostException -> "dns"
                    is javax.net.ssl.SSLException -> "tls"
                    is java.net.ConnectException -> "connect"
                    is java.io.IOException -> "network"
                    else -> "transport"
                }
                onFailure(reason, null)
            }
            return result.getOrNull()
        } finally {
            finished.countDown()
            runCatching { connection.disconnect() }
            runCatching { watchdog.join(WATCHDOG_JOIN_MS) }
        }
    }

    private fun exchange(
        connection: HttpURLConnection,
        apiKey: String,
        body: ByteArray,
        onFailure: (String, Int?) -> Unit,
    ): String? {
        val key = apiKey.trim()
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { output -> output.write(body) }
        val status = connection.responseCode
        if (status !in 200..299) {
            onFailure("http_status", status)
            return null
        }
        val bytes = readBounded(connection) ?: run {
            onFailure("response_limit", null)
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Reads at most [maxResponseBytes]; anything larger fails the request. */
    private fun readBounded(connection: HttpURLConnection): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxResponseBytes) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun requestBody(
        config: LlmLyricsConfig,
        texts: List<String>,
        title: String?,
        artist: String?,
        sourceLanguage: String?,
        backgroundIndices: Set<Int>,
    ): ByteArray? = runCatching {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", translationPrompt(texts, title, artist, sourceLanguage, backgroundIndices)),
            )
        encodeRequest(config, messages)
    }.getOrNull()

    /** Translation and the manual check share the selected thinking extension. */
    private fun encodeRequest(config: LlmLyricsConfig, messages: JSONArray): ByteArray? = runCatching {
        val bytes = JSONObject()
            .put("model", config.normalizedModel)
            .put("stream", false)
            .put("thinking", JSONObject().put("type", if (config.thinkingEnabled) "enabled" else "disabled"))
            .put("messages", messages)
            .apply {
                val effort = config.effectiveThinkingEffort
                if (effort != LlmLyricsThinkingEffort.DEFAULT) {
                    put("reasoning_effort", effort.storageValue)
                }
            }
            .toString()
            .toByteArray(Charsets.UTF_8)
        bytes.takeIf { it.size <= MAX_REQUEST_BYTES }
    }.getOrNull()

    /**
     * Fixed Simplified Chinese translation style with the existing custom-LLM
     * input/output contract. This port exposes no prompt setting.
     */
    private fun translationPrompt(
        texts: List<String>,
        title: String?,
        artist: String?,
        sourceLanguage: String?,
        backgroundIndices: Set<Int>,
    ): String {
        val targetLang = TARGET_LANGUAGE
        val songContext = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            "Song Metadata: Title=\"$title\", Artist=\"$artist\""
        } else {
            "Song Metadata: None"
        }
        val sourceLangHint = sourceLanguage?.takeIf(String::isNotBlank)
            ?.let { "Source Languages Present: $it" }
            ?: "Source Languages: Mixed/Unknown"
        val linesJson = JSONArray(texts).toString(2)
        val base = """### ROLE
你是一位专业的歌词译者，请将提供的歌词翻译成简体中文。

### CONTEXT
${songContext}
${sourceLangHint}

### 翻译要求
- 翻成简体中文，外语、方言、俚语都要处理，不能因为用了拉丁字母就原样保留。
- 已经是目标语言的句子保持原样。
- 保留重复表达，比如“ma-ma”“la-la-la”。
- 要联系歌词上下文、语气和意象理解原文，再翻译成中文，但简单句不要复杂化。
- 逐句检查并润色全篇，译文要信达雅，尤其注重“雅”，避免生硬直译。
- 同一句歌词在不同语境下可能要用不同译法，不要固定套用同一句。
- 重复歌词要按中文语感处理，不要机械重复译文
- 和声也要翻译完整，不能遗漏；不要直接重复主唱译文充数。
- 中文译文不要使用逗号和句号。

### TASK
Input Lyrics:
${linesJson}

IMPORTANT OUTPUT FORMAT:
Respond with ONLY a valid JSON object matching the exact structure below. Do not wrap it in Markdown code blocks (```json). Just return the raw JSON string.
{
  "translated_lyrics": [
    "translated line 1",
    "translated line 2"
  ],
  "target_language": "${targetLang}",
  "source_language": ["detected source language(s)"]
}"""
        return base +
            "\n\nINPUT LINES:\n${JSONArray(texts).toString()}\n" +
            "Background vocal positions (zero-based): ${JSONArray(backgroundIndices.sorted())}. " +
            "Each array entry is an independent main or background vocal. " +
            "Translate every entry separately; never merge a background with its main vocal. " +
            "Return only JSON: {\"translated_lyrics\": [one translated string for each input line]}. " +
            "Keep exactly ${texts.size} lines in order."
    }

    /**
     * Strictly accepts a bare JSON array or "translated_lyrics", requires one
     * string per input line, and rejects an empty answer for a source line that
     * carries text. Whitespace-only source lines may stay blank.
     */
    private fun parseTranslation(content: String, texts: List<String>): List<String>? {
        val cleaned = TRAILING_JSON_FENCE
            .replace(LEADING_JSON_FENCE.replace(content.trim(), ""), "")
            .trim()
        if (cleaned.isEmpty()) return null
        val parsed = runCatching {
            if (cleaned.startsWith("[")) JSONArray(cleaned) else JSONObject(cleaned)
        }.getOrNull() ?: return null
        val lines = when (parsed) {
            is JSONArray -> parsed
            is JSONObject -> parsed.opt("translated_lyrics") as? JSONArray
            else -> null
        } ?: return null
        if (lines.length() != texts.size) return null
        return buildList(lines.length()) {
            for (index in 0 until lines.length()) {
                val value = lines.opt(index)
                if (value !is String) return null
                if (texts[index].isNotBlank() && value.isBlank()) return null
                add(value)
            }
        }
    }

    private fun isWithinInputBounds(texts: List<String>): Boolean {
        if (texts.size > MAX_LINES) return false
        var totalChars = 0
        texts.forEach { line ->
            if (line.length > MAX_LINE_CHARS) return false
            totalChars += line.length
            if (totalChars > MAX_TOTAL_CHARS) return false
        }
        return true
    }

    companion object {
        /** Fixed target for the ported prompt and the translation cache. */
        const val TARGET_LANGUAGE = "zh-Hans"

        /** Bump when prompt or answer changes require invalidating cached translations. */
        const val PROMPT_VERSION = 2

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        const val DEFAULT_TOTAL_TIMEOUT_MS = 60_000L
        const val DEFAULT_MAX_RESPONSE_BYTES = 1 shl 20
        const val MAX_LINES = 4096
        const val MAX_LINE_CHARS = 4096
        const val MAX_TOTAL_CHARS = 256 * 1024
        const val MAX_REQUEST_BYTES = 512 * 1024

        /** Mirrors the user agent used by the existing lyric transport. */
        private const val USER_AGENT = "AMPlusPlus/1.2.1"
        private const val WATCHDOG_THREAD_NAME = "llmLyricsWatchdog"
        private const val WATCHDOG_POLL_MS = 250L
        private const val WATCHDOG_JOIN_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val SYSTEM_PROMPT =
            "Translate song lyrics. Return only the requested JSON, with one string per input line."
        private val LEADING_JSON_FENCE = Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE)
        private val TRAILING_JSON_FENCE = Regex("\\s*```$", RegexOption.IGNORE_CASE)
    }
}
