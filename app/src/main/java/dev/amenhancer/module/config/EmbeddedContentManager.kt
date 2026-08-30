package dev.amenhancer.module.config

import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.font.FontImportTransaction
import dev.amenhancer.module.lyrics.CustomLyricsBackupCodec
import dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult
import dev.amenhancer.module.lyrics.CustomLyricsBatchSaveResult
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.lyrics.CustomLyricsImportTransaction
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsRestoreResult
import dev.amenhancer.module.lyrics.CustomLyricsRestoreTransaction
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateCoordinator
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateSources
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.LyricsFontManifest
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal sealed interface EmbeddedLyricsMutationResult {
    data class Updated(val manifest: CustomLyricsManifest) : EmbeddedLyricsMutationResult
    data class Failed(val message: String) : EmbeddedLyricsMutationResult
}

/**
 * Host-local content facade for the embedded settings UI.
 *
 * The session owns the host storage and the index pointer protocol. This
 * class only composes the existing transactions and retires files after the
 * corresponding manifest or pointer has been published successfully.
 */
internal class EmbeddedContentManager(
    private val session: EmbeddedConfigurationSession,
    private val fileIdFactory: (String) -> String = ::newEmbeddedFileId,
    private val validateTypeface: (ByteArray) -> Boolean = { true },
) {
    private val mutationLock = Any()

    fun fontManifest(): LyricsFontManifest = synchronized(mutationLock) {
        session.settings().fontManifest
    }

    /** Imports a fresh font file and retires the previous file after publish. */
    fun importFont(displayName: String, bytes: ByteArray): FontImportResult = synchronized(mutationLock) {
        val previous = session.settings().fontManifest
        val result = FontImportTransaction(
            fileIdFactory = { fileIdFactory("font") },
            writeRemoteFile = session::writeFile,
            publishManifest = session::saveFontManifest,
            deleteRemoteFile = { fileId -> session.deleteFile(fileId) },
            validateTypeface = validateTypeface,
        ).import(displayName, bytes)

        if (result is FontImportResult.Imported) {
            retireFile(previous.fileId, result.manifest.fileId) {
                FontManifestPolicy.isValidFileId(it)
            }
        }
        result
    }

    /** Disables the font pointer, then best-effort removes its unreferenced file. */
    fun setFontEnabled(enabled: Boolean): Boolean = synchronized(mutationLock) {
        val previous = session.settings().fontManifest
        if (enabled == previous.enabled) return@synchronized true
        if (!enabled && !session.saveFontManifest(LyricsFontManifest.disabled())) return@synchronized false
        true.also {
            if (!enabled) {
                retireFile(previous.fileId, replacementFileId = null) {
                    FontManifestPolicy.isValidFileId(it)
                }
            }
        }
    }

    fun clearFont(): Boolean = setFontEnabled(false)

    fun lyricsManifest(): CustomLyricsManifest = synchronized(mutationLock) {
        currentLyricsManifest()
    }

    fun listLyrics(): List<CustomLyricsEntry> = lyricsManifest().entries

    /** Reads and verifies the TTML body; disabled entries remain readable for editing. */
    fun readLyrics(appleMusicId: Long): String? = synchronized(mutationLock) {
        val entry = currentLyricsManifest().entries.singleOrNull { it.appleMusicId == appleMusicId }
            ?: return@synchronized null
        CustomLyricsFileReader(::readFile).read(entry)
    }

    fun addLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String = CustomLyricsSources.MANUAL,
        enabled: Boolean = true,
    ): CustomLyricsSaveResult = saveLyrics(
        draft = CustomLyricsDraft(
            appleMusicId = appleMusicId,
            displayName = displayName,
            ttml = ttml,
            source = source,
            enabled = enabled,
        ),
    )

    fun updateLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String = CustomLyricsSources.MANUAL,
        enabled: Boolean = true,
    ): CustomLyricsSaveResult = saveLyrics(
        draft = CustomLyricsDraft(
            appleMusicId = appleMusicId,
            displayName = displayName,
            ttml = ttml,
            source = source,
            enabled = enabled,
        ),
        replacingAppleMusicId = appleMusicId,
    )

    fun saveLyrics(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            val current = currentLyricsManifest()
            CustomLyricsImportTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    session.commitCustomLyrics(next) is CustomLyricsIndexCommitResult.Committed
                },
                deleteRemoteFile = { fileId -> session.deleteFile(fileId) },
            ).upsert(current, draft, replacingAppleMusicId)
        }
    }

    /**
     * Atomically imports one TTML body for several Apple Music IDs.
     *
     * [CustomLyricsImportTransaction] writes every generated file before it
     * publishes the replacement manifest, so a failed write or publish leaves
     * the previous mappings intact.  Replaced IDs are retired only after the
     * new manifest has been committed.
     */
    fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): CustomLyricsBatchSaveResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            val current = currentLyricsManifest()
            CustomLyricsImportTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    session.commitCustomLyrics(next) is CustomLyricsIndexCommitResult.Committed
                },
                deleteRemoteFile = { fileId -> session.deleteFile(fileId) },
            ).upsertMany(
                oldManifest = current,
                draft = draft,
                replacingAppleMusicIds = replacingAppleMusicIds,
            )
        }
    }

    fun importOnlineLyrics(
        appleMusicId: Long,
        displayName: String,
        imported: CustomLyricsOnlineImportResult,
        enabled: Boolean = true,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = when (imported) {
        is CustomLyricsOnlineImportResult.Failed -> CustomLyricsSaveResult.Failed(imported.message)
        is CustomLyricsOnlineImportResult.Imported -> saveLyrics(
            draft = CustomLyricsDraft(
                appleMusicId = appleMusicId,
                displayName = displayName,
                ttml = imported.ttml,
                source = imported.source,
                enabled = enabled,
            ),
            replacingAppleMusicId = replacingAppleMusicId,
        )
    }

    /** Convenience overload for callers that already have the online TTML and source. */
    fun importOnlineLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String,
        enabled: Boolean = true,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = saveLyrics(
        draft = CustomLyricsDraft(
            appleMusicId = appleMusicId,
            displayName = displayName,
            ttml = ttml,
            source = source,
            enabled = enabled,
        ),
        replacingAppleMusicId = replacingAppleMusicId,
    )

    fun setLyricsEnabled(
        appleMusicIds: List<Long>,
        enabled: Boolean,
    ): EmbeddedLyricsMutationResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            val targetIds = appleMusicIds.toSet()
            if (targetIds.isEmpty() || targetIds.any { it <= 0L }) {
                return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed("歌词映射不存在")
            }
            val current = currentLyricsManifest()
            var found = 0
            val nextEntries = current.entries.map { entry ->
                if (entry.appleMusicId in targetIds) {
                    found += 1
                    entry.copy(enabled = enabled)
                } else {
                    entry
                }
            }
            if (found != targetIds.size) {
                return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed("歌词映射不存在")
            }
            publishLyrics(CustomLyricsManifest(nextEntries))
        }
    }

    /** Backward-compatible single-ID enable/disable API. */
    fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedLyricsMutationResult =
        setLyricsEnabled(listOf(appleMusicId), enabled)

    fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedLyricsMutationResult =
        synchronized(mutationLock) {
            session.withCustomLyricsMutation {
                val targetIds = appleMusicIds.toSet()
                if (targetIds.isEmpty() || targetIds.any { it <= 0L }) {
                    return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed("歌词映射不存在")
                }
                val current = currentLyricsManifest()
                val removed = current.entries.filter { it.appleMusicId in targetIds }
                if (removed.size != targetIds.size) {
                    return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed("歌词映射不存在")
                }
                when (val result = publishLyrics(
                    CustomLyricsManifest(current.entries.filterNot { it.appleMusicId in targetIds }),
                )) {
                    is EmbeddedLyricsMutationResult.Updated -> {
                        val nextFileIds = result.manifest.entries.mapTo(mutableSetOf()) { it.fileId }
                        removed.map { it.fileId }
                            .filterNot(nextFileIds::contains)
                            .distinct()
                            .forEach { fileId -> runCatching { session.deleteFile(fileId) } }
                        result
                    }
                    is EmbeddedLyricsMutationResult.Failed -> result
                }
            }
        }

    /** Backward-compatible single-ID delete API. */
    fun deleteLyrics(appleMusicId: Long): EmbeddedLyricsMutationResult =
        deleteLyrics(listOf(appleMusicId))

    /** Removes an automatic entry only when its persisted body and metadata still match. */
    fun removeLyricsIfMatches(
        appleMusicId: Long,
        source: String,
        displayName: String?,
        ttml: String,
    ): Boolean = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            val entry = currentLyricsManifest().entries.singleOrNull {
                it.appleMusicId == appleMusicId
            } ?: return@withCustomLyricsMutation false
            val inspected = CustomLyricsFilePolicy.inspect(ttml)
                as? dev.amenhancer.module.lyrics.CustomLyricsInspection.Accepted
                ?: return@withCustomLyricsMutation false
            if (
                entry.source != source ||
                entry.displayName != CustomLyricsManifestPolicy.sanitizeDisplayName(
                    displayName?.takeIf(String::isNotBlank)
                        ?: "自动缓存歌词 · $appleMusicId",
                ) ||
                !entry.sha256.equals(inspected.sha256, ignoreCase = true)
            ) {
                return@withCustomLyricsMutation false
            }
            val next = CustomLyricsManifest(currentLyricsManifest().entries.filterNot {
                it.appleMusicId == appleMusicId
            })
            when (val result = publishLyrics(next)) {
                is EmbeddedLyricsMutationResult.Updated -> {
                    runCatching { session.deleteFile(entry.fileId) }
                    true
                }
                is EmbeddedLyricsMutationResult.Failed -> false
            }
        }
    }

    fun backupLyrics(out: OutputStream): CustomLyricsBackupEncodeResult = synchronized(mutationLock) {
        CustomLyricsBackupCodec.encode(currentLyricsManifest(), ::readFile, out)
    }

    fun restoreLyrics(
        input: InputStream,
        policy: CustomLyricsRestorePolicy = CustomLyricsRestorePolicy.OVERWRITE,
    ): CustomLyricsRestoreResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            val current = currentLyricsManifest()
            CustomLyricsRestoreTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    session.commitCustomLyrics(next, allowRecovery = true) is CustomLyricsIndexCommitResult.Committed
                },
                deleteRemoteFile = { fileId -> session.deleteFile(fileId) },
            ).merge(current, policy) { onFile -> CustomLyricsBackupCodec.decode(input, onFile) }
        }
    }

    /**
     * Checks every remotely managed entry and atomically publishes only the
     * bodies whose canonical UTF-8 bytes changed. Network work is deliberately
     * outside [mutationLock]; the final compare-and-swap protects concurrent
     * manual edits and automatic cache writes from being overwritten.
     */
    fun updateLyrics(
        sources: CustomLyricsUpdateSources,
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult {
        val baseline = synchronized(mutationLock) { session.customLyricsIndexState() }
        if (!baseline.canCommit) {
            return CustomLyricsUpdateResult.Failed("歌词索引文件不可读，无法更新")
        }
        return CustomLyricsUpdateCoordinator(sources).update(
            oldManifest = baseline.manifest,
            fileIdFactory = { fileIdFactory("lyrics") },
            writeRemoteFile = session::writeFile,
            publishManifest = { next ->
                session.commitCustomLyricsIfUnchanged(baseline, next) is CustomLyricsIndexCommitResult.Committed
            },
            deleteRemoteFile = { fileId -> session.deleteFile(fileId) },
            isBaselineCurrent = { session.customLyricsIndexState() == baseline },
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }

    private fun currentLyricsManifest(): CustomLyricsManifest =
        CustomLyricsIndexRepository.resolve(session.values(), session::openFile)

    private fun publishLyrics(next: CustomLyricsManifest): EmbeddedLyricsMutationResult =
        when (val result = session.commitCustomLyrics(next)) {
            is CustomLyricsIndexCommitResult.Committed -> EmbeddedLyricsMutationResult.Updated(result.manifest)
            is CustomLyricsIndexCommitResult.Failed -> EmbeddedLyricsMutationResult.Failed(result.message)
        }

    private fun readFile(fileId: String): ByteArray? = runCatching {
        session.openFile(fileId)?.use { input -> CustomLyricsFilePolicy.readBounded(input) }
    }.getOrNull()

    private fun retireFile(
        previousFileId: String,
        replacementFileId: String?,
        isValidFileId: (String) -> Boolean,
    ) {
        if (
            previousFileId.isNotBlank() &&
            previousFileId != replacementFileId &&
            isValidFileId(previousFileId)
        ) {
            session.deleteFile(previousFileId)
        }
    }
}

private fun newEmbeddedFileId(prefix: String): String =
    "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
