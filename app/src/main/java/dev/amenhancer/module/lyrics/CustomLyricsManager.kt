package dev.amenhancer.module.lyrics

import android.os.ParcelFileDescriptor
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsManifest
import java.util.UUID

internal sealed interface CustomLyricsMutationResult {
    data class Updated(val manifest: CustomLyricsManifest) : CustomLyricsMutationResult
    data class Failed(val message: String) : CustomLyricsMutationResult
}

/** Settings-process facade for atomic custom-lyrics file and manifest changes. */
internal class CustomLyricsManager(
    private val snapshot: XposedServiceSnapshot,
    private val configStore: ConfigStore,
) {
    fun save(draft: CustomLyricsDraft): CustomLyricsSaveResult = synchronized(mutationLock) {
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
        ).upsert(oldManifest, draft)
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
