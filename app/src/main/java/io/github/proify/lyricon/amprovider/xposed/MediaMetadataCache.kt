/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 100
    }
    @Volatile
    private var activeProfile = DEFAULT_PROFILE

    /** Selects the in-memory namespace used by the currently installed runtime. */
    @Synchronized
    fun setProfile(profile: String) {
        val normalized = normalizeProfile(profile)
        if (activeProfile == normalized) return
        activeProfile = normalized
        metadataCache.clear()
    }

    @Synchronized
    fun profile(): String = activeProfile

    @Synchronized
    fun clearProfile(profile: String = activeProfile) {
        val prefix = normalizeProfile(profile) + ":"
        metadataCache.keys.removeIf { it.startsWith(prefix) }
    }

    /** 缓存从 Apple Music 内部播放队列中提取的歌曲元数据。 */
    @Synchronized
    fun put(metadata: Metadata, profile: String = activeProfile) {
        val key = cacheKey(profile, metadata.id)
        val current = metadataCache[key]
        metadataCache[key] = metadata.copy(
            genre = mergeGenres(current?.genre, listOfNotNull(metadata.genre)),
            originalTitle = metadata.originalTitle ?: current?.originalTitle,
            originalArtist = metadata.originalArtist ?: current?.originalArtist,
            originalAlbum = metadata.originalAlbum ?: current?.originalAlbum,
            originalMetadataResolved = metadata.originalMetadataResolved ||
                current?.originalMetadataResolved == true,
        )
    }

    @Synchronized
    fun getMetadataById(mediaId: String, profile: String = activeProfile): Metadata? =
        metadataCache[cacheKey(profile, mediaId)]

    @Synchronized
    fun updateOriginalMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
        album: String? = null,
        resolved: Boolean = true,
        profile: String = activeProfile,
    ): Metadata? {
        val key = cacheKey(profile, mediaId)
        val current = metadataCache[key] ?: return null
        val updated = current.copy(
            originalTitle = title,
            originalArtist = artist,
            originalAlbum = album,
            originalMetadataResolved = resolved,
        )
        metadataCache[key] = updated
        return updated
    }

    @Synchronized
    fun updateDisplayMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
        profile: String = activeProfile,
    ): Metadata? {
        val key = cacheKey(profile, mediaId)
        val current = metadataCache[key] ?: return null
        val updated = current.copy(
            title = title?.takeIf(String::isNotBlank) ?: current.title,
            artist = artist?.takeIf(String::isNotBlank) ?: current.artist,
        )
        metadataCache[key] = updated
        return updated
    }

    @Synchronized
    fun updateCatalogGenres(
        mediaId: String,
        genres: Collection<String>,
        profile: String = activeProfile,
    ): Metadata? {
        val key = cacheKey(profile, mediaId)
        val current = metadataCache[key] ?: return null
        val mergedGenre = mergeGenres(current.genre, genres) ?: return current
        if (mergedGenre == current.genre) return current
        val updated = current.copy(genre = mergedGenre)
        metadataCache[key] = updated
        return updated
    }

    private fun mergeGenres(current: String?, genres: Collection<String>): String? =
        sequenceOf(current)
            .plus(genres.asSequence())
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotEmpty)

    private fun cacheKey(profile: String, mediaId: String): String =
        normalizeProfile(profile) + ":" + mediaId.trim()

    private fun normalizeProfile(profile: String): String = profile
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .trim('_')
        .ifBlank { DEFAULT_PROFILE }

    private const val DEFAULT_PROFILE = "account"

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val genre: String?,
        val originalTitle: String? = null,
        val originalArtist: String? = null,
        val originalAlbum: String? = null,
        val originalMetadataResolved: Boolean = false,
        val duration: Long,
        val queueId: Long
    )
}
