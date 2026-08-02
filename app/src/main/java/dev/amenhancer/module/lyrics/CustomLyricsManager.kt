package dev.amenhancer.module.lyrics

import android.os.ParcelFileDescriptor
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal sealed interface CustomLyricsMutationResult {
    data class Updated(val manifest: CustomLyricsManifest) : CustomLyricsMutationResult
    data class Failed(val message: String) : CustomLyricsMutationResult
}

internal sealed interface CustomLyricsBackupResult {
    data class Done(val entryCount: Int) : CustomLyricsBackupResult
    data class Failed(val message: String) : CustomLyricsBackupResult
}

/** Settings-process facade for atomic custom-lyrics file and manifest changes. */
internal class CustomLyricsManager(
    private val snapshot: XposedServiceSnapshot,
    private val configStore: ConfigStore,
) {
    fun save(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsSaveResult.Failed("libxposed remote file 服务不可用")
        val oldManifest = configStore.settings(snapshot).customLyricsManifest
        return CustomLyricsImportTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest ->
                isWritable() && configStore.saveCustomLyricsManifest(manifest, snapshot)
            },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
        ).upsert(oldManifest, draft, replacingAppleMusicId)
    }

    fun setEnabled(appleMusicId: Long, enabled: Boolean): CustomLyricsMutationResult = synchronized(mutationLock) {
        mutate { manifest ->
        var found = false
        val entries = manifest.entries.map { entry ->
            if (entry.appleMusicId == appleMusicId) {
                found = true
                entry.copy(enabled = enabled)
            } else {
                entry
            }
        }
            if (!found) null else CustomLyricsManifest(entries)
        }
    }

    fun delete(appleMusicId: Long): CustomLyricsMutationResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsMutationResult.Failed("libxposed remote file 服务不可用")
        val oldManifest = configStore.settings(snapshot).customLyricsManifest
        val removed = oldManifest.entries.singleOrNull { it.appleMusicId == appleMusicId }
            ?: return CustomLyricsMutationResult.Failed("歌词映射不存在")
        val next = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(oldManifest.entries.filterNot { it.appleMusicId == appleMusicId }),
        )
        if (!configStore.saveCustomLyricsManifest(next, snapshot)) {
            return CustomLyricsMutationResult.Failed("无法发布歌词映射")
        }
        if (ModuleApplication.isCurrentSnapshot(snapshot)) {
            runCatching { snapshot.deleteRemoteFile(removed.fileId) }
        }
        return CustomLyricsMutationResult.Updated(next)
    }

    /**
     * Writes a bounded ZIP backup (manifest.json plus one file per entry) into
     * [out]; every TTML body is validated by [CustomLyricsFileReader] first.
     * Consumes and closes [out].
     */
    fun backup(out: OutputStream): CustomLyricsBackupResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsBackupResult.Failed("libxposed remote file 服务不可用")
        val manifest = configStore.settings(snapshot).customLyricsManifest
        return when (val result = CustomLyricsBackupCodec.encode(manifest, ::readRemoteFile, out)) {
            is CustomLyricsBackupEncodeResult.Encoded ->
                CustomLyricsBackupResult.Done(result.entryCount)
            is CustomLyricsBackupEncodeResult.Failed ->
                CustomLyricsBackupResult.Failed(result.message)
        }
    }

    /**
     * Decodes a bounded ZIP backup from [input] and merge-restores it: backup
     * entries overwrite same-ID current entries, current-only IDs are kept,
     * every restored entry gets a fresh remote fileId. Consumes and closes
     * [input].
     */
    fun restore(input: InputStream): CustomLyricsRestoreResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsRestoreResult.Failed("libxposed remote file 服务不可用")
        val oldManifest = configStore.settings(snapshot).customLyricsManifest
        val decoded = when (val result = CustomLyricsBackupCodec.decode(input)) {
            is CustomLyricsBackupDecodeResult.Rejected ->
                return CustomLyricsRestoreResult.Failed(result.message)
            is CustomLyricsBackupDecodeResult.Decoded -> result.backup
        }
        return CustomLyricsRestoreTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest ->
                isWritable() && configStore.saveCustomLyricsManifest(manifest, snapshot)
            },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
        ).merge(oldManifest, decoded)
    }

    private fun readRemoteFile(fileId: String): ByteArray? {
        if (!isWritable()) return null
        val descriptor = snapshot.openRemoteFile(fileId) ?: return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                CustomLyricsFilePolicy.readBounded(input)
            }
        }.getOrNull()
    }

    private fun mutate(
        transform: (CustomLyricsManifest) -> CustomLyricsManifest?,
    ): CustomLyricsMutationResult {
        if (!isWritable()) return CustomLyricsMutationResult.Failed("libxposed remote file 服务不可用")
        val oldManifest = configStore.settings(snapshot).customLyricsManifest
        val candidate = transform(oldManifest) ?: return CustomLyricsMutationResult.Failed("歌词映射不存在")
        val next = CustomLyricsManifestPolicy.sanitize(candidate)
        if (!configStore.saveCustomLyricsManifest(next, snapshot)) {
            return CustomLyricsMutationResult.Failed("无法发布歌词映射")
        }
        return CustomLyricsMutationResult.Updated(next)
    }

    private fun isWritable(): Boolean =
        snapshot.isRemoteFileAvailable && ModuleApplication.isCurrentSnapshot(snapshot)

    private fun writeRemoteFile(fileId: String, bytes: ByteArray): Boolean {
        if (!isWritable()) return false
        val descriptor = snapshot.openRemoteFile(fileId) ?: return false
        return runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
            }
            true
        }.getOrDefault(false)
    }

    private fun newFileId(): String = "lyrics_" + UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val mutationLock = Any()
    }
}
