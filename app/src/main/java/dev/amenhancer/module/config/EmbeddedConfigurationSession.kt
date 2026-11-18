package dev.amenhancer.module.config

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import java.io.InputStream
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Host-private storage adapter used only by the embedded artifact. */
internal interface EmbeddedConfigurationStorage : ConfigurationReader {
    fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean
    fun removeValues(keys: Set<String>, synchronous: Boolean = true): Boolean = true
    fun writeFile(name: String, bytes: ByteArray): Boolean
    fun deleteFile(name: String): Boolean

    /** Metadata-only size lookup; never streams the payload. */
    fun fileSizeBytes(name: String): Long? = null

    /** Copies and verifies a payload without requiring host implementations to buffer it. */
    fun copyFile(
        name: String,
        input: InputStream,
        expectedSizeBytes: Long,
        expectedSha256: String,
    ): Boolean = runCatching {
        val bytes = input.use(InputStream::readBytes)
        bytes.size.toLong() == expectedSizeBytes &&
            sha256(bytes).equals(expectedSha256, ignoreCase = true) &&
            writeFile(name, bytes)
    }.getOrDefault(false)

    /** Compares an existing payload without exposing its bytes to callers. */
    fun fileMatches(
        name: String,
        expectedSizeBytes: Long,
        expectedSha256: String,
    ): Boolean = runCatching {
        val bytes = openFile(name)?.use(InputStream::readBytes) ?: return@runCatching false
        bytes.size.toLong() == expectedSizeBytes &&
            sha256(bytes).equals(expectedSha256, ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Returns whether this storage already contains a file payload.
     *
     * The migration gate must treat file-only state as initialized too.  The
     * default keeps lightweight test/dynamic adapters source-compatible; the
     * host-private implementation overrides it with a directory scan.
     */
    fun hasAnyFiles(): Boolean = false

    /** Legacy automatic-cache directory, retained for reference-only migration. */
    fun sourceCacheDirectory(): File? = null

    /** Generated third-party cache files, including any left by an interrupted write. */
    fun sourceFileIds(): List<String> = emptyList()

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

/**
 * Owns the embedded configuration contract while hiding the host storage
 * implementation from settings workflows and target hooks.
 */
internal class EmbeddedConfigurationSession(
    private val storage: EmbeddedConfigurationStorage,
    newIndexFileId: () -> String = {
        "index_" + UUID.randomUUID().toString().replace("-", "")
    },
    internal val writable: Boolean = true,
) : ConfigurationReader {
    init {
        if (writable) migrateLegacyValuesBeforeCleanup()
    }
    private val indexRepository = CustomLyricsIndexRepository(
        newIndexFileId = newIndexFileId,
        openFile = storage::openFile,
        writeRemoteFile = storage::writeFile,
        publishPointer = ::publishIndexPointer,
        deleteRemoteFile = { storage.deleteFile(it) },
    )
    private var manualChangeListener: ((CustomLyricsManifest) -> Unit)? = null

    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(storage.values())

    fun saveSettings(settings: ModuleSettings): Boolean = writable && storage.writeValues(
        ModuleSettingsSchema.encodeOrdinarySettings(settings),
        synchronous = true,
    )

    fun saveAutomaticLyricsEnabled(enabled: Boolean): Boolean =
        writeValues(ModuleSettingsSchema.encodeAutomaticLyricsEnabled(enabled))

    fun saveAutomaticLyricsSourceOrder(order: List<String>): Boolean {
        if (!writable) return false
        return writeValues(legacySettingsMigrationValues(storage.values()) +
            ModuleSettingsSchema.encodeAutomaticLyricsSourceOrder(order))
    }

    fun saveFontManifest(manifest: LyricsFontManifest): Boolean = writable && storage.writeValues(
        ModuleSettingsSchema.encodeFontManifest(manifest),
        synchronous = true,
    )

    fun writeFile(name: String, bytes: ByteArray): Boolean = writable && storage.writeFile(name, bytes)

    fun deleteFile(name: String): Boolean = writable && storage.deleteFile(name)

    internal fun deleteSourceBodyFile(name: String): Boolean {
        if (!writable) return false
        return if (isLegacySourceFileName(name)) {
            sourceCacheFile(name)?.let { file ->
                runCatching { file.isFile && file.delete() }.getOrDefault(false)
            } ?: false
        } else storage.deleteFile(name)
    }

    fun commitCustomLyrics(
        manifest: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): CustomLyricsIndexCommitResult = if (!writable) {
        CustomLyricsIndexCommitResult.Failed(ModuleText.MIGRATION_READ_ONLY.text())
    } else {
        synchronized(INDEX_MUTATION_LOCK) {
            val state = indexRepository.state(storage.values())
            val maxBytes = manualIndexCommitLimit(state, manifest)
            if (maxBytes == null) {
                return@synchronized CustomLyricsIndexCommitResult.Failed(
                    ModuleText.LYRICS_INDEX_TOO_LARGE.text(),
                )
            }
            indexRepository.commit(
                state = state,
                next = manifest,
                allowRecovery = allowRecovery,
                maxIndexBytes = maxBytes,
            ).also { result ->
                if (result is CustomLyricsIndexCommitResult.Committed) notifyManualChangeListeners(result.manifest)
            }
        }
    }

    internal fun manualIndexCommitLimit(
        state: CustomLyricsIndexState,
        next: CustomLyricsManifest,
    ): Int? {
        val oldSize = state.pointer?.sizeBytes ?: runCatching {
            values()[LEGACY_MANUAL_MANIFEST_KEY]?.toString()
                ?.toByteArray(Charsets.UTF_8)?.size?.toLong()
        }.getOrNull() ?: 0L
        val newSize = CustomLyricsManifestCodec.encode(next)
            .toByteArray(Charsets.UTF_8).size.toLong()
        val limit = CustomLyricsManifestPolicy.MAX_CURRENT_INDEX_BYTES.toLong()
        if (oldSize > limit) {
            if (newSize > oldSize) return null
            return oldSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return limit.toInt()
    }

    /** Captures the currently published lyrics index for a long-running operation. */
    internal fun customLyricsIndexState(): CustomLyricsIndexState =
        indexRepository.state(storage.values())

    internal fun <T> withCustomLyricsMutation(block: () -> T): T =
        synchronized(INDEX_MUTATION_LOCK, block)

    internal fun observeManualChanges(listener: (CustomLyricsManifest) -> Unit) {
        manualChangeListener = listener
    }

    private fun notifyManualChangeListeners(manifest: CustomLyricsManifest) {
        manualChangeListener?.let { runCatching { it(manifest) } }
    }


    internal fun readFileBytes(name: String, maxBytes: Int = TtmlInputPolicy.MAX_TTML_BYTES): ByteArray? = runCatching {
        val legacy = if (isLegacySourceFileName(name)) {
            sourceCacheFile(name)?.takeIf(File::isFile)?.inputStream()
        } else null
        (legacy ?: openFile(name))?.use { input ->
            CustomLyricsFilePolicy.readBounded(input, maxBytes)
        }
    }.getOrNull()

    internal fun fileLength(name: String): Long? = if (isLegacySourceFileName(name)) {
        sourceCacheFile(name)?.takeIf(File::isFile)?.length()
    } else storage.fileSizeBytes(name)

    private fun isLegacySourceFileName(name: String): Boolean =
        name.startsWith("lyric_") && (name.endsWith(".json") || name.endsWith(".ttml"))

    internal fun readManualIndex(): CustomLyricsIndexState = synchronized(INDEX_MUTATION_LOCK) {
        indexRepository.state(storage.values())
    }

    internal fun readSourceIndex(
        pointerDecoder: (Map<String, *>) -> CustomLyricsIndexPointer?,
        pointerPresent: (Map<String, *>) -> Boolean,
        legacyDecoder: (Map<String, *>) -> CustomLyricsManifest,
        decodeIndex: (ByteArray) -> CustomLyricsManifest?,
        openFile: (String) -> InputStream? = storage::openFile,
        readIndexBytes: ((String, Int) -> ByteArray?)? = null,
    ): CustomLyricsIndexState = synchronized(INDEX_MUTATION_LOCK) {
        CustomLyricsIndexRepository.stateWithCodec(
            preferences = storage.values(),
            openFile = openFile,
            pointerDecoder = pointerDecoder,
            pointerPresent = pointerPresent,
            legacyDecoder = legacyDecoder,
            decodeIndex = decodeIndex,
            readIndexBytes = readIndexBytes ?: { fileId, maxBytes -> readFileBytes(fileId, maxBytes) },
        )
    }

    internal fun commitManualIndex(
        state: CustomLyricsIndexState,
        manifest: CustomLyricsManifest,
        allowRecovery: Boolean = false,
        maxIndexBytes: Int = CustomLyricsManifestPolicy.MAX_CURRENT_INDEX_BYTES,
    ): CustomLyricsIndexCommitResult = if (!writable) {
        CustomLyricsIndexCommitResult.Failed(ModuleText.MIGRATION_READ_ONLY.text())
    } else {
        synchronized(INDEX_MUTATION_LOCK) {
            val current = indexRepository.state(storage.values())
            if (current != state) {
                return@synchronized CustomLyricsIndexCommitResult.Failed(
                    ModuleText.LYRICS_INDEX_CHANGED.text(),
                )
            }
            indexRepository.commit(
                state = current,
                next = manifest,
                allowRecovery = allowRecovery,
                maxIndexBytes = maxIndexBytes,
            ).also { result ->
                if (result is CustomLyricsIndexCommitResult.Committed) {
                    notifyManualChangeListeners(result.manifest)
                }
            }
        }
    }

    internal fun commitSourceIndex(
        state: CustomLyricsIndexState,
        manifest: CustomLyricsManifest,
        newFileId: () -> String,
        publishPointer: (CustomLyricsIndexPointer) -> Boolean,
        maxIndexBytes: Int = CustomLyricsManifestPolicy.MAX_CURRENT_INDEX_BYTES,
        encode: (CustomLyricsManifest) -> String = CustomLyricsManifestCodec::encode,
        decode: (ByteArray) -> CustomLyricsManifest? = { bytes ->
            CustomLyricsManifestCodec.decodeIndexFile(bytes.toString(Charsets.UTF_8))
        },
        writeFile: (String, ByteArray) -> Boolean = storage::writeFile,
        deleteFile: (String) -> Boolean = { storage.deleteFile(it) },
        openFile: (String) -> InputStream? = storage::openFile,
        readIndexBytes: (String, Int) -> ByteArray? = { fileId, limit ->
            runCatching {
                openFile(fileId)?.use { input ->
                    CustomLyricsFilePolicy.readBounded(input, limit)
                }
            }.getOrNull()
        },
    ): CustomLyricsIndexCommitResult = if (!writable) {
        CustomLyricsIndexCommitResult.Failed(ModuleText.MIGRATION_READ_ONLY.text())
    } else {
        synchronized(INDEX_MUTATION_LOCK) {
            val repository = CustomLyricsIndexRepository(
                newIndexFileId = newFileId,
                openFile = openFile,
                writeRemoteFile = writeFile,
                publishPointer = publishPointer,
                deleteRemoteFile = { deleteFile(it) },
                maxIndexBytes = maxIndexBytes,
                readIndexBytes = readIndexBytes,
            )
            repository.commit(
                state = state,
                next = manifest,
                allowRecovery = false,
                maxIndexBytes = maxIndexBytes,
                encode = encode,
                decode = decode,
            )
        }
    }

    internal fun writeValues(values: Map<String, Any>): Boolean =
        writable && storage.writeValues(values, synchronous = true)

    internal fun removeValues(keys: Set<String>): Boolean =
        writable && storage.removeValues(keys, synchronous = true)

    internal fun sourceCacheDirectory(): File? = storage.sourceCacheDirectory()

    internal fun sourceFileIds(): List<String> = storage.sourceFileIds()

    /** File-level access to the legacy source-cache directory. */
    internal fun sourceCacheFile(name: String): File? {
        if (!SOURCE_CACHE_FILE_PATTERN.matches(name)) return null
        val root = sourceCacheDirectory() ?: return null
        return File(root, name)
    }

    private fun sourceCacheFileFromRoot(root: File, name: String): File? =
        if (SOURCE_CACHE_FILE_PATTERN.matches(name)) File(root, name) else null

    internal fun sourceCacheLunabeatCatalog(): File? = sourceCacheDirectory()?.let {
        sourceCacheFileFromRoot(File(it, "lunabeat"), "catalog.json")
    }

    /** Pre-runtime settings-page Lunabeat catalog; only this file is owned here. */
    internal fun legacyLunabeatCatalog(): File? = sourceCacheDirectory()?.parentFile?.let {
        File(File(it, "ampp-lunabeat-cache"), "catalog.json")
    }

    internal fun sourceCacheRootFiles(): List<File> = runCatching {
        sourceCacheDirectory()?.listFiles()?.filter(File::isFile).orEmpty()
    }.getOrDefault(emptyList())

    internal fun sourceCacheLegacyCandidateFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { sourceCacheFile("lyric_$it.json") }

    internal fun sourceCacheLegacyTtmlFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { sourceCacheFile("lyric_$it.ttml") }

    internal fun sourceCacheLegacyIds(): List<Long> = runCatching {
        val file = sourceCacheFile("index.json")?.takeIf(File::isFile)
            ?: return@runCatching emptyList()
        val bytes = file.inputStream().use { CustomLyricsFilePolicy.readBounded(it, 16 * 1024) }
        val array = org.json.JSONArray(bytes.toString(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                array.optLong(index, -1L).takeIf { it > 0L }?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())

    internal fun sourceIndexPreferenceKeys(): Set<String> = setOf(
        SOURCE_INDEX_FILE_ID,
        SOURCE_INDEX_GENERATION,
        SOURCE_INDEX_SHA256,
        SOURCE_INDEX_SIZE_BYTES,
    )

    internal fun clearSourceIndexPointer(): Boolean = removeValues(sourceIndexPreferenceKeys())

    internal fun encodeSourceIndexPointer(pointer: CustomLyricsIndexPointer): Map<String, Any> =
        linkedMapOf(
            SOURCE_INDEX_FILE_ID to pointer.fileId,
            SOURCE_INDEX_GENERATION to pointer.generation,
            SOURCE_INDEX_SHA256 to pointer.sha256,
            SOURCE_INDEX_SIZE_BYTES to pointer.sizeBytes,
        )

    internal fun decodeSourceIndexPointer(values: Map<String, *>): CustomLyricsIndexPointer? {
        val fileId = values[SOURCE_INDEX_FILE_ID] as? String ?: return null
        val generation = (values[SOURCE_INDEX_GENERATION] as? Number)?.toLong() ?: return null
        val sha256 = values[SOURCE_INDEX_SHA256] as? String ?: return null
        val sizeBytes = (values[SOURCE_INDEX_SIZE_BYTES] as? Number)?.toLong() ?: return null
        if (generation < 1L || sizeBytes !in 1L..CustomLyricsManifestPolicy.MAX_INDEX_BYTES.toLong()) {
            return null
        }
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId) ||
            !CustomLyricsManifestPolicy.isValidSha256(sha256)
        ) return null
        return CustomLyricsIndexPointer(fileId, generation, sha256.lowercase(), sizeBytes)
    }

    internal fun hasSourceIndexPointerValues(values: Map<String, *>): Boolean =
        sourceIndexPreferenceKeys().any(values::containsKey)


    private fun publishIndexPointer(pointer: CustomLyricsIndexPointer): Boolean =
        storage.writeValues(
            ModuleSettingsSchema.encodeIndexPointer(pointer),
            synchronous = true,
        )

    override fun values(): Map<String, *> = storage.values()

    override fun openFile(name: String): InputStream? = storage.openFile(name)

    override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
        storage.openFileDescriptor(name)

    /**
     * Existing host-private stores may already be marked initialized, which
     * intentionally bypasses remote migration. Convert legacy values locally
     * before deleting old keys; if publication fails, leave them intact so the
     * next process can retry.
     */
    private fun migrateLegacyValuesBeforeCleanup() {
        val values = runCatching { storage.values() }.getOrNull() ?: return
        val migration = legacySettingsMigrationValues(values)
        if (migration.isNotEmpty() && !runCatching {
                storage.writeValues(migration, synchronous = true)
            }.getOrDefault(false)
        ) {
            return
        }
        storage.removeValues(ModuleSettingsSchema.obsoleteKeys, synchronous = true)
    }

    private fun legacySettingsMigrationValues(values: Map<String, *>): Map<String, Any> =
        ModuleSettingsSchema.legacyTitleCorrectionMigrationValues(values) +
            ModuleSettingsSchema.legacyLlmThinkingFormatMigrationValues(values) +
            ModuleSettingsSchema.legacyAutomaticLyricsSourceOrderMigrationValues(values)

    private companion object {
        val INDEX_MUTATION_LOCK = Any()
        const val SOURCE_INDEX_FILE_ID = "lyrics_source_index_file_id"
        const val SOURCE_INDEX_GENERATION = "lyrics_source_index_generation"
        const val SOURCE_INDEX_SHA256 = "lyrics_source_index_sha256"
        const val SOURCE_INDEX_SIZE_BYTES = "lyrics_source_index_size_bytes"
        const val LEGACY_MANUAL_MANIFEST_KEY = "custom_lyrics_manifest"
        val SOURCE_CACHE_FILE_PATTERN = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
