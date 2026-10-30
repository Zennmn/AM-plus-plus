package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent LRU cache for LLM-translated lyrics, kept in its own directory and
 * deliberately independent from the user-managed custom-lyrics index: entries
 * are never published, listed or editable.
 *
 * One file per song holds exactly one translated document. An entry is reused
 * only while the song identity, the original lyrics, the normalized endpoint,
 * the model and the prompt version still match, and the API key is never
 * written, so rotating a key keeps the cache while switching provider or model
 * drops it. All I/O is atomic and bounded; unreadable entries are ignored
 * instead of trusted, and a write adopts orphan files so the directory cannot
 * grow past [maxEntries] even when the index was lost.
 */
class FileLlmLyricsCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val indexFile = File(directory, INDEX_FILE_NAME)
    private val lock = Any()
    private val capacity = maxEntries.coerceIn(1, MAX_ENTRIES)

    /** Returns the cached translation for this exact identity, or null. */
    fun read(appleMusicId: Long, originalTtml: String, config: LlmLyricsConfig): String? {
        val identity = identityOf(appleMusicId, originalTtml, config) ?: return null
        return synchronized(lock) {
            val file = entryFile(appleMusicId) ?: return@synchronized null
            val entry = readEntry(file) ?: return@synchronized null
            if (!entry.matches(identity)) {
                runCatching { file.delete() }
                reorder(appleMusicId, dropped = true)
                return@synchronized null
            }
            reorder(appleMusicId, dropped = false)
            entry.ttml
        }
    }

    /** Stores one translated document for this song, evicting the oldest entry. */
    fun write(
        appleMusicId: Long,
        originalTtml: String,
        config: LlmLyricsConfig,
        translatedTtml: String,
    ): Boolean {
        val identity = identityOf(appleMusicId, originalTtml, config) ?: return false
        if (!isAcceptableTranslation(translatedTtml)) return false
        val bytes = runCatching { encodeEntry(identity, translatedTtml) }.getOrNull() ?: return false
        if (bytes.size.toLong() > MAX_ENTRY_BYTES) return false
        return synchronized(lock) {
            runCatching {
                if (!directory.exists() && !directory.mkdirs()) return@runCatching false
                val file = entryFile(appleMusicId) ?: return@runCatching false
                val previous = readEntry(file)
                if (previous?.matches(identity) == true && previous.ttml == translatedTtml) {
                    reorder(appleMusicId, dropped = false)
                    return@runCatching true
                }
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
    ): EntryIdentity? {
        if (appleMusicId <= 0L || originalTtml.isEmpty()) return null
        val originalBytes = originalTtml.toByteArray(Charsets.UTF_8)
        if (originalBytes.size.toLong() > MAX_SOURCE_TTML_BYTES) return null
        val endpoint = config.endpoint ?: return null
        val model = config.normalizedModel
        if (model.isEmpty()) return null
        return EntryIdentity(
            targetLanguage = LlmLyricsClient.TARGET_LANGUAGE,
            endpoint = endpoint,
            model = model,
            thinkingEnabled = config.thinkingEnabled,
            thinkingEffort = config.effectiveThinkingEffort,
            promptVersion = LlmLyricsClient.PROMPT_VERSION,
            originalSha256 = CustomLyricsFilePolicy.sha256(originalBytes),
        )
    }

    private fun encodeEntry(identity: EntryIdentity, translatedTtml: String): ByteArray = JSONObject()
        .put("version", ENTRY_VERSION)
        .put("targetLanguage", identity.targetLanguage)
        .put("endpoint", identity.endpoint)
        .put("model", identity.model)
        .put("thinkingEnabled", identity.thinkingEnabled)
        .put("thinkingEffort", identity.thinkingEffort.storageValue)
        .put("promptVersion", identity.promptVersion)
        .put("originalSha256", identity.originalSha256)
        .put("ttml", translatedTtml)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun readEntry(file: File): Entry? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_ENTRY_BYTES) return@runCatching null
        val raw = JSONObject(file.readText(Charsets.UTF_8))
        val thinkingEffort = when (raw.optInt("version", 0)) {
            LEGACY_ENTRY_VERSION -> LlmLyricsThinkingEffort.DEFAULT
            ENTRY_VERSION -> {
                val stored = raw.optString("thinkingEffort")
                LlmLyricsThinkingEffort.values().firstOrNull { it.storageValue == stored }
                    ?: return@runCatching null
            }
            else -> return@runCatching null
        }
        val ttml = raw.optString("ttml")
        if (!isAcceptableTranslation(ttml)) return@runCatching null
        val originalSha256 = raw.optString("originalSha256")
        if (originalSha256.length != SHA256_LENGTH) return@runCatching null
        Entry(
            targetLanguage = raw.optString("targetLanguage"),
            endpoint = raw.optString("endpoint"),
            model = raw.optString("model"),
            // Keep entries without this field eligible only for the default mode.
            thinkingEnabled = raw.optBoolean("thinkingEnabled", false),
            thinkingEffort = thinkingEffort,
            promptVersion = raw.optInt("promptVersion", 0),
            originalSha256 = originalSha256,
            ttml = ttml,
        )
    }.getOrNull()

    /** True LRU: a read hit moves the song to the newest slot, a stale hit drops it. */
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
    private fun isAcceptableTranslation(ttml: String): Boolean =
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
        val targetLanguage: String,
        val endpoint: String,
        val model: String,
        val thinkingEnabled: Boolean,
        val thinkingEffort: LlmLyricsThinkingEffort,
        val promptVersion: Int,
        val originalSha256: String,
        val ttml: String,
    ) {
        fun matches(identity: EntryIdentity): Boolean =
            targetLanguage == identity.targetLanguage &&
                endpoint == identity.endpoint &&
                model == identity.model &&
                thinkingEnabled == identity.thinkingEnabled &&
                thinkingEffort == identity.thinkingEffort &&
                promptVersion == identity.promptVersion &&
                originalSha256.equals(identity.originalSha256, ignoreCase = true)
    }

    private companion object {
        const val ENTRY_PREFIX = "llm_"
        const val ENTRY_SUFFIX = ".json"
        const val INDEX_FILE_NAME = "index.json"
        // Old clients must reject entries whose identity includes an explicit effort.
        const val ENTRY_VERSION = 2
        const val LEGACY_ENTRY_VERSION = 1
        const val MAX_ENTRIES = 100
        const val SHA256_LENGTH = 64
        const val MAX_INDEX_BYTES = 8 * 1024L
        const val MAX_SOURCE_TTML_BYTES = 4 * 1024 * 1024L
        const val MAX_ENTRY_BYTES = TtmlInputPolicy.MAX_TTML_BYTES * 6L + 4096L
        const val PENDING_PREFIX = "pending_"
        const val PENDING_SUFFIX = ".tmp"
    }
}

/** Identity an entry must still match before it can be reused. */
private data class EntryIdentity(
    val targetLanguage: String,
    val endpoint: String,
    val model: String,
    val thinkingEnabled: Boolean,
    val thinkingEffort: LlmLyricsThinkingEffort,
    val promptVersion: Int,
    val originalSha256: String,
)
