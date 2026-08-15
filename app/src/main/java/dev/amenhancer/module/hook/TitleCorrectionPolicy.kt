package dev.amenhancer.module.hook

import java.util.Locale

/**
 * Pure title-correction policy shared by every display seam.  No Android
 * types here: the JVM tests pin the AMTool-equivalent semantics (cache keys,
 * schema/locale invalidation, the title replacement gate and the album-key
 * safety rule) without a device.
 */
internal object TitleCorrectionPolicy {
    /** Bumped when the meaning of a persisted cache key changes. */
    const val SCHEMA_VERSION = 2

    /** Data written by the first AM++ catalog cache schema (Phase 171/172). */
    const val LEGACY_SCHEMA = 1

    enum class CacheKind { TITLE, SONG, ARTIST, ALBUM, ALBUM_NAME }

    enum class EntityKind { SONG, ALBUM, UNKNOWN }

    enum class Script { HAN, LATIN, OTHER }

    /** Legacy generic key; kept for compatibility with existing user data. */
    fun cacheKey(localeTag: String, appleMusicId: String): String =
        "catalog-title:$localeTag:$appleMusicId"

    fun cacheKey(kind: CacheKind, localeTag: String, appleMusicId: String): String {
        val prefix = when (kind) {
            CacheKind.TITLE -> "catalog-title"
            CacheKind.SONG -> "catalog-song"
            CacheKind.ARTIST -> "catalog-artist"
            CacheKind.ALBUM -> "catalog-album"
            CacheKind.ALBUM_NAME -> "catalog-album-name"
        }
        return "$prefix:$localeTag:$appleMusicId"
    }

    fun schemaKey(): String = "catalog-schema"

    /**
     * Schema 1 wrote a song's *album name* under `catalog-album:` on the
     * capture path, so album-key values are only authoritative after the
     * schema 2 marker has been written by a fresh capture.
     */
    fun isCurrentSchema(stored: Int?): Boolean = (stored ?: LEGACY_SCHEMA) >= SCHEMA_VERSION

    fun entityKindOf(className: String?): EntityKind {
        val name = className.orEmpty().lowercase(Locale.ROOT)
        return when {
            "album" in name -> EntityKind.ALBUM
            "song" in name -> EntityKind.SONG
            else -> EntityKind.UNKNOWN
        }
    }

    fun scriptOf(text: String): Script {
        var han = false
        var latin = false
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF) {
                han = true
            }
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                latin = true
            }
            if (han && latin) break
        }
        return when {
            han -> Script.HAN
            latin -> Script.LATIN
            else -> Script.OTHER
        }
    }

    fun isCjkLocale(localeTag: String?): Boolean = runCatching {
        val language = Locale.forLanguageTag(localeTag.orEmpty().replace('_', '-'))
            .language
            .lowercase(Locale.ROOT)
        language == "zh" || language == "ja" || language == "ko"
    }.getOrDefault(false)

    /**
     * Matches AMTool's `qt.z` decision for a Catalog relationship title.
     * AMTool accepts any non-blank title that differs from the raw value; it
     * does not gate that choice by script or target locale.  Key/schema and
     * entity-type safety remain enforced by [correctionCandidate].
     */
    @Suppress("UNUSED_PARAMETER")
    fun allowReplace(current: String?, candidate: String?, localeTag: String?): Boolean {
        return !current.isNullOrBlank() && !candidate.isNullOrBlank() && current != candidate
    }

    /**
     * The single decision core shared by every correction hook.
     *
     * The ALBUM kind is only consulted for album-kind entities and only after
     * the schema 2 migration; this is the fix for songs whose id collides
     * with a cached album entry and would otherwise be overwritten by an
     * album name.  Only the title kinds (TITLE/SONG) may fall back to the
     * legacy generic TITLE key or to each other; ARTIST and ALBUM_NAME read
     * their own keys exclusively so a song title can never pollute artist or
     * album fields.  Every accepted candidate must pass the non-blank,
     * different-title gate.
     */
    fun correctionCandidate(
        appleMusicId: String,
        raw: String?,
        kind: CacheKind,
        values: Map<String, String>,
        localeTag: String,
        entityKind: EntityKind,
        schemaCurrent: Boolean,
    ): String? {
        if (appleMusicId.isBlank() || raw.isNullOrBlank()) return null
        if (kind == CacheKind.ALBUM && (!schemaCurrent || entityKind != EntityKind.ALBUM)) {
            return null
        }
        val direct = values[cacheKey(kind, localeTag, appleMusicId)]
        val candidate = when (kind) {
            // A song title must never stand in for an artist or album name.
            CacheKind.ARTIST, CacheKind.ALBUM_NAME -> direct
            CacheKind.TITLE -> direct
                ?: values[cacheKey(CacheKind.SONG, localeTag, appleMusicId)]
            else -> direct ?: values[cacheKey(CacheKind.TITLE, localeTag, appleMusicId)]
        } ?: return null
        return candidate.takeIf { allowReplace(raw, it, localeTag) }
    }

    /** Legacy precondition kept for compatibility with existing tests. */
    fun usable(raw: String?, candidate: String?): Boolean =
        !raw.isNullOrBlank() && !candidate.isNullOrBlank() && raw != candidate
}
