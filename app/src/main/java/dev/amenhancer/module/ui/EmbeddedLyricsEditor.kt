package dev.amenhancer.module.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
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
        if (!editorOpen || saveSubmitted) return
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
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                feedback?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = localizedText(ModuleText.CANCEL),
                        onClick = {
                            closeEditor()
                            dismiss()
                        },
                        modifier = Modifier.weight(1f),
                        minHeight = 48.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    )
                    TextButton(
                        text = localizedText(ModuleText.SAVE),
                        onClick = { saveLyrics(dismiss) },
                        modifier = Modifier.weight(1f),
                        enabled = !saveSubmitted,
                        minHeight = 48.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
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
            if (!editorOpen) return
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

        val songSummary = currentSong?.let { details ->
            val label = listOfNotNull(
                details.title?.takeIf(String::isNotBlank),
                details.artist?.takeIf(String::isNotBlank),
            ).joinToString(" · ").ifBlank { "#${details.appleMusicId}" }
            localizedText(ModuleText.CURRENT_SONG_TITLE, label)
        } ?: localizedText(ModuleText.NO_CURRENT_SONG)
        val idHint = idError ?: parsedIds?.takeIf { it.size > 1 }?.let { ids ->
            localizedText(ModuleText.LYRICS_ID_COUNT, ids.size).trimStart(' ', '·')
        }
        val onlineImportText = localizedText(ModuleText.IMPORT_ONLINE)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmbeddedSettingsIcon(EmbeddedSvgIcon.Music, modifier = Modifier.size(20.dp))
                Text(
                    text = songSummary,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    text = localizedText(ModuleText.GET_ID),
                    onClick = ::captureCurrentSong,
                    enabled = !saveSubmitted,
                    minHeight = 48.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    textStyle = MiuixTheme.textStyles.body2,
                    colors = ButtonDefaults.textButtonColors(
                        color = Color.Transparent,
                        textColor = MiuixTheme.colorScheme.primary,
                    ),
                )
            }
            TextField(
                value = appleMusicIds,
                onValueChange = {
                    appleMusicIds = it
                    idError = null
                    feedback = null
                    onlineImportRevision += 1
                    ttmlImportRevision += 1
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                insideMargin = DpSize(14.dp, 12.dp),
                label = "Apple Music ID",
                singleLine = true,
            )
            idHint?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = if (idError == null) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                    style = MiuixTheme.textStyles.body2,
                )
            }
            TextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    feedback = null
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                insideMargin = DpSize(14.dp, 12.dp),
                label = localizedText(ModuleText.DISPLAY_NAME),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = localizedText(ModuleText.IMPORT_TTML),
                    onClick = ::importTtml,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = !saveSubmitted,
                    minHeight = 48.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = MiuixTheme.textStyles.body2,
                )
                OverlayIconDropdownMenu(
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
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = !saveSubmitted,
                    backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
                    cornerRadius = ButtonDefaults.CornerRadius,
                    minHeight = 48.dp,
                    minWidth = 48.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = onlineImportText,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSecondaryVariant,
                        )
                        DropdownArrowEndAction(MiuixTheme.colorScheme.onSecondaryVariant)
                    }
                }
            }
            TextField(
                value = ttml,
                onValueChange = {
                    ttml = it
                    ttmlError = null
                    feedback = null
                    onlineImportRevision += 1
                    ttmlImportRevision += 1
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 148.dp),
                insideMargin = DpSize(14.dp, 12.dp),
                label = localizedText(ModuleText.TTML_CONTENT),
                useLabelAsPlaceholder = true,
                textStyle = MiuixTheme.textStyles.body2,
                minLines = 4,
                maxLines = Int.MAX_VALUE,
            )
            Text(
                text = ttmlError ?: localizedText(
                    ModuleText.CURRENT_SOURCE,
                    host.embeddedLyricsSourceName(source),
                ),
                modifier = Modifier.padding(horizontal = 14.dp),
                color = if (ttmlError == null) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.error
                },
                style = MiuixTheme.textStyles.body2,
            )
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
