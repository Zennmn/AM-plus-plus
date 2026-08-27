package dev.amenhancer.module.lyrics

import android.os.ParcelFileDescriptor
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.config.CustomLyricsIndexCommitResult
import dev.amenhancer.module.config.CustomLyricsIndexRepository
import dev.amenhancer.module.config.CustomLyricsIndexState
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
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

/** Settings-process facade for atomic custom-lyrics file and index changes. */
internal class CustomLyricsManager(
    private val snapshot: XposedServiceSnapshot,
    private val configStore: ConfigStore,
) {
    private val indexRepository = CustomLyricsIndexRepository(
        newIndexFileId = ::newIndexFileId,
        openFile = { fileId ->
            snapshot.openRemoteFile(fileId)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        },
        writeRemoteFile = ::writeRemoteFile,
        publishPointer = { pointer ->
            isWritable() && configStore.publishIndexPointer(pointer, snapshot)
        },
        deleteRemoteFile = { fileId ->
            if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
        },
    )

    fun save(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsSaveResult.Failed("libxposed remote file 服务不可用")
        val state = configStore.indexState(snapshot)
        return CustomLyricsImportTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest -> commitIndex(state, manifest) },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
        ).upsert(state.manifest, draft, replacingAppleMusicId)
    }

    fun saveMany(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): CustomLyricsBatchSaveResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsBatchSaveResult.Failed("libxposed remote file 服务不可用")
        val state = configStore.indexState(snapshot)
        return CustomLyricsImportTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest -> commitIndex(state, manifest) },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
        ).upsertMany(state.manifest, draft, replacingAppleMusicIds)
    }

    fun setEnabled(appleMusicId: Long, enabled: Boolean): CustomLyricsMutationResult =
        setEnabled(listOf(appleMusicId), enabled)

    fun setEnabled(
        appleMusicIds: List<Long>,
        enabled: Boolean,
    ): CustomLyricsMutationResult = synchronized(mutationLock) {
        mutate { manifest ->
            val targetIds = appleMusicIds.toSet()
            if (targetIds.isEmpty() || targetIds.any { it <= 0L }) return@mutate null
            var found = 0
            val entries = manifest.entries.map { entry ->
                if (entry.appleMusicId in targetIds) {
                    found += 1
                    entry.copy(enabled = enabled)
                } else {
                    entry
                }
            }
            if (found != targetIds.size) null else CustomLyricsManifest(entries)
        }
    }

    fun delete(appleMusicId: Long): CustomLyricsMutationResult = delete(listOf(appleMusicId))

    fun delete(appleMusicIds: List<Long>): CustomLyricsMutationResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsMutationResult.Failed("libxposed remote file 服务不可用")
        val state = configStore.indexState(snapshot)
        val targetIds = appleMusicIds.toSet()
        if (targetIds.isEmpty() || targetIds.any { it <= 0L }) {
            return CustomLyricsMutationResult.Failed("歌词映射不存在")
        }
        val removed = state.manifest.entries.filter { it.appleMusicId in targetIds }
        if (removed.size != targetIds.size) {
            return CustomLyricsMutationResult.Failed("歌词映射不存在")
        }
        val next = CustomLyricsManifestPolicy.sanitize(
            CustomLyricsManifest(state.manifest.entries.filterNot { it.appleMusicId in targetIds }),
        )
        if (commitIndex(state, next)) {
            if (ModuleApplication.isCurrentSnapshot(snapshot)) {
                val nextFileIds = next.entries.mapTo(mutableSetOf(), CustomLyricsEntry::fileId)
                removed.map(CustomLyricsEntry::fileId)
                    .filterNot(nextFileIds::contains)
                    .distinct()
                    .forEach { fileId -> runCatching { snapshot.deleteRemoteFile(fileId) } }
            }
            return CustomLyricsMutationResult.Updated(next)
        }
        return CustomLyricsMutationResult.Failed("无法发布歌词索引")
    }

    /**
     * Streams a bounded ZIP backup (manifest.json plus one file per entry)
     * into [out]; each TTML body is read, validated, and written one at a
     * time. Consumes and closes [out].
     */
    fun backup(out: OutputStream): CustomLyricsBackupResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsBackupResult.Failed("libxposed remote file 服务不可用")
        val manifest = configStore.indexState(snapshot).manifest
        return when (val result = CustomLyricsBackupCodec.encode(manifest, ::readRemoteFile, out)) {
            is CustomLyricsBackupEncodeResult.Encoded ->
                CustomLyricsBackupResult.Done(result.entryCount)
            is CustomLyricsBackupEncodeResult.Failed ->
                CustomLyricsBackupResult.Failed(result.message)
        }
    }

    /**
     * Streams a bounded ZIP backup from [input] into a merge-restore. Under
     * [CustomLyricsRestorePolicy.OVERWRITE] backup entries overwrite same-ID
     * current entries; under [CustomLyricsRestorePolicy.KEEP_EXISTING]
     * same-ID conflicts keep the current entry. Current-only IDs are kept and
     * backup-only IDs are appended under either policy; every restored entry
     * gets a fresh remote fileId. TTML bodies are validated and written one
     * at a time, never all at once. Consumes and closes [input].
     */
    fun restore(
        input: InputStream,
        policy: CustomLyricsRestorePolicy = CustomLyricsRestorePolicy.OVERWRITE,
    ): CustomLyricsRestoreResult = synchronized(mutationLock) {
        if (!isWritable()) return CustomLyricsRestoreResult.Failed("libxposed remote file 服务不可用")
        val state = configStore.indexState(snapshot)
        return CustomLyricsRestoreTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest -> commitIndex(state, manifest, allowRecovery = true) },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
        ).merge(state.manifest, policy) { onFile -> CustomLyricsBackupCodec.decode(input, onFile) }
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
        val state = configStore.indexState(snapshot)
        val candidate = transform(state.manifest) ?: return CustomLyricsMutationResult.Failed("歌词映射不存在")
        val next = CustomLyricsManifestPolicy.sanitize(candidate)
        if (commitIndex(state, next)) return CustomLyricsMutationResult.Updated(next)
        return CustomLyricsMutationResult.Failed("无法发布歌词索引")
    }

    private fun commitIndex(
        state: CustomLyricsIndexState,
        manifest: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): Boolean = indexRepository.commit(
        state,
        manifest,
        allowRecovery,
    ) is CustomLyricsIndexCommitResult.Committed

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

    private fun newIndexFileId(): String = "index_" + UUID.randomUUID().toString().replace("-", "")

    private companion object {
        val mutationLock = Any()
    }
}
