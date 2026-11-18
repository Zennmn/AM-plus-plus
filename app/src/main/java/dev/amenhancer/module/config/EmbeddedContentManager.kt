package dev.amenhancer.module.config

import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.hook.AutoLyricsCandidate

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
import dev.amenhancer.module.lyrics.CustomLyricsUpdateIssue
import dev.amenhancer.module.lyrics.CustomLyricsUpdateSummary
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
    /** Third-party source index and metadata snapshot; also owns manual-change notifications. */
    val sourceCache: EmbeddedLyricsSourceStore = EmbeddedLyricsSourceStore(session, fileIdFactory)

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

    fun lyricsManifest(): CustomLyricsManifest = sourceCache.manualManifest()

    fun listLyrics(): List<CustomLyricsEntry> = lyricsManifest().entries

    /** Reads and verifies the TTML body; disabled entries remain readable for editing. */
    fun readLyrics(appleMusicId: Long): String? = synchronized(mutationLock) {
        val entry = sourceCache.manualEntry(appleMusicId) ?: return@synchronized null
        CustomLyricsFileReader(::readFile).read(entry)
    }

    fun addLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String = CustomLyricsSources.MANUAL,
        enabled: Boolean = true,
    ): CustomLyricsSaveResult {
        val draft = CustomLyricsDraft(
            appleMusicId = appleMusicId,
            displayName = displayName,
            ttml = ttml,
            source = source,
            enabled = enabled,
        )
        return saveLyrics(draft)
    }

    fun updateLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String = CustomLyricsSources.MANUAL,
        enabled: Boolean = true,
    ): CustomLyricsSaveResult {
        val draft = CustomLyricsDraft(
            appleMusicId = appleMusicId,
            displayName = displayName,
            ttml = ttml,
            source = source,
            enabled = enabled,
        )
        return saveLyrics(draft)
    }

    fun saveLyrics(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult = if (
        CustomLyricsManifestPolicy.isThirdPartySource(
            CustomLyricsManifestPolicy.canonicalSource(draft.source),
        )
    ) {
        importOnlineLyrics(
            appleMusicId = draft.appleMusicId,
            displayName = draft.displayName,
            ttml = draft.ttml,
            source = draft.source,
            enabled = draft.enabled,
        )
    } else synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            if (!sourceCache.prepare()) return@withCustomLyricsMutation CustomLyricsSaveResult.Failed(
                ModuleText.LYRICS_INDEX_UNREADABLE.text(),
            )
            val current = currentLyricsManifest()
            var publishFailure: String? = null
            val result = CustomLyricsImportTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    when (val committed = commitManualLyrics(next)) {
                        is CustomLyricsIndexCommitResult.Committed -> true
                        is CustomLyricsIndexCommitResult.Failed -> { publishFailure = committed.message; false }
                    }
                },
                deleteRemoteFile = ::deleteManualBody,
            ).upsert(
                current,
                draft,
                replacingAppleMusicId = replacingAppleMusicId ?: draft.appleMusicId.takeIf { id ->
                    current.entries.any { it.appleMusicId == id }
                },
            )
            publishFailure?.let { CustomLyricsSaveResult.Failed(it) } ?: result
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
    ): CustomLyricsBatchSaveResult = if (
        CustomLyricsManifestPolicy.isThirdPartySource(
            CustomLyricsManifestPolicy.canonicalSource(draft.source),
        )
    ) {
        val source = CustomLyricsManifestPolicy.canonicalSource(draft.source)
        val written = sourceCache.writeCandidates(
            draft.appleMusicIds,
            AutoLyricsCandidate(source, draft.ttml, draft.displayName),
            enabled = draft.enabled,
        )
        if (!written) {
            CustomLyricsBatchSaveResult.Failed(ModuleText.LYRICS_FILE_WRITE_FAILED.text())
        } else {
            CustomLyricsBatchSaveResult.Saved(
                manifest = lyricsManifest(),
                entries = draft.appleMusicIds.mapNotNull { id -> sourceCache.entryFor(id, source) },
            )
        }
    } else synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            if (!sourceCache.prepare()) return@withCustomLyricsMutation CustomLyricsBatchSaveResult.Failed(
                ModuleText.LYRICS_INDEX_UNREADABLE.text(),
            )
            val current = currentLyricsManifest()
            val currentIds = current.entries.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
            val replaceIds = replacingAppleMusicIds.ifEmpty { draft.appleMusicIds.filter(currentIds::contains) }
            var publishFailure: String? = null
            val result = CustomLyricsImportTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    when (val committed = commitManualLyrics(next)) {
                        is CustomLyricsIndexCommitResult.Committed -> true
                        is CustomLyricsIndexCommitResult.Failed -> { publishFailure = committed.message; false }
                    }
                },
                deleteRemoteFile = ::deleteManualBody,
            ).upsertMany(
                oldManifest = current,
                draft = draft,
                replacingAppleMusicIds = replaceIds,
            )
            publishFailure?.let { CustomLyricsBatchSaveResult.Failed(it) } ?: result
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
        is CustomLyricsOnlineImportResult.Imported -> {
            val written = sourceCache.writeCandidate(
                appleMusicId,
                AutoLyricsCandidate(imported.source, imported.ttml, displayName),
                enabled = enabled,
            )
            if (written) {
                CustomLyricsSaveResult.Saved(
                    manifest = lyricsManifest(),
                    entry = sourceCache.entryFor(appleMusicId, imported.source)
                        ?: return CustomLyricsSaveResult.Failed(ModuleText.LYRICS_MAPPING_PUBLISH_FAILED.text()),
                )
            } else CustomLyricsSaveResult.Failed(ModuleText.LYRICS_FILE_WRITE_FAILED.text())
        }
    }

    /** Convenience overload for callers that already have the online TTML and source. */
    fun importOnlineLyrics(
        appleMusicId: Long,
        displayName: String,
        ttml: String,
        source: String,
        enabled: Boolean = true,
        replacingAppleMusicId: Long? = null,
    ): CustomLyricsSaveResult {
        val sourceValue = CustomLyricsManifestPolicy.canonicalSource(source)
        return if (CustomLyricsManifestPolicy.isThirdPartySource(sourceValue)) {
            if (sourceCache.writeCandidate(
                    appleMusicId,
                    AutoLyricsCandidate(sourceValue, ttml, displayName),
                    enabled = enabled,
                )
            ) {
                CustomLyricsSaveResult.Saved(
                    manifest = lyricsManifest(),
                    entry = sourceCache.entryFor(appleMusicId, sourceValue)
                        ?: return CustomLyricsSaveResult.Failed(ModuleText.LYRICS_MAPPING_PUBLISH_FAILED.text()),
                )
            } else CustomLyricsSaveResult.Failed(ModuleText.LYRICS_FILE_WRITE_FAILED.text())
        } else saveLyrics(
            draft = CustomLyricsDraft(
                appleMusicId = appleMusicId,
                displayName = displayName,
                ttml = ttml,
                source = sourceValue,
                enabled = enabled,
            ),
            replacingAppleMusicId = replacingAppleMusicId,
        )
    }

    fun setLyricsEnabled(
        appleMusicIds: List<Long>,
        enabled: Boolean,
    ): EmbeddedLyricsMutationResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            if (!sourceCache.prepare()) return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(
                ModuleText.LYRICS_INDEX_UNREADABLE.text(),
            )
            val targetIds = appleMusicIds.toSet()
            if (targetIds.isEmpty() || targetIds.any { it <= 0L }) {
                return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(ModuleText.LYRICS_MAPPING_MISSING.text())
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
                return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(ModuleText.LYRICS_MAPPING_MISSING.text())
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
                if (!sourceCache.prepare()) return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(
                    ModuleText.LYRICS_INDEX_UNREADABLE.text(),
                )
                val targetIds = appleMusicIds.toSet()
                if (targetIds.isEmpty() || targetIds.any { it <= 0L }) {
                    return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(ModuleText.LYRICS_MAPPING_MISSING.text())
                }
                val current = currentLyricsManifest()
                val removed = current.entries.filter { it.appleMusicId in targetIds }
                if (removed.size != targetIds.size) {
                    return@withCustomLyricsMutation EmbeddedLyricsMutationResult.Failed(ModuleText.LYRICS_MAPPING_MISSING.text())
                }
                when (val result = publishLyrics(
                    CustomLyricsManifest(current.entries.filterNot { it.appleMusicId in targetIds }),
                )) {
                    is EmbeddedLyricsMutationResult.Updated -> {
                        val nextFileIds = result.manifest.entries.mapTo(mutableSetOf()) { it.fileId }
                        removed.map { it.fileId }
                            .filterNot(nextFileIds::contains)
                            .distinct()
                            .forEach { fileId -> runCatching { deleteManualBody(fileId) } }
                        result
                    }
                    is EmbeddedLyricsMutationResult.Failed -> result
                }
            }
        }

    /** Backward-compatible single-ID delete API. */
    fun deleteLyrics(appleMusicId: Long): EmbeddedLyricsMutationResult =
        deleteLyrics(listOf(appleMusicId))

    fun backupLyrics(out: OutputStream): CustomLyricsBackupEncodeResult = synchronized(mutationLock) {
        CustomLyricsBackupCodec.encode(currentLyricsManifest(), ::readFile, out)
    }

    fun restoreLyrics(
        input: InputStream,
        policy: CustomLyricsRestorePolicy = CustomLyricsRestorePolicy.OVERWRITE,
    ): CustomLyricsRestoreResult = synchronized(mutationLock) {
        session.withCustomLyricsMutation {
            if (session.customLyricsIndexState().canCommit && !sourceCache.prepare()) {
                return@withCustomLyricsMutation CustomLyricsRestoreResult.Failed(ModuleText.LYRICS_INDEX_UNREADABLE.text())
            }
            val current = currentLyricsManifest()
            CustomLyricsRestoreTransaction(
                fileIdFactory = { fileIdFactory("lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { next ->
                    commitManualLyrics(next, allowRecovery = true) is CustomLyricsIndexCommitResult.Committed
                },
                deleteRemoteFile = ::deleteManualBody,
            ).merge(current, policy) { onFile -> CustomLyricsBackupCodec.decode(input, onFile) }.also { result ->
                if (result is CustomLyricsRestoreResult.Restored) sourceCache.prepare(afterRestore = true)
            }
        }
    }

    /**
     * The existing bulk refresh checks provider caches. Each provider's
     * publication preserves the other providers and the manual index.
     */
    fun updateLyrics(
        sources: CustomLyricsUpdateSources,
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult {
        if (!sourceCache.prepareSource()) {
            return CustomLyricsUpdateResult.Failed(ModuleText.LYRICS_INDEX_UPDATE_UNREADABLE.text())
        }
        val epoch = sourceCache.generation()
        val selected = sourceCache.entries()
        // Legacy auto-cache envelopes have no stable provider, or use a legacy filename.
        // They remain usable and refresh through playback, without rewriting them in bulk.
        val refreshable = selected.filter { CustomLyricsManifestPolicy.isValidFileId(it.fileId) }
        val skipped = selected.size - refreshable.size
        var summary = CustomLyricsUpdateSummary(checked = skipped, skipped = skipped)
        val issues = mutableListOf<CustomLyricsUpdateIssue>()
        val coordinator = CustomLyricsUpdateCoordinator(sources)
        for (entries in refreshable.groupBy(CustomLyricsEntry::source).values) {
            fun cancelled() = isCancelled() || sourceCache.generation() != epoch
            if (cancelled()) return CustomLyricsUpdateResult.Cancelled
            val result = coordinator.update(
                oldManifest = CustomLyricsManifest(entries),
                fileIdFactory = { fileIdFactory("source_lyrics") },
                writeRemoteFile = session::writeFile,
                publishManifest = { !cancelled() && sourceCache.replaceUpdatedEntries(entries, it, epoch) },
                deleteRemoteFile = ::deleteManualBody,
                isBaselineCurrent = { entries.all { sourceCache.entryFor(it.appleMusicId, it.source) == it } },
                isCancelled = ::cancelled,
                onProgress = { progress ->
                    onProgress(CustomLyricsUpdateProgress(
                        summary.checked + progress.checkedEntries, selected.size,
                        summary.updated + progress.updatedEntries, summary.unchanged + progress.unchangedEntries,
                        summary.skipped + progress.skippedEntries, summary.failed + progress.failedEntries,
                    ))
                },
            )
            when (result) {
                is CustomLyricsUpdateResult.Updated -> {
                    val step = result.summary
                    summary = CustomLyricsUpdateSummary(summary.checked + step.checked, summary.updated + step.updated,
                        summary.unchanged + step.unchanged, summary.skipped + step.skipped, summary.failed + step.failed)
                    issues += result.issues
                }
                else -> return result
            }
        }
        return CustomLyricsUpdateResult.Updated(CustomLyricsManifest(sourceCache.entries()), summary, issues)
    }

    private fun currentLyricsManifest(): CustomLyricsManifest =
        sourceCache.manualManifest()

    private fun deleteManualBody(fileId: String) {
        if (!sourceCache.isFileReferenced(fileId) &&
            sourceCache.manualIndexManifest().entries.none { it.fileId == fileId }
        ) session.deleteFile(fileId)
    }

    private fun publishLyrics(next: CustomLyricsManifest): EmbeddedLyricsMutationResult =
        when (val result = commitManualLyrics(next)) {
            is CustomLyricsIndexCommitResult.Committed -> EmbeddedLyricsMutationResult.Updated(
                CustomLyricsManifest(result.manifest.entries.filter {
                    it.source == CustomLyricsSources.MANUAL
                }),
            )
            is CustomLyricsIndexCommitResult.Failed -> EmbeddedLyricsMutationResult.Failed(result.message)
        }

    private fun commitManualLyrics(
        next: CustomLyricsManifest,
        allowRecovery: Boolean = false,
    ): CustomLyricsIndexCommitResult = session.commitCustomLyrics(
        sourceCache.preserveUnmigratedSources(next),
        allowRecovery = allowRecovery,
    )

    private fun readFile(fileId: String): ByteArray? = runCatching {
        session.readFileBytes(fileId)
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
