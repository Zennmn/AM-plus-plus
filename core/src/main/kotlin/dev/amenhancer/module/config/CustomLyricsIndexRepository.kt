package dev.amenhancer.module.config

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.InputStream

/** Current index state: the published pointer (may be stale) plus the resolved manifest. */
data class CustomLyricsIndexState(
    val pointer: CustomLyricsIndexPointer?,
    val manifest: CustomLyricsManifest,
    val canCommit: Boolean = true,
    /** True only when the published index was read successfully, including an empty index. */
    val isResolved: Boolean = false,
)

sealed interface CustomLyricsIndexCommitResult {
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
class CustomLyricsIndexRepository(
    private val newIndexFileId: () -> String,
    private val openFile: (String) -> InputStream?,
    private val writeRemoteFile: (String, ByteArray) -> Boolean,
    private val publishPointer: (CustomLyricsIndexPointer) -> Boolean,
    private val deleteRemoteFile: (String) -> Unit,
    private val readIndexBytes: ((String, Int) -> ByteArray?)? = null,
    private val maxIndexBytes: Int = CustomLyricsManifestPolicy.MAX_INDEX_BYTES,
) {
    private fun readIndex(fileId: String, maxBytes: Int): ByteArray? =
        readIndexBytes?.invoke(fileId, maxBytes)
            ?: readBoundedIndexBytes(fileId, maxBytes, openFile)

    fun state(preferences: Map<String, *>): CustomLyricsIndexState =
        Companion.stateWithCodec(
            preferences = preferences,
            openFile = openFile,
            readIndexBytes = ::readIndex,
        )

    fun commit(
        state: CustomLyricsIndexState,
        next: CustomLyricsManifest,
        allowRecovery: Boolean = false,
        maxIndexBytes: Int = this.maxIndexBytes,
        encode: (CustomLyricsManifest) -> String = CustomLyricsManifestCodec::encode,
        decode: (ByteArray) -> CustomLyricsManifest? = { bytes ->
            CustomLyricsManifestCodec.decodeIndexFile(bytes.toString(Charsets.UTF_8))
        },
    ): CustomLyricsIndexCommitResult {
        if (!state.canCommit && !allowRecovery) {
            return CustomLyricsIndexCommitResult.Failed(ModuleText.LYRICS_INDEX_UNREADABLE.text())
        }
        val encoded = encode(next).toByteArray(Charsets.UTF_8)
        if (encoded.size > maxIndexBytes) {
            return CustomLyricsIndexCommitResult.Failed(ModuleText.LYRICS_INDEX_TOO_LARGE.text())
        }
        val fileId = runCatching(newIndexFileId).getOrNull()
            ?: return CustomLyricsIndexCommitResult.Failed(ModuleText.INDEX_ID_CREATE_FAILED.text())
        if (!CustomLyricsManifestPolicy.isValidFileId(fileId)) {
            return CustomLyricsIndexCommitResult.Failed(ModuleText.INDEX_ID_INVALID.text())
        }
        if (!runCatching { writeRemoteFile(fileId, encoded) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsIndexCommitResult.Failed(ModuleText.INDEX_WRITE_FAILED.text())
        }
        val pointer = CustomLyricsIndexPointer(
            fileId = fileId,
            generation = (state.pointer?.generation ?: 0L) + 1L,
            sha256 = CustomLyricsFilePolicy.sha256(encoded),
            sizeBytes = encoded.size.toLong(),
        )
        if (!runCatching { publishPointer(pointer) }.getOrDefault(false)) {
            runCatching { deleteRemoteFile(fileId) }
            return CustomLyricsIndexCommitResult.Failed(ModuleText.INDEX_PUBLISH_FAILED.text())
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
        ): CustomLyricsIndexState = stateWithCodec(
            preferences = preferences,
            openFile = openFile,
        )

        /** Extended state decoder used by the source index; keeps [state] trailing-lambda compatible. */
        fun stateWithCodec(
            preferences: Map<String, *>,
            openFile: (String) -> InputStream?,
            pointerDecoder: (Map<String, *>) -> CustomLyricsIndexPointer? = ModuleSettingsSchema::decodeIndexPointer,
            pointerPresent: (Map<String, *>) -> Boolean = ModuleSettingsSchema::hasIndexPointerValues,
            legacyDecoder: (Map<String, *>) -> CustomLyricsManifest =
                ModuleSettingsSchema::decodeLegacyCustomLyricsManifest,
            decodeIndex: (ByteArray) -> CustomLyricsManifest? = { bytes ->
                CustomLyricsManifestCodec.decodeIndexFile(bytes.toString(Charsets.UTF_8))
            },
            readIndexBytes: (String, Int) -> ByteArray? = { fileId, maxBytes ->
                readBoundedIndexBytes(fileId, maxBytes, openFile)
            },
        ): CustomLyricsIndexState {
            val pointer = pointerDecoder(preferences)
            if (pointer != null) {
                val bytes = readIndexBytes(pointer.fileId, CustomLyricsManifestPolicy.MAX_INDEX_BYTES)
                if (
                    bytes != null &&
                    bytes.size.toLong() == pointer.sizeBytes &&
                    CustomLyricsFilePolicy.sha256(bytes)
                        .equals(pointer.sha256, ignoreCase = true)
                ) {
                    val decoded = decodeIndex(bytes)
                    if (decoded != null) {
                        return CustomLyricsIndexState(pointer, decoded, isResolved = true)
                    }
                }
                return CustomLyricsIndexState(
                    pointer = pointer,
                    manifest = CustomLyricsManifest.empty(),
                    canCommit = false,
                )
            }
            if (pointerPresent(preferences)) {
                return CustomLyricsIndexState(
                    pointer = null,
                    manifest = CustomLyricsManifest.empty(),
                    canCommit = false,
                )
            }
            return CustomLyricsIndexState(
                pointer = pointer,
                manifest = legacyDecoder(preferences),
                isResolved = true,
            )
        }

        /** Read-only resolution for processes that never write the index. */
        fun resolve(
            preferences: Map<String, *>,
            openFile: (String) -> InputStream?,
        ): CustomLyricsManifest = state(preferences, openFile = openFile).manifest

        private fun readBoundedIndexBytes(
            fileId: String,
            maxBytes: Int,
            openFile: (String) -> InputStream?,
        ): ByteArray? = runCatching {
            openFile(fileId)?.use { input ->
                CustomLyricsFilePolicy.readBounded(input, maxBytes)
            }
        }.getOrNull()
    }
}
