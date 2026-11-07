package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.net.Uri
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.ModuleSettings

/** Small facade so the host UI never depends on a particular storage backend. */
internal interface EmbeddedSettingsController {
    fun currentSettings(): ModuleSettings

    fun saveOrdinarySettings(settings: ModuleSettings): Boolean

    fun currentSongDetails(): CurrentSongDetails? = null
    fun lyricsEntries(): List<CustomLyricsEntry> = emptyList()
    fun readLyrics(appleMusicId: Long): String? = null
    /** Reads and validates a SAF TTML document without persisting it. */
    fun readTtml(uri: Uri): String? = null
    fun saveLyrics(draft: CustomLyricsDraft, replacingAppleMusicId: Long? = null): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun saveLyrics(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long> = emptyList(),
    ): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun setLyricsEnabled(appleMusicIds: List<Long>, enabled: Boolean): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun deleteLyrics(appleMusicIds: List<Long>): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.LYRICS_MANAGER_UNAVAILABLE.text())
    fun importFont(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.FONT_IMPORT_UNAVAILABLE.text())
    fun clearFont(): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.FONT_MANAGER_UNAVAILABLE.text())
    fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long? = null,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.LYRICS_IMPORT_UNAVAILABLE.text())
    fun backupLyrics(uri: Uri): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.BACKUP_UNAVAILABLE.text())
    fun restoreLyrics(uri: Uri, policy: CustomLyricsRestorePolicy): EmbeddedActionResult =
        EmbeddedActionResult.Failed(ModuleText.RESTORE_UNAVAILABLE.text())
    fun importOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
        displayName: String,
    ): EmbeddedActionResult = EmbeddedActionResult.Failed(ModuleText.ONLINE_IMPORT_UNAVAILABLE.text())

    fun updateLyrics(
        isCancelled: () -> Boolean = { false },
        onProgress: (CustomLyricsUpdateProgress) -> Unit = {},
    ): CustomLyricsUpdateResult = CustomLyricsUpdateResult.Failed(ModuleText.LYRICS_UPDATE_UNAVAILABLE.text())
}

internal fun interface EmbeddedSafSelectionHandler {
    fun onSelected(operation: EmbeddedSafOperation, uri: Uri)
}

