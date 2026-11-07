package dev.amenhancer.module.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun EmbeddedSettingsHost.showLyricsEditor(
    activity: Activity,
    group: CustomLyricsUiGroup?,
    song: CurrentSongDetails?,
) {
    val host = this
    val entry = group?.primary
    val initialAppleMusicIds = group?.appleMusicIds?.let(CustomLyricsIdParser::format)
        ?: song?.appleMusicId?.toString().orEmpty()
    val initialDisplayName = entry?.displayName ?: song?.title.orEmpty()
    val initialTtml = entry?.let { controller.readLyrics(it.appleMusicId) }.orEmpty()
    val initialSource = entry?.source ?: CustomLyricsSources.MANUAL

    var editorOpen = true
    var onlineImportRevision = 0L
    var ttmlImportRevision = 0L
    var editorPendingTtmlImport: ((String?) -> Unit)? = null
    var appleMusicIds by mutableStateOf(initialAppleMusicIds)
    var displayName by mutableStateOf(initialDisplayName)
    var ttml by mutableStateOf(initialTtml)
    var source by mutableStateOf(initialSource)
    var currentSong by mutableStateOf(host.controller.currentSongDetails())
    var idError by mutableStateOf<String?>(null)
    var ttmlError by mutableStateOf<String?>(null)
    var feedback by mutableStateOf<String?>(null)
    var saveSubmitted by mutableStateOf(false)

    fun clearPendingTtmlImport() {
        if (editorPendingTtmlImport != null &&
            pendingTtmlImport === editorPendingTtmlImport
        ) {
            pendingTtmlImport = null
        }
        editorPendingTtmlImport = null
    }

    fun closeEditor() {
        if (!editorOpen) return
        editorOpen = false
        onlineImportRevision += 1
        ttmlImportRevision += 1
        clearPendingTtmlImport()
    }

    fun saveLyrics(dismiss: () -> Unit) {
        if (saveSubmitted) return
        val ids = CustomLyricsIdParser.parse(appleMusicIds)
        val lyrics = ttml
        if (ids == null) {
            idError = localizedText(ModuleText.MUSIC_IDS_INVALID)
            return
        }
        if (lyrics.isBlank()) {
            ttmlError = localizedText(ModuleText.TTML_REQUIRED)
            return
        }
        val draft = CustomLyricsMultiIdDraft(
            appleMusicIds = ids,
            displayName = displayName,
            ttml = lyrics,
            source = source,
            enabled = entry?.enabled ?: true,
        )
        saveSubmitted = true
        closeEditor()
        host.runAsync(activity) { host.saveMany(draft, group?.appleMusicIds.orEmpty()) }
        dismiss()
    }

    host.showEmbeddedMiuixDialog(
        activity = activity,
        title = if (group == null) {
            localizedText(ModuleText.NEW_LYRICS)
        } else {
            localizedText(ModuleText.EDIT_LYRICS)
        },
        scrollContent = true,
        onDismiss = ::closeEditor,
        footer = { dismiss ->
            Column(Modifier.fillMaxWidth()) {
                feedback?.let { message ->
                    EmbeddedSettingsNotice(
                        text = message,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EmbeddedDialogActions(
                    dismissText = localizedText(ModuleText.CANCEL),
                    onDismiss = {
                        closeEditor()
                        dismiss()
                    },
                    confirmText = localizedText(ModuleText.SAVE),
                    confirmEnabled = !saveSubmitted,
                    onConfirm = { saveLyrics(dismiss) },
                )
            }
        },
    ) { _ ->
        DisposableEffect(Unit) {
            onDispose { closeEditor() }
        }

        val parsedIds = remember(appleMusicIds) {
            CustomLyricsIdParser.parse(appleMusicIds)
        }

        fun captureCurrentSong() {
            val latest = host.controller.currentSongDetails()
            if (latest == null) {
                feedback = localizedText(ModuleText.PLAY_SONG_FIRST)
                return
            }
            currentSong = latest
            onlineImportRevision += 1
            ttmlImportRevision += 1
            appleMusicIds = latest.appleMusicId.toString()
            idError = null
            val capturedName = listOfNotNull(
                latest.title?.takeIf(String::isNotBlank),
                latest.artist?.takeIf(String::isNotBlank),
            ).joinToString(" - ")
            if (capturedName.isNotBlank()) displayName = capturedName
            feedback = localizedText(ModuleText.SONG_DETAILS_CAPTURED)
        }

        fun importOnline(sourceToImport: EmbeddedOnlineSource) {
            if (!editorOpen) return
            val revision = ++onlineImportRevision
            ttmlImportRevision += 1
            clearPendingTtmlImport()
            val appleMusicId = appleMusicIds.toLongOrNull()
            if (appleMusicId == null || appleMusicId <= 0L) {
                idError = localizedText(ModuleText.MUSIC_ID_INVALID)
                feedback = null
                return
            }

            val failureFallback = localizedText(ModuleText.ONLINE_IMPORT_FAILED)
            idError = null
            feedback = localizedText(ModuleText.FETCHING_LYRICS)
            host.worker.execute {
                val result = runCatching {
                    val importer = host.embeddedOnlineLyricsImporter()
                    when (sourceToImport) {
                        EmbeddedOnlineSource.AMLL -> importer.importAmll(appleMusicId)
                        EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(appleMusicId)
                        EmbeddedOnlineSource.LUNABEAT -> importer.importLunabeat(appleMusicId)
                    }
                }.getOrElse {
                    CustomLyricsOnlineImportResult.Failed(
                        it.message.orEmpty().ifBlank { failureFallback },
                    )
                }
                host.mainHandler.post {
                    if (!editorOpen ||
                        revision != onlineImportRevision ||
                        activity.isFinishing ||
                        activity.isDestroyed ||
                        host.currentActivity() !== activity
                    ) {
                        return@post
                    }
                    when (result) {
                        is CustomLyricsOnlineImportResult.Imported -> {
                            ttml = result.ttml
                            ttmlError = null
                            source = result.source
                            val reformatNote = if (result.reformatted) {
                                localizedText(ModuleText.CONVERTED_TO_AM_FORMAT)
                            } else {
                                ""
                            }
                            feedback = localizedText(
                                ModuleText.ONLINE_LYRICS_IMPORTED,
                                host.embeddedLyricsSourceName(result.source),
                                reformatNote,
                            )
                        }
                        is CustomLyricsOnlineImportResult.Failed -> {
                            feedback = result.message
                        }
                    }
                }
            }
        }

        fun importTtml() {
            if (!editorOpen) return
            onlineImportRevision += 1
            feedback = null
            val revision = ++ttmlImportRevision
            val importCallback: (String?) -> Unit = { imported ->
                if (editorOpen &&
                    revision == ttmlImportRevision &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    host.currentActivity() === activity
                ) {
                    if (imported == null) {
                        feedback = localizedText(ModuleText.SELECTED_TTML_INVALID)
                    } else {
                        ttml = imported
                        ttmlError = null
                        source = CustomLyricsSources.MANUAL
                        feedback = localizedText(ModuleText.TTML_IMPORTED)
                    }
                }
            }
            editorPendingTtmlImport = importCallback
            pendingTtmlImport = importCallback
            host.launchSafPicker(
                activity,
                EmbeddedSafOperation.EditorTtml,
                "application/xml",
                arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
            )
        }

        val songTitle = currentSong?.title?.takeIf(String::isNotBlank)
            ?: if (currentSong == null) {
                localizedText(ModuleText.NO_CURRENT_SONG)
            } else {
                localizedText(ModuleText.UNKNOWN_TITLE)
            }
        val songMeta = currentSong?.let { details ->
            listOfNotNull(
                details.artist?.takeIf(String::isNotBlank),
                "#${details.appleMusicId}",
            ).joinToString(" · ")
        }.orEmpty().ifBlank { localizedText(ModuleText.MUSIC_ID_PLAY_HINT) }

        EmbeddedSettingsSection(title = localizedText(ModuleText.CURRENT_SONG))
        EmbeddedSettingsGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(EmbeddedPreferencePadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmbeddedSettingsIcon(
                        icon = EmbeddedSvgIcon.Music,
                        modifier = Modifier.size(28.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = songTitle,
                            style = MiuixTheme.textStyles.headline1,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = songMeta,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            EmbeddedSettingLink(
                title = localizedText(ModuleText.GET_ID),
                icon = EmbeddedSvgIcon.Refresh,
                onClick = ::captureCurrentSong,
            )
        }

        EmbeddedSettingsSection(title = "Apple Music ID")
        EmbeddedSettingsGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(EmbeddedPreferencePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = appleMusicIds,
                    onValueChange = {
                        appleMusicIds = it
                        idError = null
                        feedback = null
                        onlineImportRevision += 1
                        ttmlImportRevision += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Apple Music ID",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                idError?.let { message ->
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                parsedIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                    Text(
                        text = localizedText(ModuleText.LYRICS_ID_COUNT, ids.size)
                            .trimStart(' ', '·'),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                TextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        feedback = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = localizedText(ModuleText.DISPLAY_NAME),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
            }
        }

        EmbeddedSettingsSection(title = localizedText(ModuleText.IMPORT))
        EmbeddedSettingsGroup {
            EmbeddedSettingLink(
                title = localizedText(ModuleText.IMPORT_TTML),
                icon = EmbeddedSvgIcon.ImportTtml,
                onClick = ::importTtml,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            OverlayDropdownMenu(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = localizedText(ModuleText.IMPORT_FROM_AMLL),
                            onClick = { importOnline(EmbeddedOnlineSource.AMLL) },
                        ),
                        DropdownItem(
                            text = localizedText(ModuleText.IMPORT_FROM_LUNABEAT),
                            onClick = { importOnline(EmbeddedOnlineSource.LUNABEAT) },
                        ),
                        DropdownItem(
                            text = localizedText(ModuleText.IMPORT_FROM_AM_LYRICS),
                            onClick = { importOnline(EmbeddedOnlineSource.AM_LYRICS) },
                        ),
                    ),
                ),
                title = localizedText(ModuleText.IMPORT),
                summary = listOf(
                    "AMLL",
                    "Lunabeat",
                    localizedText(ModuleText.AM_LYRICS_REPOSITORY),
                ).joinToString(" · "),
                startAction = {
                    EmbeddedSettingsIcon(
                        EmbeddedSvgIcon.DocumentSearch,
                        modifier = Modifier.size(24.dp),
                    )
                },
                insideMargin = EmbeddedPreferencePadding,
            )
        }

        EmbeddedSettingsSection(
            title = localizedText(ModuleText.TTML_CONTENT),
            summary = localizedText(
                ModuleText.CURRENT_SOURCE,
                host.embeddedLyricsSourceName(source),
            ),
        )
        EmbeddedSettingsGroup {
            Column(
                modifier = Modifier.fillMaxWidth().padding(EmbeddedPreferencePadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextField(
                    value = ttml,
                    onValueChange = {
                        ttml = it
                        ttmlError = null
                        feedback = null
                        onlineImportRevision += 1
                        ttmlImportRevision += 1
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp),
                    label = localizedText(ModuleText.TTML_CONTENT),
                    useLabelAsPlaceholder = true,
                    minLines = 4,
                    maxLines = Int.MAX_VALUE,
                )
                ttmlError?.let { message ->
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
    }
}

/** Compatibility overload for callers that already hold one lyrics entry. */
internal fun EmbeddedSettingsHost.showLyricsEditor(
    activity: Activity,
    entry: CustomLyricsEntry?,
    song: CurrentSongDetails?,
) = showLyricsEditor(
    activity,
    entry?.let { CustomLyricsUiGroup(listOf(it)) },
    song,
)

/** Keeps the editor's multi-ID operation explicit at the host boundary. */
internal fun EmbeddedSettingsHost.saveMany(
    draft: CustomLyricsMultiIdDraft,
    replacingAppleMusicIds: List<Long>,
): EmbeddedActionResult = controller.saveLyrics(draft, replacingAppleMusicIds)

internal fun EmbeddedSettingsHost.embeddedOnlineLyricsImporter(): CustomLyricsOnlineImporter =
    dev.amenhancer.module.lyrics.createEmbeddedOnlineLyricsImporter(application)

internal fun EmbeddedSettingsHost.embeddedLyricsSourceName(source: String): String = when (source) {
    CustomLyricsSources.AUTO_CACHE -> localizedText(ModuleText.AUTO_CACHE)
    CustomLyricsSources.AMLL -> "AMLL"
    CustomLyricsSources.AM_LYRICS -> localizedText(ModuleText.AM_LYRICS_REPOSITORY)
    CustomLyricsSources.LUNABEAT -> "Lunabeat"
    else -> localizedText(ModuleText.MANUAL_TTML)
}
