package dev.amenhancer.module.settings

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.ui.*

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.EmbeddedLyricsMutationResult
import dev.amenhancer.module.font.FontFilePolicy
import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.hook.LyricsPlaybackRuntimeRegistry
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmLyricsIndexEntry
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.embeddedLunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsBatchSaveResult
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsInspection
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.createEmbeddedOnlineLyricsImporter
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsRestoreResult
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateSources
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.io.FileOutputStream

/** Android/SAF/network facade consumed only by the in-host settings dialog. */
internal class EmbeddedRuntimeSettingsController(
    context: Context,
    private val session: EmbeddedConfigurationSession,
    private val currentSong: () -> CurrentSongDetails?,
) : EmbeddedSettingsController {
    private val appContext = context.applicationContext
    internal val content by lazy {
        EmbeddedContentManager(session = session, validateTypeface = ::canBuildTypeface)
    }
    internal val lunabeatCatalogCache by lazy {
        embeddedLunabeatCatalogCache(appContext, content.sourceCache, session.writable)
    }

    override fun currentSettings(): ModuleSettings = session.settings()

    override fun saveOrdinarySettings(settings: ModuleSettings): Boolean = session.saveSettings(settings)

    override fun saveAutomaticLyricsEnabled(enabled: Boolean): Boolean =
        session.saveAutomaticLyricsEnabled(enabled).also { if (it) LyricsPlaybackRuntimeRegistry.onSettingsChanged() }

    override fun saveAutomaticLyricsSourceOrder(order: List<String>): Boolean =
        session.saveAutomaticLyricsSourceOrder(order)

    override fun currentSongDetails(): CurrentSongDetails? = currentSong()

    override fun lyricsEntries(): List<CustomLyricsEntry> = content.listLyrics()

    override fun readLyrics(appleMusicId: Long): String? = content.readLyrics(appleMusicId)

    override fun readTtml(uri: Uri): String? = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use(CustomLyricsFilePolicy::readBounded)
            ?: return@runCatching null
        when (val inspection = CustomLyricsFilePolicy.inspect(bytes.toString(Charsets.UTF_8))) {
            is CustomLyricsInspection.Accepted -> inspection.ttml
            is CustomLyricsInspection.Rejected -> null
        }
    }.getOrNull()

    override fun saveLyrics(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long?,
    ): EmbeddedActionResult = content.saveLyrics(draft, replacingAppleMusicId).toActionResult()

    override fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long>,
    ): EmbeddedActionResult = content.saveLyrics(draft, replacingAppleMusicIds).toActionResult()

    override fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        content.setLyricsEnabled(appleMusicId, enabled).toActionResult(ModuleText.LYRICS_STATE_UPDATED.text())

    override fun setLyricsEnabled(
        appleMusicIds: List<Long>,
        enabled: Boolean,
    ): EmbeddedActionResult = content.setLyricsEnabled(appleMusicIds, enabled)
        .toActionResult(ModuleText.LYRICS_STATE_UPDATED.text())

    override fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        content.deleteLyrics(appleMusicId).toActionResult(ModuleText.LYRICS_MAPPING_DELETED.text())

    override fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedActionResult =
        content.deleteLyrics(appleMusicIds).toActionResult(ModuleText.LYRICS_MAPPING_DELETED.text())

    override fun importFont(uri: Uri): EmbeddedActionResult {
        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use(FontFilePolicy::readBounded)
                ?: return EmbeddedActionResult.Failed(ModuleText.FONT_READ_FAILED.text())
        } catch (_: Throwable) {
            return EmbeddedActionResult.Failed(ModuleText.FONT_READ_FAILED.text())
        }
        return when (val result = content.importFont(displayName(uri, ModuleText.IMPORTED_FONT.text()), bytes)) {
            is FontImportResult.Imported -> EmbeddedActionResult.Done(ModuleText.FONT_IMPORTED.text())
            is FontImportResult.Failed -> EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun clearFont(): EmbeddedActionResult =
        if (content.clearFont()) {
            EmbeddedActionResult.Done(ModuleText.FONT_CLEARED.text())
        } else {
            EmbeddedActionResult.Failed(ModuleText.FONT_CLEAR_FAILED.text())
        }

    override fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long?,
    ): EmbeddedActionResult {
        val ttml = try {
            appContext.contentResolver.openInputStream(uri)?.use(CustomLyricsFilePolicy::readBounded)
                ?.toString(Charsets.UTF_8)
                ?: return EmbeddedActionResult.Failed(ModuleText.TTML_READ_FAILED.text())
        } catch (_: Throwable) {
            return EmbeddedActionResult.Failed(ModuleText.TTML_READ_FAILED.text())
        }
        return content.saveLyrics(
            CustomLyricsDraft(
                appleMusicId = appleMusicId,
                displayName = displayName,
                ttml = ttml,
                source = CustomLyricsSources.MANUAL,
            ),
            replacingAppleMusicId,
        ).toActionResult()
    }

    override fun backupLyrics(uri: Uri): EmbeddedActionResult {
        val output = runCatching { appContext.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: return EmbeddedActionResult.Failed(ModuleText.BACKUP_CREATE_FAILED.text())
        return when (val result = output.use(content::backupLyrics)) {
            is dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult.Encoded ->
                EmbeddedActionResult.Done(ModuleText.LYRICS_BACKED_UP.text(result.entryCount))
            is dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult.Failed ->
                EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun restoreLyrics(
        uri: Uri,
        policy: dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy,
    ): EmbeddedActionResult {
        val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return EmbeddedActionResult.Failed(ModuleText.BACKUP_FILE_UNREADABLE.text())
        return when (val result = input.use { content.restoreLyrics(it, policy) }) {
            is CustomLyricsRestoreResult.Restored ->
                EmbeddedActionResult.Done(ModuleText.LYRICS_RESTORED.text(result.manifest.entries.size))
            is CustomLyricsRestoreResult.Failed -> EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun fetchOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
    ): CustomLyricsOnlineImportResult {
        val importer = createEmbeddedOnlineLyricsImporter(lunabeatCatalogCache)
        return when (source) {
            EmbeddedOnlineSource.AMLL -> importer.importAmll(appleMusicId)
            EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(appleMusicId)
            EmbeddedOnlineSource.LUNABEAT -> importer.importLunabeat(appleMusicId)
        }
    }

    /**
     * Checks all remote-backed custom lyrics and atomically updates changed
     * bodies. The caller owns cancellation and invokes this on its worker;
     * source index/catalog reads happen once and are never performed on the UI
     * thread.
     */
    override fun updateLyrics(
        isCancelled: () -> Boolean,
        onProgress: (CustomLyricsUpdateProgress) -> Unit,
    ): CustomLyricsUpdateResult = runCatching {
        val transport = HttpLyricTransport()
        val amll = AmllTtmlClient(transport)
        val amLyrics = AmLyricsClient(transport)
        val lunabeat = LunabeatClient(
            indexTransport = HttpLyricTransport(maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES),
            lyricsTransport = HttpLyricTransport(),
            cache = lunabeatCatalogCache,
        )
        content.updateLyrics(
            sources = CustomLyricsUpdateSources(
                fetchAmll = amll::fetch,
                loadAmLyricsIndex = amLyrics::fetchIndex,
                fetchAmLyricsTtml = amLyrics::fetchTtml,
                loadLunabeatCatalog = lunabeat::loadCatalog,
                fetchLunabeatTtml = lunabeat::fetch,
            ),
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }.getOrElse { error ->
        CustomLyricsUpdateResult.Failed(ModuleText.LYRICS_UPDATE_FAILED.text(error.message.orEmpty()))
    }

    private fun displayName(uri: Uri, fallback: String): String = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let(cursor::getString)
        }
    }.getOrNull().orEmpty().ifBlank { fallback }

    private fun canBuildTypeface(bytes: ByteArray): Boolean {
        val temporary = runCatching { File.createTempFile("ampp-font-", ".tmp", appContext.cacheDir) }
            .getOrNull() ?: return false
        return try {
            FileOutputStream(temporary).use { it.write(bytes) }
            Typeface.Builder(temporary).build() != null
        } catch (_: Throwable) {
            false
        } finally {
            temporary.delete()
        }
    }
}

private fun CustomLyricsSaveResult.toActionResult(): EmbeddedActionResult = when (this) {
    is CustomLyricsSaveResult.Saved -> EmbeddedActionResult.Done(ModuleText.LYRICS_SAVED.text())
    is CustomLyricsSaveResult.Failed -> EmbeddedActionResult.Failed(message)
}

private fun CustomLyricsBatchSaveResult.toActionResult(): EmbeddedActionResult = when (this) {
    is CustomLyricsBatchSaveResult.Saved -> EmbeddedActionResult.Done(ModuleText.LYRICS_SAVED.text())
    is CustomLyricsBatchSaveResult.Failed -> EmbeddedActionResult.Failed(message)
}

private fun EmbeddedLyricsMutationResult.toActionResult(success: String): EmbeddedActionResult = when (this) {
    is EmbeddedLyricsMutationResult.Updated -> EmbeddedActionResult.Done(success)
    is EmbeddedLyricsMutationResult.Failed -> EmbeddedActionResult.Failed(message)
}
