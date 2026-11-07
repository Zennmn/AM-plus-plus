package dev.amenhancer.module.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.zIndex
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.VerticalDivider
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
    val lyricsControlPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LyricsActionButton(
                    title = localizedText(ModuleText.ADD_LYRICS),
                    shortLabel = localizedText(ModuleText.ADD),
                    icon = EmbeddedSvgIcon.AddLyrics,
                    onClick = { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider(Modifier.height(28.dp))
                LyricsActionButton(
                    title = localizedText(ModuleText.IMPORT_TTML),
                    shortLabel = "TTML",
                    icon = EmbeddedSvgIcon.ImportTtml,
                    onClick = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider(Modifier.height(28.dp))
                LyricsActionButton(
                    title = localizedText(ModuleText.LYRICS_UPDATE),
                    shortLabel = localizedText(ModuleText.UPDATE),
                    icon = EmbeddedSvgIcon.Refresh,
                    onClick = { updateEmbeddedLyrics(activity) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VerticalDivider(Modifier.height(28.dp))
                LyricsActionButton(
                    title = localizedText(ModuleText.BACKUP_AND_RESTORE),
                    shortLabel = localizedText(ModuleText.BACKUP),
                    icon = EmbeddedSvgIcon.BackupRestore,
                    onClick = { showBackupRestoreDialog(activity) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        EmbeddedSettingsSection(
            title = localizedText(ModuleText.CONFIGURED),
            summary = localizedText(ModuleText.SONG_COUNT, entries.size),
        )

        EmbeddedSettingsGroup {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(lyricsControlPadding),
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
            SettingsDivider()
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(lyricsControlPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = localizedText(
                            ModuleText.LYRICS_VISIBLE_COUNT,
                            lyricsState.visibleCount,
                            lyricsState.totalCount,
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (lyricsState.hasMore) {
                        TextButton(
                            text = localizedText(ModuleText.LOAD_MORE),
                            onClick = {
                                lyricsState.loadMore()
                                listRevision++
                            },
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = TextFieldDefaults.CornerRadius,
                            insideMargin = PaddingValues(
                                horizontal = TextFieldDefaults.InsideMargin.width,
                                vertical = TextFieldDefaults.InsideMargin.height,
                            ),
                            textStyle = MiuixTheme.textStyles.main,
                            colors = ButtonDefaults.textButtonColors(
                                color = MiuixTheme.colorScheme.secondaryContainer,
                                textColor = MiuixTheme.colorScheme.onSurface,
                            ),
                        )
                    }
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
    var visibleSources by remember {
        mutableStateOf(initialVisibleSources)
    }
    var nativeIndex by remember {
        mutableIntStateOf(normalizedOrder.indexOf(CustomLyricsSources.AM_NATIVE))
    }
    var dragStartOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragStartCenters by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var draggingSource by remember { mutableStateOf<String?>(null) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragSettleJob by remember { mutableStateOf<Job?>(null) }
    var dragReleaseTargetIndex by remember { mutableIntStateOf(-1) }

    val sourceCenters = remember { mutableStateMapOf<String, Float>() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentSettings by rememberUpdatedState(settings)
    val settingsChanged by rememberUpdatedState(onSettingsChanged)

    LaunchedEffect(initialVisibleSources, normalizedOrder, dragStartOrder, dragSettleJob) {
        if (dragStartOrder == null && dragSettleJob == null) {
            visibleSources = initialVisibleSources
            nativeIndex = normalizedOrder.indexOf(CustomLyricsSources.AM_NATIVE)
        }
    }

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

    fun persistSourceOrder(orderSources: List<String> = visibleSources) {
        val nextOrder = ModuleSettings.normalizeAutomaticLyricsSourceOrder(
            orderWithNative(orderSources, nativeIndex),
        )
        if (dragStartOrder == null && dragSettleJob == null) {
            nativeIndex = nextOrder.indexOf(CustomLyricsSources.AM_NATIVE)
        }
        settingsChanged(currentSettings.copy(automaticLyricsSourceOrder = nextOrder))
    }

    fun moveSource(from: Int, to: Int): Boolean {
        if (!canMoveSource(from, to)) return false
        visibleSources = visibleSources.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun startDrag(source: String) {
        if (dragStartOrder != null || dragSettleJob != null) return
        if (source !in visibleSources || visibleSources.any { sourceCenters[it] == null }) return
        dragStartOrder = visibleSources.toList()
        dragStartCenters = visibleSources.associateWith { sourceCenters.getValue(it) }
        draggingSource = source
        dragDistancePx = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(source: String, deltaY: Float) {
        if (draggingSource != source || dragSettleJob != null) return
        val order = dragStartOrder ?: return
        val sourceCenter = dragStartCenters[source] ?: return
        val centers = order.mapNotNull(dragStartCenters::get).sorted()
        if (centers.isEmpty()) return
        dragDistancePx = (dragDistancePx + deltaY).coerceIn(
            minimumValue = centers.first() - sourceCenter,
            maximumValue = centers.last() - sourceCenter,
        )
    }

    fun dropTargetIndex(
        source: String,
        distancePx: Float,
        order: List<String>,
        centers: Map<String, Float>,
    ): Int {
        val from = order.indexOf(source)
        if (from < 0) return -1
        val startCenter = centers[source] ?: return from
        if (distancePx == 0f) return from
        val targetCenter = startCenter + distancePx
        val direction = if (distancePx > 0f) 1 else -1
        var workingOrder = order
        var position = from
        while (true) {
            val next = position + direction
            if (next !in order.indices) break
            val currentCenter = centers[order[position]] ?: break
            val nextCenter = centers[order[next]] ?: break
            val midpoint = (currentCenter + nextCenter) / 2f
            if ((targetCenter - midpoint) * direction < 0 || !canMoveIn(workingOrder, position, next)) break
            workingOrder = workingOrder.toMutableList().apply { add(next, removeAt(position)) }
            position = next
        }
        return position
    }

    fun finishDrag(commit: Boolean) {
        if (dragSettleJob != null) return
        val initialOrder = dragStartOrder ?: return
        val source = draggingSource ?: return
        val centers = dragStartCenters
        val targetIndex = dropTargetIndex(source, dragDistancePx, initialOrder, centers)
        val from = initialOrder.indexOf(source)
        val targetCenter = initialOrder.getOrNull(targetIndex)?.let(centers::get)
        val sourceCenter = centers[source]
        val committedOrder = if (commit &&
            from >= 0 &&
            targetIndex in initialOrder.indices &&
            from != targetIndex &&
            canMoveIn(initialOrder, from, targetIndex)
        ) {
            initialOrder.toMutableList().apply { add(targetIndex, removeAt(from)) }
        } else {
            null
        }
        val targetDistance = if (committedOrder != null && targetCenter != null && sourceCenter != null) {
            targetCenter - sourceCenter
        } else {
            0f
        }
        if (committedOrder != null) persistSourceOrder(committedOrder)
        dragReleaseTargetIndex = if (committedOrder != null) targetIndex else from

        dragSettleJob = scope.launch {
            try {
                animate(
                    initialValue = dragDistancePx,
                    targetValue = targetDistance,
                    animationSpec = tween(160),
                ) { value, _ ->
                    dragDistancePx = value
                }
                if (committedOrder != null) visibleSources = committedOrder
            } finally {
                dragSettleJob = null
                dragStartOrder = null
                dragStartCenters = emptyMap()
                draggingSource = null
                dragDistancePx = 0f
                dragReleaseTargetIndex = -1
            }
        }
    }

    fun moveForAccessibility(source: String, offset: Int): Boolean {
        if (dragStartOrder != null || dragSettleJob != null) return false
        val from = visibleSources.indexOf(source)
        if (from < 0 || !moveSource(from, from + offset)) return false
        persistSourceOrder()
        return true
    }

    fun resetSourceOrder() {
        if (dragStartOrder != null || dragSettleJob != null) return
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
            dragSettleJob?.cancel()
            dragSettleJob = null
            dragStartOrder = null
            dragStartCenters = emptyMap()
            draggingSource = null
            dragDistancePx = 0f
            dragReleaseTargetIndex = -1
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

    val activeDragOrder = dragStartOrder
    val dragTargetIndex = if (dragSettleJob != null) {
        dragReleaseTargetIndex
    } else if (draggingSource != null && activeDragOrder != null) {
        dropTargetIndex(
            draggingSource.orEmpty(),
            dragDistancePx,
            activeDragOrder,
            dragStartCenters,
        )
    } else {
        -1
    }

    fun dragYieldOffset(index: Int): Float {
        val source = draggingSource ?: return 0f
        val targetIndex = dragTargetIndex
        if (targetIndex < 0) return 0f
        val from = activeDragOrder?.indexOf(source) ?: return 0f
        if (from < 0 || index !in visibleSources.indices || index == from) return 0f
        val order = activeDragOrder
        return when {
            from < targetIndex && index in (from + 1)..targetIndex -> {
                val previous = dragStartCenters[order[index - 1]] ?: return 0f
                val current = dragStartCenters[order[index]] ?: return 0f
                previous - current
            }
            from > targetIndex && index in targetIndex until from -> {
                val next = dragStartCenters[order[index + 1]] ?: return 0f
                val current = dragStartCenters[order[index]] ?: return 0f
                next - current
            }
            else -> 0f
        }
    }

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
                    val isDragging = draggingSource == source
                    AutomaticLyricsSourceRow(
                        source = source,
                        sourceName = automaticLyricsSourceName(source),
                        position = index,
                        dragging = isDragging,
                        released = dragSettleJob != null,
                        offsetY = if (isDragging) dragDistancePx else dragYieldOffset(index),
                        animateOffset = dragStartOrder != null && !isDragging,
                        showDivider = index < visibleSources.lastIndex,
                        canMoveUp = canMoveSource(index, index - 1),
                        canMoveDown = canMoveSource(index, index + 1),
                        onCenterPositioned = { center -> sourceCenters[source] = center },
                        onStartDrag = { startDrag(source) },
                        onDrag = { deltaY -> dragBy(source, deltaY) },
                        onFinishDrag = ::finishDrag,
                        onMoveForAccessibility = { offset -> moveForAccessibility(source, offset) },
                    )
                }
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
private fun LyricsActionButton(
    title: String,
    shortLabel: String,
    icon: EmbeddedSvgIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = title
        },
        minWidth = 0.dp,
        minHeight = 68.dp,
        cornerRadius = 0.dp,
        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            color = Color.Transparent,
            contentColor = MiuixTheme.colorScheme.onBackground,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EmbeddedSettingsIcon(
                icon = icon,
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
            Text(
                text = shortLabel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
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
    released: Boolean,
    offsetY: Float,
    animateOffset: Boolean,
    showDivider: Boolean,
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
    val lifted = dragging && !released
    val animatedOffset = if (animateOffset) {
        val offset by animateFloatAsState(
            targetValue = offsetY,
            animationSpec = tween(160),
            label = "lyrics-source-yield",
        )
        offset
    } else {
        0f
    }
    val scale by animateFloatAsState(
        targetValue = if (lifted) 1.015f else 1f,
        animationSpec = tween(160),
        label = "lyrics-source-scale",
    )
    val liftColor by animateColorAsState(
        targetValue = if (lifted) MiuixTheme.colorScheme.secondaryContainerVariant else MiuixTheme.colorScheme.surfaceContainer,
        animationSpec = tween(160),
        label = "lyrics-source-lift-color",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (animateOffset) animatedOffset else offsetY },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleY = scale
                },
            shape = RoundedCornerShape(CardDefaults.CornerRadius),
            color = liftColor,
            // Match the reference's single colour/scale effect. A translucent surface
            // with a fading shadow lets its own dark shadow show through during release.
            shadowElevation = 0.dp,
        ) {
            BasicComponent(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        onCenterPositioned(it.positionInRoot().y + it.size.height / 2f)
                    },
                title = sourceName,
                titleColor = BasicComponentDefaults.titleColor(
                    color = if (lifted) MiuixTheme.colorScheme.onSecondaryContainerVariant
                        else MiuixTheme.colorScheme.onBackground,
                ),
                startAction = {
                    Text(
                        text = (position + 1).toString(),
                        modifier = Modifier.width(28.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = if (lifted) MiuixTheme.colorScheme.onSecondaryContainerVariant
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { currentStartDrag() },
                                    onDragEnd = { currentFinishDrag(true) },
                                    onDragCancel = { currentFinishDrag(false) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragAmount.y != 0f) currentDrag(dragAmount.y)
                                    },
                                )
                            },
                    ) {
                        EmbeddedSettingsIcon(
                            icon = EmbeddedSvgIcon.DragHandle,
                            modifier = Modifier.size(24.dp),
                            tint = if (lifted) MiuixTheme.colorScheme.onSecondaryContainerVariant
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                },
                insideMargin = EmbeddedPreferencePadding,
            )
        }
        // Keep the row footprint stable while the lifted row temporarily hides its separator.
        Box(Modifier.graphicsLayer { alpha = if (showDivider && !lifted) 1f else 0f }) {
            SettingsDivider()
        }
    }
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
