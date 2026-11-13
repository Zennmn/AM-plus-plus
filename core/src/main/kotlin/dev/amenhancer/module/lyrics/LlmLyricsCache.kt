package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import dev.amenhancer.module.model.LlmLyricsThinkingFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent LRU cache for generated lyrics, kept in its own directory and
 * deliberately independent from the user-managed custom-lyrics index: entries
 * are never published, listed or editable.
 *
 * One file per song holds independent translation and transliteration slots.
 * The original-document SHA and each slot's request identity must still match
 * before a slot is reused. The API key is never written, so rotating a key
 * keeps the cache while switching a provider, model, thinking mode or prompt
 * invalidates only the affected slot. All I/O is atomic and bounded; unreadable
 * entries are ignored instead of trusted, and a write adopts orphan files so
 * the directory cannot grow past [maxEntries] even when the index was lost.
 */
class FileLlmLyricsCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val lock = Any()
    private val capacity = maxEntries.coerceIn(1, MAX_ENTRIES)

    /** Returns one cached result slot for this exact identity, or null. */
    fun read(
        appleMusicId: Long,
        originalTtml: String,
        config: LlmLyricsConfig,
        kind: String = TRANSLATION_KIND,
    ): String? {
        val identity = identityOf(appleMusicId, originalTtml, config, kind) ?: return null
        return synchronized(lock) {
            val file = entryFile(appleMusicId) ?: return@synchronized null
            val entry = readEntry(file) ?: return@synchronized null
            if (!entry.originalSha256.equals(identity.originalSha256, ignoreCase = true)) {
                runCatching { file.delete() }
                reorder(appleMusicId, dropped = true)
                return@synchronized null
            }
            val slot = entry.slots[kind] ?: return@synchronized null
            if (!slot.matches(identity)) {
                removeSlot(file, appleMusicId, entry, kind)
                return@synchronized null
            }
            reorder(appleMusicId, dropped = false)
            slot.ttml
        }
    }

    /** Stores one result slot for this song, preserving its sibling slot. */
    fun write(
        appleMusicId: Long,
        originalTtml: String,
        config: LlmLyricsConfig,
        resultTtml: String,
        kind: String = TRANSLATION_KIND,
    ): Boolean {
        val identity = identityOf(appleMusicId, originalTtml, config, kind) ?: return false
        if (!isAcceptableTtml(resultTtml)) return false
        return synchronized(lock) {
            runCatching {
                if (!directory.exists() && !directory.mkdirs()) return@runCatching false
                val file = entryFile(appleMusicId) ?: return@runCatching false
                val previous = readEntry(file)
                val slots = LinkedHashMap<String, Slot>()
                if (previous != null &&
                    previous.originalSha256.equals(identity.originalSha256, ignoreCase = true)
                ) {
                    slots.putAll(previous.slots)
                }
                val current = slots[kind]
                if (current?.matches(identity) == true && current.ttml == resultTtml) {
                    reorder(appleMusicId, dropped = false)
                    return@runCatching true
                }
                slots[kind] = identity.toSlot(resultTtml)
                val bytes = encodeEntry(Entry(identity.originalSha256, slots))
                if (bytes.size.toLong() > MAX_ENTRY_BYTES) return@runCatching false
                val ordered = orderedIds().toMutableList()
                ordered.remove(appleMusicId)
                ordered.add(appleMusicId)
                val keep = ordered.takeLast(capacity)
                // Never grow past the bound when storage refuses an eviction.
                for (evicted in ordered.dropLast(keep.size)) {
                    val old = entryFile(evicted) ?: continue
                    if (old.exists() && !old.delete()) return@runCatching false
                }
                atomicWrite(file, bytes)
                writeIndex(keep)
                pruneUnexpected(keep.toSet())
                true
            }.getOrDefault(false)
        }
    }

    /** Entry identity: everything the API key is deliberately absent from. */
    private fun identityOf(
        appleMusicId: Long,
        originalTtml: String,
        config: LlmLyricsConfig,
        kind: String,
    ): EntryIdentity? {
        if (appleMusicId <= 0L || originalTtml.isEmpty()) return null
        val originalBytes = originalTtml.toByteArray(Charsets.UTF_8)
        if (originalBytes.size.toLong() > MAX_SOURCE_TTML_BYTES) return null
        val endpoint = config.endpoint ?: return null
        val model = config.normalizedModel
        if (model.isEmpty()) return null
        val (targetLanguage, promptVersion) = when (kind) {
            TRANSLATION_KIND -> LlmLyricsClient.TARGET_LANGUAGE to LlmLyricsClient.PROMPT_VERSION
            TRANSLITERATION_KIND ->
                TRANSLITERATION_TARGET_LANGUAGE to LlmLyricsClient.TRANSLITERATION_PROMPT_VERSION
            else -> return null
        }
        return EntryIdentity(
            targetLanguage = targetLanguage,
            endpoint = endpoint,
            model = model,
            thinkingFormat = config.thinkingFormat,
            thinkingEnabled = config.effectiveThinkingEnabled,
            thinkingEffort = config.effectiveThinkingEffort,
            promptVersion = promptVersion,
            originalSha256 = CustomLyricsFilePolicy.sha256(originalBytes),
        )
    }

    private fun encodeEntry(entry: Entry): ByteArray {
        val slots = JSONObject()
        SLOT_KINDS.forEach { kind ->
            entry.slots[kind]?.let { slot -> slots.put(kind, encodeSlot(slot)) }
        }
        return JSONObject()
            .put("version", ENTRY_VERSION)
            .put("originalSha256", entry.originalSha256)
            .put("slots", slots)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun encodeSlot(slot: Slot): JSONObject = JSONObject()
        .put("targetLanguage", slot.targetLanguage)
        .put("endpoint", slot.endpoint)
        .put("model", slot.model)
        .put("thinkingFormat", slot.thinkingFormat.storageValue)
        .put("thinkingEnabled", slot.thinkingEnabled)
        .put("thinkingEffort", slot.thinkingEffort.storageValue)
        .put("promptVersion", slot.promptVersion)
        .put("ttml", slot.ttml)

    private fun readEntry(file: File): Entry? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_ENTRY_BYTES) return@runCatching null
        val raw = JSONObject(file.readText(Charsets.UTF_8))
        when (raw.optInt("version", 0)) {
            LEGACY_ENTRY_VERSION,
            LEGACY_EFFORT_ENTRY_VERSION,
            LEGACY_THINKING_FORMAT_ENTRY_VERSION
            -> readLegacyEntry(raw, raw.optInt("version", 0))
            ENTRY_VERSION -> readCurrentEntry(raw)
            else -> null
        }
    }.getOrNull()

    /** Versions 1-3 stored a single translation at the top level. */
    private fun readLegacyEntry(raw: JSONObject, version: Int): Entry? {
        val (thinkingFormat, thinkingEffort) = when (version) {
            LEGACY_ENTRY_VERSION ->
                LlmLyricsThinkingFormat.THINKING_TYPE to LlmLyricsThinkingEffort.DEFAULT
            LEGACY_EFFORT_ENTRY_VERSION ->
                LlmLyricsThinkingFormat.THINKING_TYPE to (decodeEffort(raw) ?: return null)
            LEGACY_THINKING_FORMAT_ENTRY_VERSION ->
                (decodeFormat(raw) ?: return null) to (decodeEffort(raw) ?: return null)
            else -> return null
        }
        val ttml = raw.optString("ttml")
        if (!isAcceptableTtml(ttml)) return null
        val originalSha256 = raw.optString("originalSha256")
        if (originalSha256.length != SHA256_LENGTH) return null
        val slot = Slot(
            targetLanguage = raw.optString("targetLanguage"),
            endpoint = raw.optString("endpoint"),
            model = raw.optString("model"),
            thinkingFormat = thinkingFormat,
            // Keep entries without this field eligible only for the default mode.
            thinkingEnabled = raw.optBoolean("thinkingEnabled", false),
            thinkingEffort = thinkingEffort,
            promptVersion = raw.optInt("promptVersion", 0),
            ttml = ttml,
        )
        return Entry(originalSha256, linkedMapOf(TRANSLATION_KIND to slot))
    }

    private fun readCurrentEntry(raw: JSONObject): Entry? {
        val originalSha256 = raw.optString("originalSha256")
        if (originalSha256.length != SHA256_LENGTH) return null
        val encodedSlots = raw.optJSONObject("slots") ?: return null
        val slots = LinkedHashMap<String, Slot>()
        SLOT_KINDS.forEach { kind ->
            encodedSlots.optJSONObject(kind)?.let { encoded ->
                readSlot(encoded)?.let { slot -> slots[kind] = slot }
            }
        }
        return Entry(originalSha256, slots)
    }

    private fun readSlot(raw: JSONObject): Slot? {
        val ttml = raw.optString("ttml")
        if (!isAcceptableTtml(ttml)) return null
        val thinkingFormat = decodeFormat(raw) ?: return null
        val thinkingEffort = decodeEffort(raw) ?: return null
        return Slot(
            targetLanguage = raw.optString("targetLanguage"),
            endpoint = raw.optString("endpoint"),
            model = raw.optString("model"),
            thinkingFormat = thinkingFormat,
            thinkingEnabled = raw.optBoolean("thinkingEnabled", false),
            thinkingEffort = thinkingEffort,
            promptVersion = raw.optInt("promptVersion", 0),
            ttml = ttml,
        )
    }

    private fun decodeFormat(raw: JSONObject): LlmLyricsThinkingFormat? {
        val stored = raw.optString("thinkingFormat")
        return LlmLyricsThinkingFormat.values().firstOrNull { it.storageValue == stored }
    }

    private fun decodeEffort(raw: JSONObject): LlmLyricsThinkingEffort? {
        val stored = raw.optString("thinkingEffort")
        return LlmLyricsThinkingEffort.values().firstOrNull { it.storageValue == stored }
    }

    /** Drops only the requested stale slot; a valid sibling remains on disk. */
    private fun removeSlot(file: File, appleMusicId: Long, entry: Entry, kind: String) {
        val remaining = entry.slots - kind
        if (remaining.isEmpty()) {
            runCatching { file.delete() }
            reorder(appleMusicId, dropped = true)
            return
        }
        runCatching {
            val bytes = encodeEntry(entry.copy(slots = remaining))
            if (bytes.size.toLong() <= MAX_ENTRY_BYTES) atomicWrite(file, bytes)
        }
    }

    /** True LRU: a read hit moves the song to newest; removing its last slot drops it. */
    private fun reorder(appleMusicId: Long, dropped: Boolean) {
        runCatching {
            val before = orderedIds()
            val after = if (dropped) {
                before - appleMusicId
            } else {
                (before - appleMusicId) + appleMusicId
            }
            if (after == before && after.size <= capacity) return@runCatching
            val keep = after.takeLast(capacity)
            pruneUnexpected(keep.toSet())
            writeIndex(keep)
        }
    }

    /**
     * Newest-last identity order: the indexed order first, then any file the
     * index does not know about, oldest modification last so it is evicted
     * before a song that is actually in use.
     */
    private fun orderedIds(): List<Long> {
        val onDisk = entryFiles()
        if (onDisk.isEmpty()) return emptyList()
        val indexed = readIndex().filter { onDisk.containsKey(it) }
        if (indexed.size == onDisk.size) return indexed
        val known = indexed.toHashSet()
        val orphans = onDisk
            .filterKeys { it !in known }
            .entries
            .sortedBy { it.value.lastModified() }
            .map { it.key }
        return orphans + indexed
    }

    private fun entryFiles(): Map<Long, File> {
        val files = directory.listFiles() ?: return emptyMap()
        val entries = LinkedHashMap<Long, File>()
        files.forEach { file ->
            val id = idOf(file.name) ?: return@forEach
            if (file.isFile) entries[id] = file
        }
        return entries
    }

    private fun idOf(name: String): Long? {
        if (!name.startsWith(ENTRY_PREFIX) || !name.endsWith(ENTRY_SUFFIX)) return null
        val digits = name.substring(ENTRY_PREFIX.length, name.length - ENTRY_SUFFIX.length)
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        return digits.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun entryFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { File(directory, "$ENTRY_PREFIX$it$ENTRY_SUFFIX") }

    /** Drops anything outside the retained set so orphan files cannot accumulate. */
    private fun pruneUnexpected(keep: Set<Long>) {
        entryFiles().keys.forEach { id ->
            if (id !in keep) entryFile(id)?.delete()
        }
    }

    private fun readIndex(): List<Long> = runCatching {
        if (!indexFile.isFile || indexFile.length() !in 1L..MAX_INDEX_BYTES) {
            return@runCatching emptyList()
        }
        val array = JSONArray(indexFile.readText(Charsets.UTF_8))
        val ids = mutableListOf<Long>()
        for (index in 0 until array.length()) {
            val id = when (val value = array.opt(index)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: -1L
                else -> -1L
            }
            if (id > 0L && id !in ids) ids.add(id)
        }
        ids
    }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<Long>) {
        val capped = ids.takeLast(capacity)
        val bytes = JSONArray().apply { capped.forEach(::put) }
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_INDEX_BYTES) return
        atomicWrite(indexFile, bytes)
    }

    /** Applies the same bound used before any generated TTML reaches the native parser. */
    private fun isAcceptableTtml(ttml: String): Boolean =
        ttml.isNotBlank() &&
            ttml.toByteArray(Charsets.UTF_8).size <= TtmlInputPolicy.MAX_TTML_BYTES &&
            TtmlInputPolicy.isAcceptable(ttml)

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val pending = File.createTempFile(PENDING_PREFIX, PENDING_SUFFIX, directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            pending.delete()
        }
    }

    private data class Entry(
        val originalSha256: String,
        val slots: Map<String, Slot>,
    )

    private data class Slot(
        val targetLanguage: String,
        val endpoint: String,
        val model: String,
        val thinkingFormat: LlmLyricsThinkingFormat,
        val thinkingEnabled: Boolean,
        val thinkingEffort: LlmLyricsThinkingEffort,
        val promptVersion: Int,
        val ttml: String,
    ) {
        fun matches(identity: EntryIdentity): Boolean =
            targetLanguage == identity.targetLanguage &&
                endpoint == identity.endpoint &&
                model == identity.model &&
                thinkingFormat == identity.thinkingFormat &&
                thinkingEnabled == identity.thinkingEnabled &&
                thinkingEffort == identity.thinkingEffort &&
                promptVersion == identity.promptVersion
    }

    /** Identity a slot must still match before it can be reused. */
    private data class EntryIdentity(
        val targetLanguage: String,
        val endpoint: String,
        val model: String,
        val thinkingFormat: LlmLyricsThinkingFormat,
        val thinkingEnabled: Boolean,
        val thinkingEffort: LlmLyricsThinkingEffort,
        val promptVersion: Int,
        val originalSha256: String,
    ) {
        fun toSlot(ttml: String): Slot = Slot(
            targetLanguage = targetLanguage,
            endpoint = endpoint,
            model = model,
            thinkingFormat = thinkingFormat,
            thinkingEnabled = thinkingEnabled,
            thinkingEffort = thinkingEffort,
            promptVersion = promptVersion,
            ttml = ttml,
        )
    }

    private companion object {
        const val ENTRY_PREFIX = "llm_"
        const val ENTRY_SUFFIX = ".json"
        const val INDEX_FILE_NAME = "index.json"
        const val TRANSLATION_KIND = "translation"
        const val TRANSLITERATION_KIND = "transliteration"
        const val TRANSLITERATION_TARGET_LANGUAGE = "Latn"
        val SLOT_KINDS = listOf(TRANSLATION_KIND, TRANSLITERATION_KIND)
        // Old clients must reject the two-slot envelope.
        const val ENTRY_VERSION = 4
        const val LEGACY_ENTRY_VERSION = 1
        const val LEGACY_EFFORT_ENTRY_VERSION = 2
        const val LEGACY_THINKING_FORMAT_ENTRY_VERSION = 3
        const val MAX_ENTRIES = 200
        const val SHA256_LENGTH = 64
        const val MAX_INDEX_BYTES = 8 * 1024L
        const val MAX_SOURCE_TTML_BYTES = 4 * 1024 * 1024L
        const val MAX_ENTRY_BYTES = TtmlInputPolicy.MAX_TTML_BYTES * 12L + 4096L
        const val PENDING_PREFIX = "pending_"
        const val PENDING_SUFFIX = ".tmp"
    }
}
