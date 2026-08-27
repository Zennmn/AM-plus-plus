package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.TtmlInputPolicy
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Cached raw Lunabeat catalog payloads. */
internal data class LunabeatCatalogCacheSnapshot(
    val manifestJson: String,
    val indexJson: String,
    val etag: String? = null,
)

/** Storage seam keeps catalog caching independent from Android UI/process code. */
internal fun interface LunabeatCatalogCache {
    fun read(): LunabeatCatalogCacheSnapshot?

    fun write(manifestJson: String, indexJson: String, etag: String? = null): Boolean = false
}

/** File-backed cache used by both the standalone and embedded settings hosts. */
internal class FileLunabeatCatalogCache(
    private val directory: File,
) : LunabeatCatalogCache {
    private val cacheFile: File = File(directory, CACHE_FILE_NAME)

    override fun read(): LunabeatCatalogCacheSnapshot? = runCatching {
        if (!cacheFile.isFile || cacheFile.length() !in 1L..MAX_CACHE_BYTES) return@runCatching null
        val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
        val manifest = root.optString(KEY_MANIFEST).takeIf(String::isNotBlank) ?: return@runCatching null
        val index = root.optString(KEY_INDEX).takeIf(String::isNotBlank) ?: return@runCatching null
        LunabeatCatalogCacheSnapshot(
            manifestJson = manifest,
            indexJson = index,
            etag = root.optString(KEY_ETAG).takeIf(String::isNotBlank),
        )
    }.getOrNull()

    override fun write(manifestJson: String, indexJson: String, etag: String?): Boolean = runCatching {
        if (manifestJson.isBlank() || indexJson.isBlank()) return@runCatching false
        val encoded = JSONObject()
            .put(KEY_MANIFEST, manifestJson)
            .put(KEY_INDEX, indexJson)
            .put(KEY_ETAG, etag.orEmpty())
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_CACHE_BYTES) return@runCatching false
        if (!directory.exists() && !directory.mkdirs()) return@runCatching false
        val pending = File.createTempFile("lunabeat_pending_", ".tmp", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } finally {
            pending.delete()
        }
    }.getOrDefault(false)

    private companion object {
        const val CACHE_FILE_NAME = "catalog.json"
        const val MAX_CACHE_BYTES = 4L * 1024L * 1024L
        const val KEY_MANIFEST = "manifest"
        const val KEY_INDEX = "index"
        const val KEY_ETAG = "etag"
    }
}

internal data class LunabeatManifest(
    val schemaVersion: Int,
    val revision: String,
    val indexPath: String,
)

internal data class LunabeatSong(
    val title: String,
    val artists: List<String>,
    val album: String,
    val appleMusicIds: List<Long>,
    val path: String,
    val sha256: String,
) {
    val displayName: String
        get() = listOfNotNull(
            title.takeIf(String::isNotBlank),
            artists.joinToString(", ").takeIf(String::isNotBlank),
        ).joinToString(" - ").ifBlank { "Lunabeat 自定义歌词" }
}

internal data class LunabeatCatalog(
    val manifest: LunabeatManifest,
    val songs: List<LunabeatSong>,
) {
    fun entryFor(appleMusicId: Long): LunabeatSong? = songs.firstOrNull { song ->
        appleMusicId in song.appleMusicIds
    }
}

/**
 * Lunabeat/TTML Hub client. The catalog is refreshed from the manifest first;
 * the larger songs.json is downloaded only when its revision changes. The
 * manifest's optional indexSha256 is intentionally not enforced because the
 * current published value does not match the served index bytes.
 */
internal class LunabeatClient(
    private val indexTransport: LyricHttpTransport,
    private val lyricsTransport: LyricHttpTransport = indexTransport,
    private val cache: LunabeatCatalogCache,
) {
    fun fetch(appleMusicId: Long): String? {
        if (appleMusicId <= 0L) return null
        val catalog = loadCatalog() ?: return null
        val song = catalog.entryFor(appleMusicId) ?: return null
        return fetch(song)
    }

    /** Fetches one already-resolved catalog song without reloading the catalog. */
    fun fetch(song: LunabeatSong): String? {
        val encodedPath = encodePath(song.path) ?: return null
        val bytes = lyricsTransport.getBytes("$LYRICS_BASE/$encodedPath") ?: return null
        if (bytes.size > TtmlInputPolicy.MAX_TTML_BYTES) return null
        if (!sha256(bytes).equals(song.sha256, ignoreCase = true)) return null
        return bytes.toString(Charsets.UTF_8)
            .takeIf(TtmlInputPolicy::isAcceptable)
    }

    internal fun loadCatalog(): LunabeatCatalog? {
        val cachedSnapshot = cache.read()
        val cached = cachedSnapshot?.let { snapshot ->
            parseCatalog(snapshot.manifestJson, snapshot.indexJson)
        }
        var response = indexTransport.getResponse(MANIFEST_URL, cachedSnapshot?.etag)
            ?: return cached
        if (response.statusCode == 304) {
            if (cached != null) return cached
            response = indexTransport.getResponse(MANIFEST_URL) ?: return null
            if (response.statusCode == 304) return null
        }
        if (response.statusCode != 200 || response.body == null) return cached
        val remoteManifestJson = response.body.toString(Charsets.UTF_8)
        val remoteManifest = parseManifest(remoteManifestJson)
            ?: return cached
        if (cached != null &&
            cached.manifest.revision == remoteManifest.revision &&
            cached.manifest.indexPath == remoteManifest.indexPath
        ) {
            val snapshot = cachedSnapshot ?: return cached
            if (remoteManifestJson != snapshot.manifestJson ||
                response.etag != snapshot.etag
            ) {
                cache.write(remoteManifestJson, snapshot.indexJson, response.etag)
            }
            return cached
        }
        val indexPath = encodePath(remoteManifest.indexPath) ?: return cached
        val indexResponse = indexTransport.getResponse("$API_BASE/$indexPath") ?: return cached
        if (indexResponse.statusCode != 200 || indexResponse.body == null) return cached
        val indexBytes = indexResponse.body
        if (indexBytes.size > INDEX_MAX_BYTES) return cached
        val indexJson = indexBytes.toString(Charsets.UTF_8)
        val updated = parseCatalog(remoteManifestJson, indexJson) ?: return cached
        cache.write(remoteManifestJson, indexJson, response.etag)
        return updated
    }

    internal companion object {
        const val API_BASE = "https://2755337087.github.io/ttml-hub/api/v1"
        const val MANIFEST_URL = "$API_BASE/manifest.json"
        const val LYRICS_BASE = "https://2755337087.github.io/ttml-hub"
        const val INDEX_MAX_BYTES = 2 * 1024 * 1024
        private const val SCHEMA_VERSION = 2
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        fun parseManifest(raw: String): LunabeatManifest? = runCatching {
            val root = JSONObject(raw)
            val schemaVersion = root.optInt("schemaVersion", 0)
            val revision = root.optString("revision").trim()
            val indexPath = root.optString("index").trim()
            if (schemaVersion != SCHEMA_VERSION || revision.isBlank() || !isSafePath(indexPath)) {
                return@runCatching null
            }
            LunabeatManifest(schemaVersion, revision, indexPath)
        }.getOrNull()

        fun parseCatalog(manifestJson: String, indexJson: String): LunabeatCatalog? = runCatching {
            val manifest = parseManifest(manifestJson) ?: return@runCatching null
            val root = JSONObject(indexJson)
            if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION ||
                root.optString("revision").trim() != manifest.revision
            ) {
                return@runCatching null
            }
            val songs = root.optJSONArray("songs") ?: return@runCatching null
            val parsed = songs.parseSongs() ?: return@runCatching null
            val ids = mutableSetOf<Long>()
            parsed.forEach { song ->
                song.appleMusicIds.forEach { id ->
                    if (!ids.add(id)) return@runCatching null
                }
            }
            LunabeatCatalog(manifest, parsed)
        }.getOrNull()

        private fun JSONArray.parseSongs(): List<LunabeatSong>? = buildList {
            for (index in 0 until length()) {
                val raw = optJSONObject(index) ?: return null
                val title = raw.optString("title").trim()
                val artists = parseStrings(raw.optJSONArray("artists")) ?: return null
                val album = raw.optString("album").trim()
                // TTML Hub also publishes entries without a platform ID for
                // text-search/future metadata use. They must not invalidate
                // the entire catalog needed for exact Apple Music matches.
                val sourceIds = raw.optJSONObject("sourceIds")
                val appleMusicIds = parseIds(sourceIds?.opt("appleMusicId")) ?: return null
                val path = raw.optString("path").trim().takeIf(::isSafePath) ?: return null
                val sha256 = raw.optString("sha256").trim()
                    .takeIf(SHA256_PATTERN::matches) ?: return null
                add(LunabeatSong(title, artists, album, appleMusicIds, path, sha256.lowercase()))
            }
        }

        private fun parseStrings(array: JSONArray?): List<String>? {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isBlank()) return null
                    add(value)
                }
            }
        }

        private fun parseIds(value: Any?): List<Long>? {
            if (value == null) return emptyList()
            val values: List<Any?> = when (value) {
                is JSONArray -> buildList<Any?> {
                    for (index in 0 until value.length()) add(value.opt(index))
                }
                is String, is Number -> listOf(value)
                else -> return null
            }
            val result = linkedSetOf<Long>()
            values.forEach { raw ->
                val id = raw.toString().trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
                if (!result.add(id)) return null
            }
            return result.toList().takeIf(List<Long>::isNotEmpty)
        }

        private fun isSafePath(path: String): Boolean =
            path.isNotBlank() &&
                !path.startsWith('/') &&
                !path.contains('\\') &&
                path.split('/').all { segment ->
                    segment.isNotEmpty() && segment != "." && segment != ".." &&
                        segment.none(Char::isISOControl)
                }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun encodePath(path: String): String? {
        if (!isSafePath(path)) return null
        return path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
