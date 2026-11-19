package dev.amenhancer.module.config

import dev.amenhancer.module.hook.AutoLyricsCandidate
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsInspection
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class LyricsCacheEntry(
    val entry: CustomLyricsEntry,
    val lastUsedAtMs: Long,
)

internal data class LyricsCacheSnapshot(
    val entries: List<LyricsCacheEntry>,
    val usageBytes: Long,
    val indexAvailable: Boolean,
)

/** Returns the stored song name only when it is not a source, ID, or blank placeholder. */
internal fun CustomLyricsEntry.cachedSongDisplayName(): String? =
    cacheSongDisplayName(displayName, source, appleMusicId)

private val CACHE_SONG_NAME_PLACEHOLDERS = setOf(
    "auto cache",
    "auto-cache",
    "amll",
    "amll-ttml-db",
    "am-lyrics",
    "am-lyrics custom lyrics",
    "am-lyrics 自定义歌词",
    "lunabeat",
    "lunabeat-ttml-hub",
    "lunabeat custom lyrics",
    "lunabeat 自定义歌词",
)

private fun cacheSongDisplayName(name: String, source: String, appleMusicId: Long): String? {
    val trimmed = name.trim()
    val id = appleMusicId.toString()
    if (trimmed.isEmpty() || trimmed == id) return null
    val normalized = trimmed.lowercase(Locale.ROOT)
    if (trimmed.equals(source, ignoreCase = true) || normalized in CACHE_SONG_NAME_PLACEHOLDERS) return null
    if (normalized == "auto-cached lyrics · $id" || normalized == "自动缓存歌词 · $id") return null
    return trimmed
}

internal fun String.isCompleteCacheSongName(): Boolean {
    val normalized = trim()
    val separator = normalized.indexOf(" - ")
    return separator > 0 && normalized.substring(separator + 3).isNotBlank()
}

/**
 * Third-party source cache plus a metadata snapshot of the manual index.
 *
 * The source index is keyed by (Apple Music ID, source), so providers can
 * coexist for one song. The manual index keeps its existing file and pointer;
 * this class references those bodies and never copies or relocates them.
 *
 * Metadata getters use only the in-memory snapshot. Body reads, index commits,
 * usage scans and clearing run on the caller's worker. The state lock protects
 * snapshots and is never held while a listener runs. Mutations that publish
 * indexes use the shared mutation lock and do file/index I/O with the state lock released.
 */
internal class EmbeddedLyricsSourceStore(
    private val session: EmbeddedConfigurationSession,
    private val newFileId: (String) -> String = { prefix ->
        "${prefix}_${java.util.UUID.randomUUID().toString().replace("-", "")}"
    },
) {
    private data class Key(val appleMusicId: Long, val source: String)

    private class State {
        val stateLock = Any()
        val listeners = linkedSetOf<() -> Unit>()
        var normalized = false
        var manualManifest = CustomLyricsManifest.empty()
        var sourceManifest = CustomLyricsManifest.empty()
        val preferred = linkedMapOf<Long, String>()
        val offsets = linkedMapOf<Key, Int>()
        val lastUsed = linkedMapOf<Key, Long>()
        val pendingDeletes = linkedSetOf<String>()
        var generation = 0L
    }

    private val shared = State()

    init {
        initialize()
    }

    /** Snapshot only; includes disabled entries for the editor/list. */
    fun manualEntry(appleMusicId: Long): CustomLyricsEntry? = synchronized(shared.stateLock) {
        shared.manualManifest.entries.firstOrNull {
            it.appleMusicId == appleMusicId && it.source == CustomLyricsSources.MANUAL
        }
    }

    /** Snapshot only; keeps the old disabled intent instead of silently enabling a song. */
    fun isSuppressed(appleMusicId: Long): Boolean = synchronized(shared.stateLock) {
        shared.manualManifest.entries.any { it.appleMusicId == appleMusicId && !it.enabled } ||
            shared.sourceManifest.entries.any { it.appleMusicId == appleMusicId && !it.enabled }
    }

    fun entries(): List<CustomLyricsEntry> = synchronized(shared.stateLock) { shared.sourceManifest.entries }

    /** Read-only management snapshot. It neither migrates legacy rows nor touches the persisted LRU. */
    fun cacheSnapshot(): LyricsCacheSnapshot = session.withCustomLyricsMutation {
        val normalized = synchronized(shared.stateLock) { shared.normalized }
        val sourceState = if (normalized) {
            null
        } else {
            runCatching { readSourceState() }.getOrNull()
        }
        val resolvedSourceState = sourceState?.takeIf { it.isResolved }
        val manifest = resolvedSourceState?.manifest
            ?: synchronized(shared.stateLock) { shared.sourceManifest }
        val lastUsed = synchronized(shared.stateLock) { shared.lastUsed.toMap() }
        val entries = manifest.entries.map { entry ->
            LyricsCacheEntry(
                entry = entry,
                lastUsedAtMs = lastUsed[Key(entry.appleMusicId, entry.source)] ?: 0L,
            )
        }
        // A resolved empty index is available too, including immediately after a full clear.
        val indexAvailable = normalized || resolvedSourceState != null
        LyricsCacheSnapshot(
            entries = entries,
            usageBytes = usageBytesLocked(),
            indexAvailable = indexAvailable,
        )
    }

    /** Backfills display names only. Bodies, preferences, LRU order and cache generation stay untouched. */
    fun updateCacheSongNames(names: Map<Long, String>, expectedGeneration: Long): Boolean {
        if (!session.writable || names.isEmpty()) return false
        val usableNames = names.mapNotNull { (appleMusicId, name) ->
            appleMusicId.takeIf { it > 0L }?.let { id ->
                name.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { id to it }
            }
        }.toMap()
        if (usableNames.isEmpty()) return false
        return session.withCustomLyricsMutation {
            if (!generationMatches(expectedGeneration)) return@withCustomLyricsMutation false
            val needsUpdate = synchronized(shared.stateLock) {
                !shared.normalized || shared.sourceManifest.entries.any { entry ->
                    entry.appleMusicId in usableNames &&
                        entry.cachedSongDisplayName()?.isCompleteCacheSongName() != true
                }
            }
            if (!needsUpdate) return@withCustomLyricsMutation true
            if (!prepareSource()) return@withCustomLyricsMutation false
            val current = synchronized(shared.stateLock) { shared.sourceManifest }
            var changed = false
            val entries = current.entries.map { entry ->
                val proposed = usableNames[entry.appleMusicId] ?: return@map entry
                val currentName = entry.cachedSongDisplayName()
                if (currentName?.isCompleteCacheSongName() == true) return@map entry
                val sanitized = cacheSongDisplayName(
                    CustomLyricsManifestPolicy.sanitizeDisplayName(proposed),
                    entry.source,
                    entry.appleMusicId,
                ) ?: return@map entry
                if (sanitized == entry.displayName) return@map entry
                changed = true
                entry.copy(displayName = sanitized)
            }
            if (!changed) return@withCustomLyricsMutation true
            val manifest = CustomLyricsManifest(entries)
            if (!commitSourceManifest(manifest)) return@withCustomLyricsMutation false
            synchronized(shared.stateLock) { shared.sourceManifest = manifest }
            notifyListeners()
            true
        }
    }

    fun entryFor(appleMusicId: Long, source: String): CustomLyricsEntry? =
        synchronized(shared.stateLock) {
            shared.sourceManifest.entries.firstOrNull {
                it.appleMusicId == appleMusicId && it.source == source
            }
        }

    /** Updates one provider's enabled intent without publishing a manual entry. */
    fun setSourceEnabled(appleMusicId: Long, source: String, enabled: Boolean): Boolean {
        if (!session.writable) return false
        return session.withCustomLyricsMutation {
            val current = synchronized(shared.stateLock) {
                shared.sourceManifest.entries.firstOrNull {
                    it.appleMusicId == appleMusicId && it.source == source
                }
            } ?: return@withCustomLyricsMutation false
            if (current.enabled == enabled) return@withCustomLyricsMutation true
            val next = synchronized(shared.stateLock) {
                shared.sourceManifest.entries.map { entry ->
                    if (entry.appleMusicId == appleMusicId && entry.source == source) {
                        entry.copy(enabled = enabled)
                    } else entry
                }
            }
            if (!commitSourceManifest(CustomLyricsManifest(next))) return@withCustomLyricsMutation false
            synchronized(shared.stateLock) {
                shared.sourceManifest = CustomLyricsManifest(next)
            }
            notifyListeners()
            true
        }
    }

    /**
     * Deletes one provider slot, or every provider slot for [appleMusicId] when [source] is null.
     * The source and manual indexes must already be readable. The manifest is published once,
     * then only bodies without any remaining source or manual reference are reclaimed.
     */
    fun deleteCachedLyrics(appleMusicId: Long, source: String? = null): Boolean {
        if (!session.writable || appleMusicId <= 0L) return false
        val requestedSource = if (source == null) {
            null
        } else {
            CustomLyricsManifestPolicy.canonicalSource(source).takeIf(
                CustomLyricsManifestPolicy::isThirdPartySource,
            ) ?: return false
        }
        return session.withCustomLyricsMutation {
            // Finish the existing legacy migration first; a partial migration must not be deleted.
            if (!prepareSource()) return@withCustomLyricsMutation false
            if (!synchronized(shared.stateLock) { shared.normalized }) {
                return@withCustomLyricsMutation false
            }
            val manualState = runCatching { session.readManualIndex() }.getOrNull()
                ?.takeIf { it.isResolved } ?: return@withCustomLyricsMutation false
            val before = synchronized(shared.stateLock) {
                SourceSnapshot(
                    manifest = shared.sourceManifest,
                    lastUsed = shared.lastUsed.toMap(),
                )
            }
            val removed = before.manifest.entries.filter { entry ->
                entry.appleMusicId == appleMusicId &&
                    (requestedSource == null || entry.source == requestedSource)
            }
            if (removed.isEmpty()) return@withCustomLyricsMutation false
            val removedKeys = removed.mapTo(mutableSetOf()) { entry ->
                Key(entry.appleMusicId, entry.source)
            }
            val nextManifest = CustomLyricsManifest(
                before.manifest.entries.filterNot { entry ->
                    Key(entry.appleMusicId, entry.source) in removedKeys
                },
            )
            if (!commitSourceManifest(nextManifest)) return@withCustomLyricsMutation false
            val lastUsed = before.lastUsed.toMutableMap().apply {
                removedKeys.forEach { key -> remove(key) }
            }
            synchronized(shared.stateLock) {
                shared.manualManifest = manualState.manifest
                shared.sourceManifest = nextManifest
                shared.lastUsed.clear()
                shared.lastUsed.putAll(lastUsed)
                // One mutation invalidates every request that started before this deletion.
                shared.generation++
            }
            persistLastUsed(lastUsed)
            retireUnreferenced(removed.map(CustomLyricsEntry::fileId), nextManifest)
            notifyListeners()
            true
        }
    }

    internal fun isFileReferenced(fileId: String): Boolean = synchronized(shared.stateLock) {
        shared.sourceManifest.entries.any { it.fileId == fileId }
    }

    fun preferredSource(appleMusicId: Long): String? = synchronized(shared.stateLock) {
        shared.preferred[appleMusicId]
    }

    fun setPreferredSource(appleMusicId: Long, source: String?): Boolean {
        if (!session.writable || appleMusicId <= 0L) return false
        val normalized = source
            ?.takeUnless { it == LyricsPlaybackSources.AUTOMATIC }
            ?.takeIf(CustomLyricsManifestPolicy::isUsableSourceId)
        return session.withCustomLyricsMutation {
            val next = synchronized(shared.stateLock) {
                linkedMapOf<Long, String>().apply {
                    putAll(shared.preferred)
                    if (normalized == null) remove(appleMusicId) else put(appleMusicId, normalized)
                }
            }
            if (!persistPreferred(next)) return@withCustomLyricsMutation false
            val changed = synchronized(shared.stateLock) {
                if (shared.preferred == next) false
                else {
                    shared.preferred.clear()
                    shared.preferred.putAll(next)
                    true
                }
            }
            if (changed) notifyListeners()
            true
        }
    }

    fun offsetMs(appleMusicId: Long, source: String): Int = synchronized(shared.stateLock) {
        shared.offsets[Key(appleMusicId, source)] ?: 0
    }

    fun setOffsetMs(appleMusicId: Long, source: String, offsetMs: Int): Boolean {
        if (!session.writable || appleMusicId <= 0L ||
            !CustomLyricsManifestPolicy.isUsableSourceId(source)
        ) return false
        val key = Key(appleMusicId, source)
        return session.withCustomLyricsMutation {
            val next = synchronized(shared.stateLock) {
                linkedMapOf<Key, Int>().apply {
                    putAll(shared.offsets)
                    if (offsetMs == 0) remove(key) else put(key, offsetMs)
                }
            }
            if (!persistOffsets(next)) return@withCustomLyricsMutation false
            val changed = synchronized(shared.stateLock) {
                if (shared.offsets == next) false
                else {
                    shared.offsets.clear()
                    shared.offsets.putAll(next)
                    true
                }
            }
            if (changed) notifyListeners()
            true
        }
    }

    /** Worker body read. A hit updates the persisted LRU without changing the available lyrics. */
    fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? {
        if (appleMusicId <= 0L) return null
        prepare()
        val snapshot = entryFor(appleMusicId, source)
        val entry: CustomLyricsEntry = snapshot
            ?: readLegacyCandidate(appleMusicId, source)
            ?: return null
        val candidate = readCandidateBody(entry) ?: return null
        if (snapshot != null) touch(entry)
        return candidate
    }

    /** Worker write. [expectedGeneration] rejects a request that started before clear. */
    fun writeCandidate(
        appleMusicId: Long,
        candidate: AutoLyricsCandidate,
        expectedGeneration: Long? = null,
        enabled: Boolean = true,
    ): Boolean = writeCandidates(listOf(appleMusicId), candidate, expectedGeneration, enabled)

    /** A batch publishes all IDs in one index update and shares its immutable body. */
    fun writeCandidates(
        ids: List<Long>,
        candidate: AutoLyricsCandidate,
        expectedGeneration: Long? = null,
        enabled: Boolean = true,
    ): Boolean {
        if (!session.writable || ids.isEmpty() || ids.any { it <= 0L } ||
            ids.toSet().size != ids.size
        ) return false
        val source = CustomLyricsManifestPolicy.canonicalSource(candidate.source)
        if (!CustomLyricsManifestPolicy.isThirdPartySource(source)) return false
        val inspection = CustomLyricsFilePolicy.inspect(candidate.ttml)
        if (inspection !is CustomLyricsInspection.Accepted) return false
        return session.withCustomLyricsMutation {
            if (!generationMatches(expectedGeneration) || !prepareSource()) return@withCustomLyricsMutation false
            val before = synchronized(shared.stateLock) {
                SourceSnapshot(
                    manifest = shared.sourceManifest,
                    lastUsed = shared.lastUsed.toMap(),
                )
            }
            val entries = before.manifest.entries.associateByTo(linkedMapOf()) { Key(it.appleMusicId, it.source) }
            val keys = ids.map { Key(it, source) }
            val name = CustomLyricsManifestPolicy.sanitizeDisplayName(candidate.displayName ?: source)
            val existing = keys.mapNotNull(entries::get)
            if (existing.size == keys.size && existing.all {
                it.sha256 == inspection.sha256 && it.displayName == name && it.enabled == enabled &&
                    session.fileLength(it.fileId) != null
            }) {
                return@withCustomLyricsMutation true
            }
            val fileId = runCatching { newFileId("source_lyrics") }.getOrNull()
                ?.takeIf(CustomLyricsManifestPolicy::isValidFileId) ?: return@withCustomLyricsMutation false
            if (session.fileLength(fileId) != null || !session.writeFile(fileId, inspection.bytes)) {
                return@withCustomLyricsMutation false
            }
            keys.forEach { key ->
                entries[key] = CustomLyricsEntry(key.appleMusicId, name, fileId, inspection.bytes.size.toLong(),
                    inspection.sha256, source, enabled, autoManaged = true)
            }
            val lastUsed = before.lastUsed.toMutableMap().apply {
                val now = System.currentTimeMillis()
                keys.forEach { put(it, now) }
            }
            val trimmed = trimSourceEntries(entries, lastUsed)
            val trimmedManifest = CustomLyricsManifest(trimmed.values.toList())
            if (!keys.all(trimmed::containsKey) || !commitSourceManifest(trimmedManifest)) {
                session.deleteSourceBodyFile(fileId)
                return@withCustomLyricsMutation false
            }
            synchronized(shared.stateLock) {
                shared.sourceManifest = trimmedManifest
                shared.lastUsed.clear()
                shared.lastUsed.putAll(lastUsed.filterKeys(trimmed::containsKey))
            }
            persistLastUsed(lastUsed)
            retireUnreferenced(before.manifest.entries.map(CustomLyricsEntry::fileId), trimmedManifest)
            notifyListeners()
            true
        }
    }

    /**
     * Clears source bodies, the source index and Lunabeat catalog while keeping
     * manual files and all preferred/offset metadata. Cache pointer recovery
     * only needs a readable manual index to protect any shared old bodies.
     */
    fun clear(): Boolean {
        if (!session.writable) return false
        return session.withCustomLyricsMutation {
            synchronized(shared.stateLock) { shared.generation++ }
            try {
                val manualState = runCatching { session.readManualIndex() }.getOrNull()
                    ?.takeIf { it.isResolved } ?: return@withCustomLyricsMutation false
                val (legacyProviders, manual) = manualState.manifest.entries.partition {
                    CustomLyricsManifestPolicy.isThirdPartySource(it.source)
                }
                val sourceState = runCatching { readSourceState() }.getOrNull()?.takeIf { it.isResolved }
                val sourceEntries = sourceState?.manifest?.entries.orEmpty() + entries()
                if (!session.writeValues(mapOf(LEGACY_CACHE_CLEARED_KEY to true))) {
                    return@withCustomLyricsMutation false
                }
                if (!session.clearSourceIndexPointer()) return@withCustomLyricsMutation false

                // If publishing a pruned manual index fails, its old references still protect the bodies.
                val manualPruned = legacyProviders.isEmpty() || run {
                    val next = CustomLyricsManifest(manual)
                    val limit = session.manualIndexCommitLimit(manualState, next) ?: return@run false
                    val result = session.commitManualIndex(manualState, next, maxIndexBytes = limit)
                    result is CustomLyricsIndexCommitResult.Committed
                }
                val empty = CustomLyricsManifest.empty()
                synchronized(shared.stateLock) {
                    shared.sourceManifest = empty
                    shared.lastUsed.clear()
                    shared.normalized = false
                    shared.manualManifest = if (manualPruned) CustomLyricsManifest(manual) else manualState.manifest
                }
                val ownedFiles = (sourceEntries + legacyProviders).map(CustomLyricsEntry::fileId) +
                    listOfNotNull(sourceState?.pointer?.fileId) + session.sourceFileIds()
                val removedBodies = retireUnreferenced(ownedFiles, empty)
                val legacyFiles = session.sourceCacheRootFiles().filter {
                    isSourceCacheFile(it.name) && !isManualFile(it.name)
                }
                val removedLegacy = (legacyFiles + listOfNotNull(
                    session.sourceCacheLunabeatCatalog(), session.legacyLunabeatCatalog(),
                )).distinct().map { file -> runCatching { !file.isFile || file.delete() }.getOrDefault(false) }
                    .all { it }
                val resetLru = persistLastUsed(emptyMap())
                removedBodies && removedLegacy && resetLru && manualPruned
            } finally {
                notifyListeners()
            }
        }
    }

    /**
     * Real on-disk usage: current source index, its unique referenced bodies,
     * provider rows still pending in the manual index, unclassified legacy
     * candidates and the Lunabeat catalog. Uses metadata only; it never reads
     * TTML bodies.
     */
    fun sizeBytes(): Long = session.withCustomLyricsMutation { usageBytesLocked() }

    private fun usageBytesLocked(): Long {
        val diskIds = session.sourceFileIds() + listOfNotNull(
            session.decodeSourceIndexPointer(session.values())?.fileId,
        )
        val referenced = synchronized(shared.stateLock) {
            shared.sourceManifest.entries.mapTo(linkedSetOf(), CustomLyricsEntry::fileId).apply {
                addAll(shared.pendingDeletes)
                addAll(diskIds)
                shared.manualManifest.entries.filter {
                    CustomLyricsManifestPolicy.isThirdPartySource(it.source)
                }.mapTo(this, CustomLyricsEntry::fileId)
                removeAll(shared.manualManifest.entries.filter {
                    it.source == CustomLyricsSources.MANUAL
                }.map(CustomLyricsEntry::fileId).toSet())
            }
        }
        return referenced.sumOf(::fileBytesOnDisk) + session.sourceCacheRootFiles().sumOf {
            if (isSourceCacheFile(it.name) && it.name !in referenced && !isManualFile(it.name)) it.length()
            else 0L
        } + listOfNotNull(session.sourceCacheLunabeatCatalog(), session.legacyLunabeatCatalog())
            .sumOf { it.takeIf(File::isFile)?.length() ?: 0L }
    }

    fun generation(): Long = synchronized(shared.stateLock) { shared.generation }

    /** Apply one provider's refresh without replacing or deleting a different provider's slot. */
    fun replaceUpdatedEntries(baseline: List<CustomLyricsEntry>, next: CustomLyricsManifest, epoch: Long): Boolean =
        writeIfCurrent(epoch) {
            val before = synchronized(shared.stateLock) { SourceSnapshot(shared.sourceManifest, shared.lastUsed.toMap()) }
            val entries = before.manifest.entries.associateByTo(linkedMapOf()) { Key(it.appleMusicId, it.source) }
            if (baseline.any { entries[Key(it.appleMusicId, it.source)] != it }) return@writeIfCurrent false
            baseline.forEach { entries.remove(Key(it.appleMusicId, it.source)) }
            next.entries.forEach { entry ->
                if (entries.putIfAbsent(Key(entry.appleMusicId, entry.source), entry) != null) return@writeIfCurrent false
            }
            val lastUsed = before.lastUsed.toMutableMap()
            val trimmed = trimSourceEntries(entries, lastUsed)
            if (next.entries.any { Key(it.appleMusicId, it.source) !in trimmed }) return@writeIfCurrent false
            val manifest = CustomLyricsManifest(trimmed.values.toList())
            if (!commitSourceManifest(manifest)) return@writeIfCurrent false
            synchronized(shared.stateLock) {
                shared.sourceManifest = manifest
                shared.lastUsed.clear()
                shared.lastUsed.putAll(lastUsed)
            }
            persistLastUsed(lastUsed)
            retireUnreferenced(before.manifest.entries.map(CustomLyricsEntry::fileId), manifest)
            notifyListeners()
            true
        }

    fun observe(listener: () -> Unit): AutoCloseable {
        synchronized(shared.stateLock) { shared.listeners += listener }
        return AutoCloseable {
            synchronized(shared.stateLock) { shared.listeners -= listener }
        }
    }

    /** Serializes publication with clear. The operation owns its write permission. */
    internal fun writeIfCurrent(expectedGeneration: Long, block: () -> Boolean): Boolean =
        session.withCustomLyricsMutation { generationMatches(expectedGeneration) && block() }

    internal fun manualManifest(): CustomLyricsManifest = synchronized(shared.stateLock) {
        CustomLyricsManifest(shared.manualManifest.entries.filter { it.source == CustomLyricsSources.MANUAL })
    }

    internal fun manualIndexManifest(): CustomLyricsManifest = synchronized(shared.stateLock) {
        shared.manualManifest
    }

    /** Keep provider rows in the manual index until their source index is usable. */
    internal fun preserveUnmigratedSources(next: CustomLyricsManifest): CustomLyricsManifest {
        val nextIds = next.entries.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        val pending = synchronized(shared.stateLock) {
            shared.manualManifest.entries.filter {
                CustomLyricsManifestPolicy.isThirdPartySource(it.source) && it.appleMusicId !in nextIds
            }
        }
        return CustomLyricsManifest(pending + next.entries)
    }

    internal fun onManualManifestChanged(manifest: CustomLyricsManifest) {
        val safe = CustomLyricsManifestPolicy.sanitize(manifest)
        val changed = synchronized(shared.stateLock) {
            if (shared.manualManifest == safe) false
            else {
                shared.manualManifest = safe
                true
            }
        }
        if (changed) notifyListeners()
    }

    private fun initialize() = session.withCustomLyricsMutation {
        shared.preferred.putAll(readPreferred())
        shared.offsets.putAll(readOffsets())
        shared.lastUsed.putAll(readLastUsed())
        shared.pendingDeletes.addAll(runCatching {
            val array = JSONArray(session.values()[PENDING_DELETE_KEY] as? String ?: "[]")
            (0 until array.length()).map { array.optString(it) }.filter(::isStorableFileId)
        }.getOrDefault(emptyList()))
        shared.manualManifest = runCatching { session.readManualIndex().manifest }
            .getOrDefault(CustomLyricsManifest.empty())
        shared.sourceManifest = runCatching { readSourceState().manifest }
            .getOrDefault(CustomLyricsManifest.empty())
        session.observeManualChanges(::onManualManifestChanged)
    }

    /**
     * Migrate provider rows only after the manual index itself is readable. The
     * source index is published before provider rows leave the manual index;
     * an unreadable source index keeps those rows in place for a later retry.
     * Returns whether the manual index is usable, not whether the source index
     * happens to be readable. The legacy-clear marker suppresses directory
     * discovery only; provider rows already restored into the manual index
     * remain migratable.
     */
    internal fun prepare(afterRestore: Boolean = false): Boolean = session.withCustomLyricsMutation {
        if (!session.writable) return@withCustomLyricsMutation false
        if (!afterRestore && synchronized(shared.stateLock) { shared.normalized }) return@withCustomLyricsMutation true
        val manualState = runCatching { session.readManualIndex() }.getOrNull()
            ?: return@withCustomLyricsMutation false
        if (!manualState.isResolved) return@withCustomLyricsMutation false
        val sourceState = runCatching { readSourceState() }.getOrNull()
        if (sourceState == null || !sourceState.isResolved) {
            synchronized(shared.stateLock) {
                shared.manualManifest = manualState.manifest
                shared.normalized = false
            }
            return@withCustomLyricsMutation true
        }
        val values = session.values()
        val includeLegacy = values[LEGACY_CACHE_CLEARED_KEY] != true && values[LEGACY_NORMALIZED_KEY] != true
        val thirdParty = manualState.manifest.entries.filter {
            CustomLyricsManifestPolicy.isThirdPartySource(it.source)
        }
        val manualOnly = manualState.manifest.entries.filterNot {
            CustomLyricsManifestPolicy.isThirdPartySource(it.source)
        }
        val legacyEntries = if (includeLegacy) readLegacyCacheEntries() else emptyList()
        val sourceEntries = linkedMapOf<Key, CustomLyricsEntry>().apply {
            sourceState.manifest.entries.forEach { put(Key(it.appleMusicId, it.source), it) }
            (thirdParty + legacyEntries).forEach {
                putIfAbsent(Key(it.appleMusicId, it.source), it)
            }
        }
        val lastUsed = linkedMapOf<Key, Long>().apply {
            synchronized(shared.stateLock) { putAll(shared.lastUsed) }
            thirdParty.forEach { putIfAbsent(Key(it.appleMusicId, it.source), 0L) }
            legacyEntries.forEach { entry ->
                putIfAbsent(Key(entry.appleMusicId, entry.source),
                    session.sourceCacheFile(entry.fileId)?.lastModified() ?: 0L)
            }
        }
        val trimmed = trimSourceEntries(sourceEntries, lastUsed)
        val sourceManifest = CustomLyricsManifest(trimmed.values.toList())
        val sourceChanged = sourceManifest != sourceState.manifest
        if (sourceChanged && !commitSourceManifest(sourceManifest)) {
            synchronized(shared.stateLock) {
                shared.manualManifest = manualState.manifest
                shared.sourceManifest = sourceState.manifest
                shared.normalized = false
            }
            return@withCustomLyricsMutation true
        }

        val nextManual = CustomLyricsManifest(manualOnly)
        val manualChanged = nextManual != manualState.manifest
        if (manualChanged) {
            val maxBytes = session.manualIndexCommitLimit(manualState, nextManual)
            val committed = maxBytes != null && session.commitManualIndex(
                manualState,
                nextManual,
                maxIndexBytes = maxBytes,
            ) is CustomLyricsIndexCommitResult.Committed
            if (!committed) {
                synchronized(shared.stateLock) {
                    shared.manualManifest = manualState.manifest
                    shared.sourceManifest = sourceManifest
                    shared.normalized = false
                }
                if (sourceChanged) notifyListeners()
                return@withCustomLyricsMutation true
            }
        }

        val marked = !includeLegacy || session.writeValues(mapOf(LEGACY_NORMALIZED_KEY to true))
        synchronized(shared.stateLock) {
            shared.sourceManifest = sourceManifest
            shared.manualManifest = nextManual
            shared.lastUsed.clear()
            shared.lastUsed.putAll(lastUsed.filterKeys(trimmed::containsKey))
            shared.normalized = marked
        }
        if (marked) {
            persistLastUsed(lastUsed)
            retireUnreferenced((sourceState.manifest.entries + thirdParty + legacyEntries)
                .map(CustomLyricsEntry::fileId), sourceManifest)
        }
        if (sourceChanged || manualChanged) notifyListeners()
        true
    }

    /** Source mutations retain the original fail-closed behavior for a corrupt source index. */
    internal fun prepareSource(): Boolean {
        if (!prepare()) return false
        return runCatching { readSourceState().isResolved }.getOrDefault(false)
    }

    /** Binary-search the retained LRU suffix; avoids repeated full JSON encodes. */
    private fun trimSourceEntries(
        entries: MutableMap<Key, CustomLyricsEntry>,
        lastUsed: MutableMap<Key, Long>,
    ): Map<Key, CustomLyricsEntry> {
        if (entries.isEmpty()) { lastUsed.clear(); return emptyMap() }
        val ordered = entries.keys.sortedBy { lastUsed[it] ?: 0L }
        fun encodesWithinLimit(keys: List<Key>): Boolean = SourceIndexCodec.encode(
            CustomLyricsManifest(keys.map(entries::getValue)),
        ).toByteArray(Charsets.UTF_8).size <= CustomLyricsManifestPolicy.MAX_CURRENT_INDEX_BYTES
        var low = 0
        var high = ordered.size
        if (encodesWithinLimit(ordered)) high = 0
        while (low < high) {
            val drop = (low + high) / 2
            if (encodesWithinLimit(ordered.subList(drop, ordered.size))) high = drop
            else low = drop + 1
        }
        val retained = ordered.drop(low).associateWith(entries::getValue)
        lastUsed.keys.removeAll { it !in retained }
        return retained
    }

    private data class SourceSnapshot(
        val manifest: CustomLyricsManifest,
        val lastUsed: Map<Key, Long>,
    )

    private fun generationMatches(expected: Long?): Boolean = synchronized(shared.stateLock) {
        expected == null || shared.generation == expected
    }

    private fun touch(entry: CustomLyricsEntry) = session.withCustomLyricsMutation {
        if (!session.writable) return@withCustomLyricsMutation
        val key = Key(entry.appleMusicId, entry.source)
        val next = synchronized(shared.stateLock) {
            val latest = shared.sourceManifest.entries.firstOrNull {
                it.appleMusicId == entry.appleMusicId && it.source == entry.source
            }
            if (latest?.copy(displayName = entry.displayName) != entry) return@withCustomLyricsMutation
            linkedMapOf<Key, Long>().apply {
                putAll(shared.lastUsed)
                put(key, System.currentTimeMillis())
            }
        }
        if (persistLastUsed(next)) synchronized(shared.stateLock) {
            shared.lastUsed.clear()
            shared.lastUsed.putAll(next)
        }
    }

    private fun readSourceState(): CustomLyricsIndexState = session.readSourceIndex(
        pointerDecoder = session::decodeSourceIndexPointer,
        pointerPresent = session::hasSourceIndexPointerValues,
        legacyDecoder = { CustomLyricsManifest.empty() },
        decodeIndex = SourceIndexCodec::decode,
        openFile = session::openFile,
        readIndexBytes = { fileId, maxBytes -> session.readFileBytes(fileId, maxBytes) },
    )

    private fun commitSourceManifest(manifest: CustomLyricsManifest): Boolean {
        val state = runCatching { readSourceState() }.getOrNull() ?: return false
        if (!state.canCommit) return false
        val result = session.commitSourceIndex(
            state = state,
            manifest = SourceIndexCodec.sanitize(manifest),
            newFileId = { newFileId("source_index") },
            publishPointer = { pointer ->
                session.writeValues(session.encodeSourceIndexPointer(pointer))
            },
            maxIndexBytes = CustomLyricsManifestPolicy.MAX_CURRENT_INDEX_BYTES,
            encode = SourceIndexCodec::encode,
            decode = SourceIndexCodec::decode,
            writeFile = session::writeFile,
            deleteFile = session::deleteFile,
            openFile = session::openFile,
            readIndexBytes = { fileId, maxBytes -> session.readFileBytes(fileId, maxBytes) },
        )
        return result is CustomLyricsIndexCommitResult.Committed
    }

    private fun readCandidateBody(entry: CustomLyricsEntry): AutoLyricsCandidate? {
        val legacy = if (LEGACY_FILE_ID.matches(entry.fileId)) {
            session.sourceCacheFile(entry.fileId)?.takeIf(File::isFile)
        } else null
        val ttml = if (legacy != null) parseLegacyFile(legacy)?.second else
            session.readFileBytes(entry.fileId)?.toString(Charsets.UTF_8)
        if (ttml == null) return null
        if (!TtmlInputPolicy.isAcceptable(ttml)) return null
        return AutoLyricsCandidate(entry.source, ttml, entry.displayName)
    }

    private fun readLegacyCandidate(appleMusicId: Long, source: String): CustomLyricsEntry? {
        val values = session.values()
        if (values[LEGACY_NORMALIZED_KEY] == true || values[LEGACY_CACHE_CLEARED_KEY] == true) return null
        return legacyFilesFor(appleMusicId).firstNotNullOfOrNull { file ->
            legacyEntry(appleMusicId, file)?.takeIf { it.source == source }
        }
    }

    private fun readLegacyCacheEntries(): List<CustomLyricsEntry> {
        val ids = session.sourceCacheLegacyIds().toMutableSet()
        session.sourceCacheRootFiles().forEach { legacyIdOf(it.name)?.let(ids::add) }
        return ids.flatMap { id ->
            legacyFilesFor(id).mapNotNull { file -> legacyEntry(id, file) }
        }
    }

    private fun legacyEntry(id: Long, file: File): CustomLyricsEntry? {
        val (source, ttml, name) = parseLegacyFile(file) ?: return null
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        return CustomLyricsEntry(id, CustomLyricsManifestPolicy.sanitizeDisplayName(name), file.name,
            bytes.size.toLong(), CustomLyricsFilePolicy.sha256(bytes), source, autoManaged = true)
    }

    /** Old automatic files with no recorded provider keep their own cache slot. */
    private fun parseLegacyFile(file: File): Triple<String, String, String>? = runCatching {
        val limit = if (file.name.endsWith(".json")) TtmlInputPolicy.MAX_TTML_BYTES * 6 + 2048
            else TtmlInputPolicy.MAX_TTML_BYTES
        val raw = file.inputStream().use { CustomLyricsFilePolicy.readBounded(it, limit) }.toString(Charsets.UTF_8)
        if (file.name.endsWith(".json")) {
            val root = JSONObject(raw)
            if (root.optInt("version") != 1) return@runCatching null
            val source = CustomLyricsManifestPolicy.canonicalSource(root.optString("source"))
                .takeIf(CustomLyricsManifestPolicy::isThirdPartySource) ?: CustomLyricsSources.AUTO_CACHE
            val ttml = root.optString("ttml")
            if (!TtmlInputPolicy.isAcceptable(ttml)) return@runCatching null
            Triple(source, ttml, root.optString("displayName"))
        } else {
            if (!TtmlInputPolicy.isAcceptable(raw)) return@runCatching null
            Triple(CustomLyricsSources.AUTO_CACHE, raw, "")
        }
    }.getOrNull()

    private fun legacyFilesFor(appleMusicId: Long): List<File> = listOfNotNull(
        session.sourceCacheLegacyCandidateFile(appleMusicId),
        session.sourceCacheLegacyTtmlFile(appleMusicId),
    ).filter(File::isFile)

    private fun legacyIdOf(name: String): Long? {
        if (!name.startsWith("lyric_") || !name.endsWith(".json") && !name.endsWith(".ttml")) {
            return null
        }
        return name.substring(6, name.length - 5).toLongOrNull()?.takeIf { it > 0L }
    }

    private fun retireUnreferenced(fileIds: List<String>, next: CustomLyricsManifest): Boolean {
        val protected = synchronized(shared.stateLock) {
            shared.manualManifest.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
        }
        protected.apply {
            next.entries.mapTo(this, CustomLyricsEntry::fileId)
        }
        val previous = synchronized(shared.stateLock) { shared.pendingDeletes.toSet() }
        val failed = (fileIds + previous).distinct().filter { fileId ->
            isStorableFileId(fileId) && fileId !in protected &&
                !session.deleteSourceBodyFile(fileId) && fileBytesOnDisk(fileId) > 0L
        }.toSet()
        if (failed != previous) {
            synchronized(shared.stateLock) {
                shared.pendingDeletes.clear()
                shared.pendingDeletes.addAll(failed)
            }
            session.writeValues(mapOf(PENDING_DELETE_KEY to JSONArray(failed.toList()).toString()))
        }
        return failed.isEmpty()
    }

    private fun isManualFile(fileId: String): Boolean = synchronized(shared.stateLock) {
        shared.manualManifest.entries.any { it.fileId == fileId }
    }

    private fun isSourceCacheFile(name: String): Boolean =
        name == "index.json" || isLegacyFileId(name)

    private fun isLegacyFileId(fileId: String): Boolean = LEGACY_FILE_ID.matches(fileId)

    private fun fileBytesOnDisk(fileId: String): Long {
        if (isLegacyFileId(fileId)) {
            session.sourceCacheFile(fileId)?.takeIf(File::isFile)?.let {
                return runCatching { it.length() }.getOrDefault(0L)
            }
        }
        return session.fileLength(fileId) ?: 0L
    }

    private fun readPreferred(): Map<Long, String> = readSourceMetadata(PREFERRED_KEY) { root ->
        buildMap {
            root.keys().forEach { id ->
                id.toLongOrNull()?.takeIf { it > 0L }?.let { songId ->
                    root.optString(id).takeIf(CustomLyricsManifestPolicy::isUsableSourceId)
                        ?.let { put(songId, it) }
                }
            }
        }
    } ?: emptyMap()

    private fun readOffsets(): Map<Key, Int> = readSourceMetadata(OFFSETS_KEY) { root ->
        buildMap {
            root.keys().forEach { id ->
                val songId = id.toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val sources = root.optJSONObject(id) ?: return@forEach
                sources.keys().forEach { source ->
                    if (CustomLyricsManifestPolicy.isUsableSourceId(source)) {
                        put(Key(songId, source), sources.optInt(source, 0))
                    }
                }
            }
        }
    } ?: emptyMap()

    private fun readLastUsed(): Map<Key, Long> = readSourceMetadata(LAST_USED_KEY) { root ->
        buildMap {
            root.keys().forEach { id ->
                val songId = id.toLongOrNull()?.takeIf { it > 0L } ?: return@forEach
                val sources = root.optJSONObject(id) ?: return@forEach
                sources.keys().forEach { source ->
                    if (CustomLyricsManifestPolicy.isUsableSourceId(source)) {
                        put(Key(songId, source), sources.optLong(source, 0L))
                    }
                }
            }
        }
    } ?: emptyMap()

    private fun <T> readSourceMetadata(key: String, parse: (JSONObject) -> T): T? = runCatching {
        val raw = session.values()[key] as? String ?: return@runCatching null
        parse(JSONObject(raw))
    }.getOrNull()

    private fun persistPreferred(values: Map<Long, String>): Boolean {
        val root = JSONObject()
        values.forEach { (id, source) -> root.put(id.toString(), source) }
        return session.writeValues(mapOf(PREFERRED_KEY to root.toString()))
    }

    private fun persistOffsets(values: Map<Key, Int>): Boolean {
        val root = JSONObject()
        values.forEach { (key, offset) ->
            val song = root.optJSONObject(key.appleMusicId.toString()) ?: JSONObject().also {
                root.put(key.appleMusicId.toString(), it)
            }
            song.put(key.source, offset)
        }
        return session.writeValues(mapOf(OFFSETS_KEY to root.toString()))
    }

    private fun persistLastUsed(values: Map<Key, Long>): Boolean {
        val root = JSONObject()
        values.forEach { (key, used) ->
            val song = root.optJSONObject(key.appleMusicId.toString()) ?: JSONObject().also {
                root.put(key.appleMusicId.toString(), it)
            }
            song.put(key.source, used)
        }
        return session.writeValues(mapOf(LAST_USED_KEY to root.toString()))
    }

    private fun notifyListeners() {
        val callbacks = synchronized(shared.stateLock) { shared.listeners.toList() }
        callbacks.forEach { runCatching(it) }
    }

    private object SourceIndexCodec {
        private const val VERSION = 1

        fun encode(manifest: CustomLyricsManifest): String = JSONObject().apply {
            put("version", VERSION)
            put("entries", JSONArray().apply {
                sanitize(manifest).entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("appleMusicId", entry.appleMusicId)
                        put("displayName", entry.displayName)
                        put("fileId", entry.fileId)
                        put("sizeBytes", entry.sizeBytes)
                        put("sha256", entry.sha256)
                        put("source", entry.source)
                        put("enabled", entry.enabled)
                        put("autoManaged", entry.autoManaged)
                    })
                }
            })
        }.toString()

        fun decode(bytes: ByteArray): CustomLyricsManifest? = runCatching {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt("version") != VERSION) return@runCatching null
            val array = root.optJSONArray("entries") ?: return@runCatching null
            val entries = linkedMapOf<Key, CustomLyricsEntry>()
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index) ?: return@runCatching null
                val entry = parseEntry(raw) ?: continue
                entries.putIfAbsent(Key(entry.appleMusicId, entry.source), entry)
            }
            CustomLyricsManifest(entries.values.toList())
        }.getOrNull()

        fun sanitize(manifest: CustomLyricsManifest): CustomLyricsManifest {
            val entries = linkedMapOf<Key, CustomLyricsEntry>()
            manifest.entries.forEach { raw ->
                val entry = sanitizeEntry(raw) ?: return@forEach
                entries.putIfAbsent(Key(entry.appleMusicId, entry.source), entry)
            }
            return CustomLyricsManifest(entries.values.toList())
        }

        private fun parseEntry(raw: JSONObject): CustomLyricsEntry? {
            val id = raw.optLong("appleMusicId", 0L)
            val source = CustomLyricsManifestPolicy.canonicalSource(raw.optString("source"))
            val fileId = raw.optString("fileId")
            val size = raw.optLong("sizeBytes", 0L)
            val sha = raw.optString("sha256")
            if (id <= 0L || !CustomLyricsManifestPolicy.isThirdPartySource(source) ||
                !isStorableFileId(fileId) ||
                size !in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong() ||
                !CustomLyricsManifestPolicy.isValidSha256(sha)
            ) return null
            return CustomLyricsEntry(
                appleMusicId = id,
                displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(
                    raw.optString("displayName"),
                ),
                fileId = fileId,
                sizeBytes = size,
                sha256 = sha.lowercase(),
                source = source,
                enabled = raw.optBoolean("enabled", true),
                autoManaged = raw.optBoolean("autoManaged", true),
            )
        }

        private fun sanitizeEntry(raw: CustomLyricsEntry): CustomLyricsEntry? {
            val source = CustomLyricsManifestPolicy.canonicalSource(raw.source)
            if (raw.appleMusicId <= 0L ||
                !CustomLyricsManifestPolicy.isThirdPartySource(source) ||
                !isStorableFileId(raw.fileId) ||
                raw.sizeBytes !in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong() ||
                !CustomLyricsManifestPolicy.isValidSha256(raw.sha256)
            ) return null
            return raw.copy(
                displayName = CustomLyricsManifestPolicy.sanitizeDisplayName(raw.displayName),
                sha256 = raw.sha256.lowercase(),
                source = source,
            )
        }
    }

    private companion object {
        const val PREFERRED_KEY = "lyrics_source_preferred"
        const val OFFSETS_KEY = "lyrics_source_offsets"
        const val LAST_USED_KEY = "lyrics_source_last_used"
        const val PENDING_DELETE_KEY = "lyrics_source_pending_deletes"
        const val LEGACY_CACHE_CLEARED_KEY = "lyrics_source_legacy_cleared"
        const val LEGACY_NORMALIZED_KEY = "lyrics_source_legacy_normalized"

        fun isStorableFileId(fileId: String): Boolean =
            CustomLyricsManifestPolicy.isValidFileId(fileId) ||
                LEGACY_FILE_ID.matches(fileId)
        val LEGACY_FILE_ID = Regex("lyric_[1-9][0-9]*\\.(json|ttml)")
    }
}
