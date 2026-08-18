package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.InputStream
import android.os.ParcelFileDescriptor
import java.util.UUID
import java.security.MessageDigest

/** Read-only configuration surface consumed by target-process features. */
internal interface ConfigurationReader {
    fun values(): Map<String, *>
    fun openFile(name: String): InputStream?
    fun openFileDescriptor(name: String): ParcelFileDescriptor? = null
}

/** Host-private storage adapter used only by the embedded artifact. */
internal interface EmbeddedConfigurationStorage : ConfigurationReader {
    fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean
    fun writeFile(name: String, bytes: ByteArray): Boolean
    fun deleteFile(name: String): Boolean

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
    private val writable: Boolean = true,
) : ConfigurationReader {
    private val indexRepository = CustomLyricsIndexRepository(
        newIndexFileId = newIndexFileId,
        openFile = storage::openFile,
        writeRemoteFile = storage::writeFile,
        publishPointer = ::publishIndexPointer,
        deleteRemoteFile = { storage.deleteFile(it) },
    )

    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(storage.values())

    fun saveSettings(settings: ModuleSettings): Boolean = writable && storage.writeValues(
        ModuleSettingsSchema.encodeOrdinarySettings(settings),
        synchronous = true,
    )

    fun saveFontManifest(manifest: LyricsFontManifest): Boolean = writable && storage.writeValues(
        ModuleSettingsSchema.encodeFontManifest(manifest),
        synchronous = true,
    )

    fun writeFile(name: String, bytes: ByteArray): Boolean = writable && storage.writeFile(name, bytes)

    fun deleteFile(name: String): Boolean = writable && storage.deleteFile(name)

    fun commitCustomLyrics(
        manifest: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): CustomLyricsIndexCommitResult = if (!writable) {
        CustomLyricsIndexCommitResult.Failed("嵌入配置迁移未完成，当前仅可读")
    } else {
        synchronized(INDEX_MUTATION_LOCK) {
            indexRepository.commit(
                state = indexRepository.state(storage.values()),
                next = manifest,
                allowRecovery = allowRecovery,
            )
        }
    }

    internal fun <T> withCustomLyricsMutation(block: () -> T): T =
        synchronized(INDEX_MUTATION_LOCK, block)

    private fun publishIndexPointer(pointer: CustomLyricsIndexPointer): Boolean =
        storage.writeValues(
            ModuleSettingsSchema.encodeIndexPointer(pointer),
            synchronous = true,
        )

    override fun values(): Map<String, *> = storage.values()

    override fun openFile(name: String): InputStream? = storage.openFile(name)

    override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
        storage.openFileDescriptor(name)

    private companion object {
        val INDEX_MUTATION_LOCK = Any()
    }
}
