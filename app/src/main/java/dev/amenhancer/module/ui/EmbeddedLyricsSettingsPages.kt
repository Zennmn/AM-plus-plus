package dev.amenhancer.module.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun EmbeddedSettingsHost.EmbeddedCustomLyricsPage(
    activity: Activity,
    settings: ModuleSettings,
    revision: Int,
    onSettingsChanged: (ModuleSettings) -> Unit,
    onOpenPage: (EmbeddedSettingsPage) -> Unit,
) {
    val entries = remember(revision) {
        runCatching { controller.lyricsEntries() }.getOrDefault(emptyList())
    }
    val song = remember(revision) {
        runCatching { controller.currentSongDetails() }.getOrNull()
    }
    val lyricsState = customLyricsListState
    var searchText by remember { mutableStateOf(customLyricsSearchQuery) }
    var listRevision by remember { mutableIntStateOf(0) }
    val currentSettings by rememberUpdatedState(settings)
    val settingsChanged by rememberUpdatedState(onSettingsChanged)
    val focusManager = LocalFocusManager.current
    val visibleGroups = remember(entries, searchText, listRevision) {
        lyricsState.update(entries, searchText)
        lyricsState.visibleGroups
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmbeddedSettingsGroup {
            EmbeddedSettingSwitch(
                title = localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT),
                summary = localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT_SUMMARY),
                checked = settings.customLyricsEnabled,
                icon = EmbeddedSvgIcon.CustomLyrics,
                onCheckedChange = { enabled ->
                    settingsChanged(currentSettings.copy(customLyricsEnabled = enabled))
                    pageRefresh?.invoke()
                },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.AUTO_LYRICS),
                summary = localizedText(ModuleText.SHORT_AUTO_LYRICS_SUMMARY),
                icon = EmbeddedSvgIcon.AutomaticLyrics,
                onClick = { onOpenPage(EmbeddedSettingsPage.AUTOMATIC_LYRICS) },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.LLM_LYRICS_FALLBACK),
                summary = localizedText(ModuleText.SHORT_LLM_LYRICS_SUMMARY),
                icon = EmbeddedSvgIcon.Translation,
                onClick = { onOpenPage(EmbeddedSettingsPage.LLM_LYRICS) },
            )
        }

        EmbeddedSettingsGroup {
            CurrentSongRow(song)
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.ADD_LYRICS),
                icon = EmbeddedSvgIcon.AddLyrics,
                onClick = { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.IMPORT_TTML),
                icon = EmbeddedSvgIcon.ImportTtml,
                onClick = {
                    launchSafPicker(
                        activity,
                        EmbeddedSafOperation.Ttml,
                        "application/xml",
                        arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                    )
                },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.LYRICS_UPDATE),
                icon = EmbeddedSvgIcon.Refresh,
                onClick = { updateEmbeddedLyrics(activity) },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.BACKUP_AND_RESTORE),
                icon = EmbeddedSvgIcon.BackupRestore,
                onClick = { showBackupRestoreDialog(activity) },
            )
        }

        EmbeddedSettingsGroup {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextField(
                    value = searchText,
                    onValueChange = { value ->
                        searchText = value
                        customLyricsSearchQuery = value
                        lyricsState.setQuery(value)
                        listRevision++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = localizedText(ModuleText.SEARCH_LYRICS),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    leadingIcon = {
                        EmbeddedSettingsIcon(
                            icon = EmbeddedSvgIcon.Search,
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
            }
        }

        EmbeddedSettingsSection(
            title = localizedText(ModuleText.CONFIGURED),
            summary = localizedText(ModuleText.SONG_COUNT, entries.size),
        )

        EmbeddedSettingsGroup {
            if (lyricsState.totalCount == 0) {
                EmptyLyricsState(
                    hasEntries = entries.isNotEmpty(),
                )
            } else {
                visibleGroups.forEachIndexed { index, group ->
                    key(group.appleMusicIds) {
                        CustomLyricsEntryRow(
                            group = group,
                            song = song,
                            onEnabledChange = { enabled ->
                                runAsync(activity) {
                                    controller.setLyricsEnabled(group.appleMusicIds, enabled)
                                }
                            },
                            onOpenActions = {
                                showLyricsActionsDialog(activity, group, song)
                            },
                        )
                    }
                    if (index < visibleGroups.lastIndex) SettingsDivider()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = localizedText(
                            ModuleText.LYRICS_VISIBLE_COUNT,
                            lyricsState.visibleCount,
                            lyricsState.totalCount,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (lyricsState.hasMore) {
                    TextButton(
                        text = localizedText(ModuleText.LOAD_MORE),
                        onClick = {
                            lyricsState.loadMore()
                            listRevision++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedAutomaticLyricsPage(
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    val normalizedOrder = remember(settings.automaticLyricsSourceOrder) {
        ModuleSettings.normalizeAutomaticLyricsSourceOrder(settings.automaticLyricsSourceOrder)
    }
    val initialVisibleSources = remember(normalizedOrder, settings.automaticLyricsPreferNative) {
        visibleAutomaticLyricsSources(
            order = normalizedOrder,
            preferNative = settings.automaticLyricsPreferNative,
        )
    }
    var visibleSources by remember(initialVisibleSources) {
        mutableStateOf(initialVisibleSources)
    }
    var nativeIndex by remember(normalizedOrder) {
        mutableIntStateOf(normalizedOrder.indexOf(CustomLyricsSources.AM_NATIVE))
    }
    var dragStartOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggingSource by remember { mutableStateOf<String?>(null) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }

    val sourceCenters = remember { mutableStateMapOf<String, Float>() }
    val haptics = LocalHapticFeedback.current
    val currentSettings by rememberUpdatedState(settings)
    val settingsChanged by rememberUpdatedState(onSettingsChanged)

    fun orderWithNative(sources: List<String>, nativePosition: Int): List<String> {
        if (!currentSettings.automaticLyricsPreferNative) return sources.toList()
        val nextSources = sources.toMutableList()
        // Native is hidden in this mode, but its stored position must remain
        // ahead of Lunabeat so the persisted order stays valid.
        val lunabeatIndex = nextSources.indexOf(CustomLyricsSources.LUNABEAT)
        val maximumNativeIndex = lunabeatIndex.takeIf { it >= 0 } ?: nextSources.size
        nextSources.add(nativePosition.coerceIn(0, maximumNativeIndex), CustomLyricsSources.AM_NATIVE)
        return nextSources
    }

    fun canMoveIn(order: List<String>, from: Int, to: Int): Boolean {
        if (from !in order.indices || to !in order.indices || from == to) return false
        val nextSources = order.toMutableList().apply { add(to, removeAt(from)) }
        val fullOrder = orderWithNative(nextSources, nativeIndex)
        return ModuleSettings.normalizeAutomaticLyricsSourceOrder(fullOrder) == fullOrder
    }

    fun canMoveSource(from: Int, to: Int): Boolean = canMoveIn(visibleSources, from, to)

    fun persistSourceOrder() {
        val nextOrder = ModuleSettings.normalizeAutomaticLyricsSourceOrder(
            orderWithNative(visibleSources, nativeIndex),
        )
        nativeIndex = nextOrder.indexOf(CustomLyricsSources.AM_NATIVE)
        settingsChanged(currentSettings.copy(automaticLyricsSourceOrder = nextOrder))
    }

    fun moveSource(from: Int, to: Int): Boolean {
        if (!canMoveSource(from, to)) return false
        visibleSources = visibleSources.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun startDrag(source: String) {
        if (dragStartOrder != null) return
        dragStartOrder = visibleSources.toList()
        draggingSource = source
        dragDistancePx = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(source: String, deltaY: Float) {
        if (draggingSource != source) return
        dragDistancePx += deltaY
    }

    fun dropTargetIndex(source: String, distancePx: Float, order: List<String>): Int {
        val from = order.indexOf(source)
        if (from < 0) return -1
        val startCenter = sourceCenters[source] ?: return from
        if (distancePx == 0f) return from
        val targetCenter = startCenter + distancePx
        val direction = if (distancePx > 0f) 1 else -1
        var workingOrder = order
        var position = from
        while (true) {
            val next = position + direction
            if (next !in order.indices) break
            val currentCenter = sourceCenters[order[position]] ?: break
            val nextCenter = sourceCenters[order[next]] ?: break
            val midpoint = (currentCenter + nextCenter) / 2f
            if ((targetCenter - midpoint) * direction < 0 || !canMoveIn(workingOrder, position, next)) break
            workingOrder = workingOrder.toMutableList().apply { add(next, removeAt(position)) }
            position = next
        }
        return position
    }

    fun finishDrag(commit: Boolean) {
        val initialOrder = dragStartOrder ?: return
        val source = draggingSource
        val targetIndex = source?.let { dropTargetIndex(it, dragDistancePx, initialOrder) }
        dragStartOrder = null
        draggingSource = null
        dragDistancePx = 0f
        if (commit && source != null && targetIndex != null) {
            val from = initialOrder.indexOf(source)
            if (from < 0 || targetIndex !in initialOrder.indices || from == targetIndex) return
            visibleSources = initialOrder.toMutableList().apply {
                add(targetIndex, removeAt(from))
            }
            persistSourceOrder()
        }
    }

    fun moveForAccessibility(source: String, offset: Int): Boolean {
        if (dragStartOrder != null) return false
        val from = visibleSources.indexOf(source)
        if (from < 0 || !moveSource(from, from + offset)) return false
        persistSourceOrder()
        return true
    }

    fun resetSourceOrder() {
        if (dragStartOrder != null) return
        val nextOrder = ModuleSettings.normalizeAutomaticLyricsSourceOrder(
            ModuleSettings.DEFAULT_AUTOMATIC_LYRICS_SOURCE_ORDER,
        )
        nativeIndex = nextOrder.indexOf(CustomLyricsSources.AM_NATIVE)
        visibleSources = visibleAutomaticLyricsSources(
            order = nextOrder,
            preferNative = currentSettings.automaticLyricsPreferNative,
        )
        settingsChanged(currentSettings.copy(automaticLyricsSourceOrder = nextOrder))
        pageRefresh?.invoke()
    }

    DisposableEffect(Unit) {
        onDispose {
            dragStartOrder = null
            draggingSource = null
            dragDistancePx = 0f
        }
    }

    val sourceSummary = buildString {
        append(
            localizedText(
                if (settings.automaticLyricsPreferNative) {
                    ModuleText.AUTO_LYRICS_SOURCE_ORDER_NATIVE_HIDDEN
                } else {
                    ModuleText.AUTO_LYRICS_SOURCE_ORDER_ALL
                },
            ),
        )
        append('\n')
        append(localizedText(ModuleText.DRAG_TO_REORDER))
        if (!settings.automaticLyricsPreferNative) {
            append('\n')
            append(localizedText(ModuleText.LUNABEAT_AFTER_NATIVE))
        }
    }

    val dragTargetIndex = draggingSource?.let { source ->
        dropTargetIndex(source, dragDistancePx, dragStartOrder ?: visibleSources)
    } ?: -1

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmbeddedSettingsGroup {
            EmbeddedSettingSwitch(
                title = localizedText(ModuleText.AUTO_LYRICS_ENABLED),
                summary = localizedText(ModuleText.AUTO_LYRICS_SUMMARY),
                checked = settings.automaticLyricsEnabled,
                enabled = settings.customLyricsEnabled,
                icon = EmbeddedSvgIcon.AutomaticLyrics,
                onCheckedChange = { enabled ->
                    settingsChanged(currentSettings.copy(automaticLyricsEnabled = enabled))
                    pageRefresh?.invoke()
                },
            )
            SettingsDivider()
            EmbeddedSettingSwitch(
                title = localizedText(ModuleText.AUTO_LYRICS_PREFER_NATIVE),
                summary = localizedText(ModuleText.AUTO_LYRICS_PREFER_NATIVE_SUMMARY),
                checked = settings.automaticLyricsPreferNative,
                icon = EmbeddedSvgIcon.Music,
                onCheckedChange = { preferNative ->
                    settingsChanged(currentSettings.copy(automaticLyricsPreferNative = preferNative))
                    pageRefresh?.invoke()
                },
            )
        }

        EmbeddedSettingsSection(
            title = localizedText(ModuleText.AUTO_LYRICS_SOURCE_ORDER),
            summary = sourceSummary,
        ) {
            EmbeddedSettingsIconAction(
                EmbeddedSvgIcon.RestoreDefault,
                localizedText(ModuleText.RESET_DEFAULT),
                onClick = ::resetSourceOrder,
            )
        }

        EmbeddedSettingsGroup {
            visibleSources.forEachIndexed { index, source ->
                key(source) {
                    AutomaticLyricsSourceRow(
                        source = source,
                        sourceName = automaticLyricsSourceName(source),
                        position = index,
                        dragging = draggingSource == source,
                        dropTarget = dragTargetIndex == index && draggingSource != source,
                        canMoveUp = canMoveSource(index, index - 1),
                        canMoveDown = canMoveSource(index, index + 1),
                        onCenterPositioned = { center -> sourceCenters[source] = center },
                        onStartDrag = { startDrag(source) },
                        onDrag = { deltaY -> dragBy(source, deltaY) },
                        onFinishDrag = ::finishDrag,
                        onMoveForAccessibility = { offset -> moveForAccessibility(source, offset) },
                    )
                }
                if (index < visibleSources.lastIndex) SettingsDivider()
            }
        }
    }
}

@Composable
private fun EmbeddedSettingsHost.CurrentSongRow(song: CurrentSongDetails?) {
    val title = song?.let {
        localizedText(
            ModuleText.CURRENT_SONG_TITLE,
            it.title.orEmpty().ifBlank { localizedText(ModuleText.UNKNOWN_TITLE) },
        )
    } ?: localizedText(ModuleText.CURRENT_SONG_NOT_CAPTURED)
    val summary = song?.let {
        "Apple Music ID: ${it.appleMusicId}"
    } ?: localizedText(ModuleText.MUSIC_ID_PLAY_HINT)

    BasicComponent(
        title = title,
        summary = summary,
        startAction = {
            EmbeddedSettingsIcon(
                icon = EmbeddedSvgIcon.Music,
                modifier = Modifier.size(24.dp),
            )
        },
        insideMargin = EmbeddedPreferencePadding,
    )
}

@Composable
private fun EmbeddedSettingsHost.CustomLyricsEntryRow(
    group: CustomLyricsUiGroup,
    song: CurrentSongDetails?,
    onEnabledChange: (Boolean) -> Unit,
    onOpenActions: () -> Unit,
) {
    val entry = group.primary
    val artist = song
        ?.takeIf { it.appleMusicId == entry.appleMusicId }
        ?.artist
        ?.takeIf(String::isNotBlank)
    val secondary = buildString {
        artist?.let {
            append(it)
            append(" · ")
        }
        append("AM ID: ")
        append(entry.appleMusicId)
        if (group.entries.size > 1) {
            append(localizedText(ModuleText.LYRICS_ID_COUNT, group.entries.size))
        }
    }
    val title = entry.displayName.ifBlank { entry.appleMusicId.toString() }
    val switchDescription = localizedText(ModuleText.LYRICS_TOGGLE_ACCESSIBILITY, title)
    val moreDescription = localizedText(ModuleText.MORE_LYRICS_ACTIONS)

    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        summary = secondary,
        startAction = {
            EmbeddedSettingsIcon(
                icon = EmbeddedSvgIcon.CustomLyrics,
                modifier = Modifier.size(24.dp),
            )
        },
        endActions = {
            Switch(
                checked = group.allEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
            IconButton(
                onClick = onOpenActions,
            ) {
                EmbeddedSettingsIcon(
                    icon = EmbeddedSvgIcon.MoreVertical,
                    contentDescription = moreDescription,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
        insideMargin = EmbeddedPreferencePadding,
    )
}

@Composable
private fun EmbeddedSettingsHost.EmptyLyricsState(hasEntries: Boolean) {
    BasicComponent(
        title = localizedText(
            if (hasEntries) ModuleText.NO_SEARCH_RESULTS else ModuleText.NO_CUSTOM_LYRICS,
        ),
        summary = localizedText(
            if (hasEntries) ModuleText.SEARCH_LYRICS_EMPTY_HINT else ModuleText.ADD_LYRICS_EMPTY_HINT,
        ),
        startAction = {
            EmbeddedSettingsIcon(
                icon = if (hasEntries) EmbeddedSvgIcon.DocumentSearch else EmbeddedSvgIcon.CustomLyrics,
                modifier = Modifier.size(24.dp),
            )
        },
        insideMargin = EmbeddedPreferencePadding,
    )
}

@Composable
private fun EmbeddedSettingsHost.AutomaticLyricsSourceRow(
    source: String,
    sourceName: String,
    position: Int,
    dragging: Boolean,
    dropTarget: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCenterPositioned: (Float) -> Unit,
    onStartDrag: () -> Unit,
    onDrag: (Float) -> Unit,
    onFinishDrag: (Boolean) -> Unit,
    onMoveForAccessibility: (Int) -> Boolean,
) {
    val currentStartDrag by rememberUpdatedState(onStartDrag)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentFinishDrag by rememberUpdatedState(onFinishDrag)
    val currentMoveForAccessibility by rememberUpdatedState(onMoveForAccessibility)
    val dragDescription = localizedText(ModuleText.DRAG_SOURCE, sourceName)
    val moveUp = localizedText(ModuleText.MOVE_UP)
    val moveDown = localizedText(ModuleText.MOVE_DOWN)
    val moveActions = buildList {
        if (canMoveUp) {
            add(CustomAccessibilityAction(moveUp) { currentMoveForAccessibility(-1) })
        }
        if (canMoveDown) {
            add(CustomAccessibilityAction(moveDown) { currentMoveForAccessibility(1) })
        }
    }

    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onCenterPositioned(it.positionInRoot().y + it.size.height / 2f) }
            .graphicsLayer { alpha = if (dragging) 0.72f else 1f },
        title = sourceName,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (dropTarget) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        ),
        startAction = {
            Text(
                text = (position + 1).toString(),
                modifier = Modifier.width(28.dp),
                style = MiuixTheme.textStyles.body2,
                color = if (dropTarget) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        },
        endActions = {
            IconButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .size(44.dp)
                    .semantics {
                        contentDescription = dragDescription
                        customActions = moveActions
                    }
                    .focusable()
                    .pointerInput(source) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            currentStartDrag()
                            var completed = false
                            var previousY = down.position.y
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                    if (!change.pressed) {
                                        change.consume()
                                        currentFinishDrag(true)
                                        completed = true
                                        break
                                    }
                                    val deltaY = change.position.y - previousY
                                    previousY = change.position.y
                                    change.consume()
                                    if (deltaY != 0f) currentDrag(deltaY)
                                }
                            } finally {
                                if (!completed) currentFinishDrag(false)
                            }
                        }
                    },
            ) {
                EmbeddedSettingsIcon(
                    icon = EmbeddedSvgIcon.DragHandle,
                    modifier = Modifier.size(24.dp),
                    tint = if (dropTarget) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
        insideMargin = EmbeddedPreferencePadding,
        holdDownState = dragging,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

private fun EmbeddedSettingsHost.showLyricsActionsDialog(
    activity: Activity,
    group: CustomLyricsUiGroup,
    song: CurrentSongDetails?,
) {
    val entry = group.primary
    var afterDismiss: (() -> Unit)? = null
    showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(ModuleText.MORE_LYRICS_ACTIONS),
        onDismiss = {
            val action = afterDismiss
            afterDismiss = null
            if (!activity.isFinishing && !activity.isDestroyed && currentActivity() === activity) {
                action?.invoke()
            }
        },
    ) { dismiss ->
        EmbeddedSettingsGroup {
            EmbeddedSettingLink(
                title = localizedText(ModuleText.EDIT),
                summary = entry.displayName.ifBlank { entry.appleMusicId.toString() },
                icon = EmbeddedSvgIcon.Edit,
                onClick = {
                    afterDismiss = { showLyricsEditor(activity, group, song) }
                    dismiss()
                },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.COPY_MUSIC_ID),
                summary = group.appleMusicIds.joinToString(","),
                icon = EmbeddedSvgIcon.Document,
                onClick = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Apple Music ID", group.appleMusicIds.joinToString(",")),
                    )
                    Toast.makeText(activity, localizedText(ModuleText.MUSIC_ID_COPIED), Toast.LENGTH_SHORT).show()
                    dismiss()
                },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.DELETE),
                icon = EmbeddedSvgIcon.Delete,
                onClick = {
                    afterDismiss = { showDeleteLyricsDialog(activity, group) }
                    dismiss()
                },
            )
        }
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = dismiss,
        )
    }
}

private fun EmbeddedSettingsHost.showDeleteLyricsDialog(
    activity: Activity,
    group: CustomLyricsUiGroup,
) {
    val entry = group.primary
    var confirmed = false
    showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(ModuleText.DELETE),
        onDismiss = {
            if (confirmed && !activity.isFinishing && !activity.isDestroyed && currentActivity() === activity) {
                runAsync(activity) { controller.deleteLyrics(group.appleMusicIds) }
            }
        },
    ) { dismiss ->
        EmbeddedSettingsNotice(
            localizedText(
                ModuleText.DELETE_LYRICS_CONFIRM,
                entry.displayName.ifBlank { entry.appleMusicId.toString() },
                group.entries.size,
            ),
        )
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = dismiss,
            confirmText = localizedText(ModuleText.DELETE),
            onConfirm = {
                confirmed = true
                dismiss()
            },
        )
    }
}

private fun EmbeddedSettingsHost.showBackupRestoreDialog(activity: Activity) {
    var afterDismiss: (() -> Unit)? = null
    showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(ModuleText.BACKUP_AND_RESTORE),
        onDismiss = {
            val action = afterDismiss
            afterDismiss = null
            if (!activity.isFinishing && !activity.isDestroyed && currentActivity() === activity) {
                action?.invoke()
            }
        },
    ) { dismiss ->
        EmbeddedSettingsGroup {
            EmbeddedSettingLink(
                title = localizedText(ModuleText.BACKUP_LYRICS),
                icon = EmbeddedSvgIcon.BackupRestore,
                onClick = {
                    afterDismiss = {
                        launchSafPicker(activity, EmbeddedSafOperation.Backup, "application/zip")
                    }
                    dismiss()
                },
            )
            SettingsDivider()
            EmbeddedSettingLink(
                title = localizedText(ModuleText.RESTORE_BACKUP),
                icon = EmbeddedSvgIcon.BackupRestore,
                onClick = {
                    afterDismiss = {
                        launchSafPicker(
                            activity = activity,
                            operation = EmbeddedSafOperation.RestoreOverwrite,
                            mimeType = "*/*",
                            extraMimeTypes = arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            ),
                        )
                    }
                    dismiss()
                },
            )
        }
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = dismiss,
        )
    }
}

private fun EmbeddedSettingsHost.automaticLyricsSourceName(source: String): String = when (source) {
    CustomLyricsSources.AUTO_CACHE -> localizedText(ModuleText.AUTO_CACHE)
    CustomLyricsSources.AMLL -> "AMLL"
    CustomLyricsSources.AM_LYRICS -> localizedText(ModuleText.AM_LYRICS_REPOSITORY)
    CustomLyricsSources.LUNABEAT -> "Lunabeat"
    CustomLyricsSources.AM_NATIVE -> localizedText(ModuleText.AM_NATIVE)
    else -> localizedText(ModuleText.MANUAL_TTML)
}

private fun visibleAutomaticLyricsSources(
    order: List<String>,
    preferNative: Boolean,
): List<String> = if (preferNative) {
    order.filterNot { it == CustomLyricsSources.AM_NATIVE }
} else {
    order
}
