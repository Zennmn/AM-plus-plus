package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.InputStream
import android.os.ParcelFileDescriptor
import java.util.UUID

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
) : ConfigurationReader {
    private val indexRepository = CustomLyricsIndexRepository(
        newIndexFileId = newIndexFileId,
        openFile = storage::openFile,
        writeRemoteFile = storage::writeFile,
        publishPointer = ::publishIndexPointer,
        deleteRemoteFile = { storage.deleteFile(it) },
    )

    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(storage.values())

    fun saveSettings(settings: ModuleSettings): Boolean = storage.writeValues(
        ModuleSettingsSchema.encodeOrdinarySettings(settings),
        synchronous = true,
    )

    fun saveFontManifest(manifest: LyricsFontManifest): Boolean = storage.writeValues(
        ModuleSettingsSchema.encodeFontManifest(manifest),
        synchronous = true,
    )

    fun writeFile(name: String, bytes: ByteArray): Boolean = storage.writeFile(name, bytes)

    fun deleteFile(name: String): Boolean = storage.deleteFile(name)

    fun commitCustomLyrics(
        manifest: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): CustomLyricsIndexCommitResult = synchronized(INDEX_MUTATION_LOCK) {
        indexRepository.commit(
            state = indexRepository.state(storage.values()),
            next = manifest,
            allowRecovery = allowRecovery,
        )
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
