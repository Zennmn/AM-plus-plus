package dev.amenhancer.module.config

import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.InputStream

/** Current index state: the published pointer (may be stale) plus the resolved manifest. */
internal data class CustomLyricsIndexState(
    val pointer: CustomLyricsIndexPointer?,
    val manifest: CustomLyricsManifest,
    val canCommit: Boolean = true,
)

internal sealed interface CustomLyricsIndexCommitResult {
    data class Committed(
        val pointer: CustomLyricsIndexPointer,
        val manifest: CustomLyricsManifest,
    ) : CustomLyricsIndexCommitResult

    data class Failed(val message: String) : CustomLyricsIndexCommitResult
}

/**
 * Extensible remote-file index repository. The whole manifest lives in one
 * remote index file; remote preferences carry only a small pointer
 * (fileId/generation/sha256/sizeBytes). Every mutation commits atomically:
 * write the new index file, synchronously publish the pointer, then
 * best-effort delete the old index file. A failed pointer publication keeps
 * the old pointer and deletes the new file.
 */
internal class CustomLyricsIndexRepository(
    private val newIndexFileId: () -> String,
    private val openFile: (String) -> InputStream?,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishPointer: (CustomLyricsIndexPointer) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
    private val maxIndexBytes: Int = CustomLyricsManifestPolicy.MAX_INDEX_BYTES,
) {
    fun state(preferences: Map<String, *>): CustomLyricsIndexState =
        Companion.state(preferences, openFile)

    fun commit(
        state: CustomLyricsIndexState,
        next: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): CustomLyricsIndexCommitResult {
        if (!state.canCommit && !allowRecovery) {
            return CustomLyricsIndexCommitResult.Failed("歌词索引文件不可读，无法修改")
        }
        val encoded = CustomLyricsManifestCodec.encode(next).toByteArray(Charsets.UTF_8)
        if (encoded.size > maxIndexBytes) {
            return CustomLyricsIndexCommitResult.Failed("歌词索引超出大小上限")
        }
        val fileId = runCatching(newIndexFileId).getOrNull()
            ?: return CustomLyricsIndexCommitResult.Failed("无法生成歌词索引文件 ID")
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) {
            return CustomLyricsIndexCommitResult.Failed("生成的歌词索引文件 ID 无效")
        }
        if (!runCatching { writeRemoteFile(fileId, encoded) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsIndexCommitResult.Failed("无法写入共享歌词索引文件")
        }
        val pointer = CustomLyricsIndexPointer(
            fileId = fileId,
            generation = (state.pointer?.generation ?: 0L) + 1L,
            sha256 = CustomLyricsFilePolicy.sha256(encoded),
            sizeBytes = encoded.size.toLong(),
        )
        if (!runCatching { publishPointer(pointer) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsIndexCommitResult.Failed("无法发布歌词索引")
        }
        state.pointer?.fileId
            ?.takeIf { it != fileId }
            ?.let { oldFileId -> runCatching { deleteRemoteFile(oldFileId) } }
        return CustomLyricsIndexCommitResult.Committed(pointer, next)
    }

    companion object {
        /**
         * Resolves the current manifest: a verifiable published pointer wins;
         * otherwise the legacy v1 preference string is used; otherwise empty.
         */
        fun state(
            preferences: Map<String, *>,
            openFile: (String) -> InputStream?,
        ): CustomLyricsIndexState {
            val pointer = ModuleSettingsSchema.decodeIndexPointer(preferences)
            if (pointer != null) {
                val bytes = readIndexBytes(pointer.fileId, openFile)
                if (
                    bytes != null &&
                    bytes.size.toLong() == pointer.sizeBytes &&
                    CustomLyricsFilePolicy.sha256(bytes)
                        .equals(pointer.sha256, ignoreCase = true)
                ) {
                    val decoded = CustomLyricsManifestCodec.decodeIndexFile(
                        bytes.toString(Charsets.UTF_8),
                    )
                    if (decoded != null) return CustomLyricsIndexState(pointer, decoded)
                }
                return CustomLyricsIndexState(
                    pointer = pointer,
                    manifest = CustomLyricsManifest.empty(),
                    canCommit = false,
                )
            }
            if (ModuleSettingsSchema.hasIndexPointerValues(preferences)) {
                return CustomLyricsIndexState(
                    pointer = null,
                    manifest = CustomLyricsManifest.empty(),
                    canCommit = false,
                )
            }
            return CustomLyricsIndexState(
                pointer = pointer,
                manifest = ModuleSettingsSchema.decodeLegacyCustomLyricsManifest(preferences),
            )
        }

        /** Read-only resolution for processes that never write the index. */
        fun resolve(
            preferences: Map<String, *>,
            openFile: (String) -> InputStream?,
        ): CustomLyricsManifest = state(preferences, openFile).manifest

        private fun readIndexBytes(
            fileId: String,
            openFile: (String) -> InputStream?,
        ): ByteArray? = runCatching {
            openFile(fileId)?.use { input ->
                CustomLyricsFilePolicy.readBounded(
                    input,
                    CustomLyricsManifestPolicy.MAX_INDEX_BYTES,
                )
            }
        }.getOrNull()
    }
}
