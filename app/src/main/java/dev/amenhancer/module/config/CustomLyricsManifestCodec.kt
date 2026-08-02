package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** JSON codec for the manifest only; TTML remains in remote files. */
internal object CustomLyricsManifestCodec {
    fun encode(manifest: CustomLyricsManifest): String {
        val safe = CustomLyricsManifestPolicy.sanitize(manifest)
        return JSONObject().apply {
            put(KEY_VERSION, VERSION)
            put(KEY_ENTRIES, JSONArray().apply {
                safe.entries.forEach { entry ->
                    put(JSONObject().apply {
                        put(KEY_APPLE_MUSIC_ID, entry.appleMusicId)
                        put(KEY_DISPLAY_NAME, entry.displayName)
                        put(KEY_FILE_ID, entry.fileId)
                        put(KEY_SIZE_BYTES, entry.sizeBytes)
                        put(KEY_SHA256, entry.sha256)
                        put(KEY_SOURCE, entry.source)
                        put(KEY_ENABLED, entry.enabled)
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String): CustomLyricsManifest = runCatching {
        val root = JSONObject(raw)
        if (root.optInt(KEY_VERSION, 0) != VERSION) return@runCatching CustomLyricsManifest.empty()
        val entries = root.optJSONArray(KEY_ENTRIES) ?: return@runCatching CustomLyricsManifest.empty()
        val parsed = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                add(
                    CustomLyricsEntry(
                        appleMusicId = entry.optLong(KEY_APPLE_MUSIC_ID, 0L),
                        displayName = entry.optString(KEY_DISPLAY_NAME),
                        fileId = entry.optString(KEY_FILE_ID),
                        sizeBytes = entry.optLong(KEY_SIZE_BYTES, 0L),
                        sha256 = entry.optString(KEY_SHA256),
                        source = entry.optString(KEY_SOURCE),
                        enabled = entry.optBoolean(KEY_ENABLED, false),
                    ),
                )
            }
        }
        CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(parsed))
    }.getOrDefault(CustomLyricsManifest.empty())

    /**
     * Strict decode for backup restore: returns null unless the version
     * matches exactly, the entries array parses cleanly, and every entry
     * survives policy sanitization unchanged. A corrupt or foreign-version
     * manifest is never treated as a valid empty backup.
     */
    fun decodeStrict(raw: String): CustomLyricsManifest? {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        if (root.optInt(KEY_VERSION, 0) != VERSION) return null
        val entries = root.optJSONArray(KEY_ENTRIES) ?: return null
        if (entries.length() > CustomLyricsManifestPolicy.MAX_ENTRIES) return null
        val parsed = mutableListOf<CustomLyricsEntry>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: return null
            parsed += CustomLyricsEntry(
                appleMusicId = entry.optLong(KEY_APPLE_MUSIC_ID, 0L),
                displayName = entry.optString(KEY_DISPLAY_NAME),
                fileId = entry.optString(KEY_FILE_ID),
                sizeBytes = entry.optLong(KEY_SIZE_BYTES, 0L),
                sha256 = entry.optString(KEY_SHA256),
                source = entry.optString(KEY_SOURCE),
                enabled = entry.optBoolean(KEY_ENABLED, false),
            )
        }
        val sanitized = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(parsed))
        return sanitized.takeIf { it.entries.size == parsed.size }
    }

    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_APPLE_MUSIC_ID = "appleMusicId"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_FILE_ID = "fileId"
    private const val KEY_SIZE_BYTES = "sizeBytes"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_SOURCE = "source"
    private const val KEY_ENABLED = "enabled"
}
