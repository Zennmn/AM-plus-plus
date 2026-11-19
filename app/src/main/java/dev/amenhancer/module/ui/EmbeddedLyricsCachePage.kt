package dev.amenhancer.module.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.config.LyricsCacheEntry
import dev.amenhancer.module.config.LyricsCacheSnapshot
import dev.amenhancer.module.config.cachedSongDisplayName
import dev.amenhancer.module.config.isCompleteCacheSongName
import dev.amenhancer.module.hook.EmbeddedLyricsPlaybackRuntime
import dev.amenhancer.module.hook.LyricsPlaybackRuntimeRegistry
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class LyricsCacheSort {
    RECENT,
    SIZE,
}

private data class LyricsCacheSongGroup(
    val appleMusicId: Long,
    val entries: List<LyricsCacheEntry>,
    val title: String?,
    val sizeBytes: Long,
    val lastUsedAtMs: Long,
)

/** Background-only cache index reads and mutations. All Compose state changes stay on main. */
private class LyricsCachePageState(
    private val worker: ExecutorService,
    private val mainHandler: Handler,
    private val runtime: () -> EmbeddedLyricsPlaybackRuntime?,
    private val isAlive: () -> Boolean,
) {
    var snapshot by mutableStateOf<LyricsCacheSnapshot?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<ModuleText?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    private var closed = false
    private var refreshQueued = false
    private var operationSequence = 0L
    private var reading = false
    private var mutating = false

    fun refresh() {
        if (closed || !isAlive()) return
        if (reading || mutating) {
            refreshQueued = true
            return
        }
        val active = runtime()
        if (active == null) {
            snapshot = null
            loading = false
            busy = false
            publishMessage(ModuleText.LYRICS_CACHE_UNAVAILABLE, isError = true)
            return
        }
        val sequence = ++operationSequence
        reading = true
        updateBusy()
        loading = snapshot == null
        try {
            worker.execute {
                val next = runCatching { active.cacheSnapshot() }.getOrNull()
                mainHandler.post {
                    reading = false
                    if (isCurrent(sequence, active)) {
                        loading = false
                        if (next == null) {
                            publishMessage(ModuleText.LYRICS_CACHE_SIZE_UNAVAILABLE, isError = true)
                        } else {
                            snapshot = next
                        }
                    }
                    updateBusy()
                    consumeQueuedRefresh()
                }
            }
        } catch (_: RejectedExecutionException) {
            reading = false
            loading = false
            updateBusy()
            publishMessage(ModuleText.LYRICS_CACHE_SIZE_UNAVAILABLE, isError = true)
        }
    }

    fun delete(appleMusicId: Long, source: String?) {
        mutate(
            success = if (source == null) ModuleText.LYRICS_CACHE_SONG_DELETED else ModuleText.LYRICS_CACHE_DELETED,
            failure = ModuleText.LYRICS_CACHE_DELETE_FAILED,
            working = ModuleText.LYRICS_CACHE_WORKING,
        ) { active -> active.deleteCachedLyrics(appleMusicId, source) }
    }

    fun clearAll() {
        mutate(
            success = ModuleText.LYRICS_CACHE_CLEARED,
            failure = ModuleText.LYRICS_CACHE_CLEAR_FAILED,
            working = ModuleText.LYRICS_CACHE_CLEARING,
        ) { active -> active.clearCache() }
    }

    fun close() {
        closed = true
        refreshQueued = false
        operationSequence++
        reading = false
        mutating = false
        busy = false
    }

    private fun mutate(
        success: ModuleText,
        failure: ModuleText,
        working: ModuleText,
        operation: (EmbeddedLyricsPlaybackRuntime) -> Boolean,
    ) {
        if (closed || mutating || !isAlive()) return
        val active = runtime()
        if (active == null) {
            publishMessage(ModuleText.LYRICS_CACHE_UNAVAILABLE, isError = true)
            return
        }
        val sequence = ++operationSequence
        mutating = true
        updateBusy()
        publishMessage(working, isError = false)
        try {
            worker.execute {
                val changed = runCatching { operation(active) }.getOrDefault(false)
                val next = runCatching { active.cacheSnapshot() }.getOrNull()
                mainHandler.post {
                    mutating = false
                    if (!closed && isAlive()) {
                        if (runtime() === active) {
                            loading = false
                            next?.let { snapshot = it }
                            if (changed) publishMessage(success, isError = false)
                            else publishMessage(failure, isError = true)
                        } else {
                            refreshQueued = true
                        }
                    }
                    updateBusy()
                    consumeQueuedRefresh()
                }
            }
        } catch (_: RejectedExecutionException) {
            mutating = false
            reading = false
            updateBusy()
            publishMessage(failure, isError = true)
        }
    }

    private fun isCurrent(sequence: Long, active: EmbeddedLyricsPlaybackRuntime): Boolean =
        !closed && sequence == operationSequence && isAlive() && runtime() === active

    private fun consumeQueuedRefresh() {
        if (!refreshQueued || reading || mutating) return
        refreshQueued = false
        refresh()
    }

    private fun updateBusy() {
        busy = reading || mutating
    }

    private fun publishMessage(value: ModuleText, isError: Boolean) {
        message = value
        messageIsError = isError
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedLyricsCachePage(
    activity: Activity,
    active: Boolean,
) {
    var runtime by remember { mutableStateOf(LyricsPlaybackRuntimeRegistry.controls()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val state = remember(activity, worker, mainHandler) {
        LyricsCachePageState(
            worker = worker,
            mainHandler = mainHandler,
            runtime = { runtime },
            isAlive = {
                val current = currentActivity()
                current != null && current === activity &&
                    !current.isFinishing && !current.isDestroyed
            },
        )
    }

    DisposableEffect(state) {
        onDispose { state.close() }
    }
    DisposableEffect(state, active) {
        if (!active) return@DisposableEffect onDispose {}
        val next = LyricsPlaybackRuntimeRegistry.controls()
        runtime = next
        val subscription = next?.observe { state.refresh() }
        state.refresh()
        onDispose { subscription?.close() }
    }

    var query by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(LyricsCacheSort.RECENT) }
    var visibleGroupCount by remember { mutableIntStateOf(INITIAL_VISIBLE_CACHE_GROUPS) }
    val focusManager = LocalFocusManager.current
    val cache = state.snapshot
    val allEntries = cache?.entries.orEmpty()
    val sources = remember(allEntries) { cacheSources(allEntries) }
    val groups = remember(allEntries, query, sourceFilter, sort) {
        buildCacheGroups(allEntries, query, sourceFilter, sort)
    }
    val visibleGroups = groups.take(visibleGroupCount)
    val songs = remember(allEntries) { allEntries.map { it.entry.appleMusicId }.distinct().size }
    val canResolveNames = active && cache?.indexAvailable == true && allEntries.isNotEmpty()

    LaunchedEffect(runtime, canResolveNames) {
        val playback = runtime ?: return@LaunchedEffect
        if (canResolveNames) {
            withContext(Dispatchers.IO) { playback.refreshCacheSongNames { !isActive } }
        }
    }
    LaunchedEffect(sources) {
        if (sourceFilter?.let { it !in sources } == true) sourceFilter = null
    }
    LaunchedEffect(query, sourceFilter, sort) {
        visibleGroupCount = INITIAL_VISIBLE_CACHE_GROUPS
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmbeddedSettingsGroup {
            BasicComponent(
                title = localizedText(ModuleText.LYRICS_THIRD_PARTY_CACHE),
                summary = when {
                    cache != null -> localizedText(
                        ModuleText.LYRICS_CACHE_OVERVIEW,
                        songs,
                        cache.entries.size,
                        formatLyricsCacheSize(cache.usageBytes),
                    )
                    state.loading -> localizedText(ModuleText.LYRICS_CACHE_LOADING)
                    runtime == null -> localizedText(ModuleText.LYRICS_CACHE_UNAVAILABLE)
                    else -> localizedText(ModuleText.LYRICS_CACHE_SIZE_UNAVAILABLE)
                },
                insideMargin = EmbeddedPreferencePadding,
                endActions = if (cache == null || cache.usageBytes <= 0L) null else {
                    {
                        TextButton(
                            text = localizedText(ModuleText.LYRICS_CACHE_CLEAR),
                            onClick = {
                                showLyricsCacheDeleteDialog(
                                    activity = activity,
                                    title = localizedText(ModuleText.LYRICS_CACHE_DELETE_ALL),
                                    notice = cacheClearConfirmation(cache, songs),
                                    onConfirm = state::clearAll,
                                )
                            },
                            enabled = runtime != null && !state.busy,
                        )
                    }
                },
            )
        }

        if (cache != null && !cache.indexAvailable) EmbeddedSettingsNotice(
            localizedText(ModuleText.LYRICS_CACHE_INDEX_UNAVAILABLE),
        )
        if (state.message != null && state.messageIsError) EmbeddedSettingsNotice(
            localizedText(state.message!!),
        )

        EmbeddedSettingsGroup {
            OverlayDropdownPreference(
                items = listOf(localizedText(ModuleText.LYRICS_CACHE_ALL_SOURCES)) +
                    sources.map { cacheSourceName(it) },
                selectedIndex = sourceFilter?.let { sources.indexOf(it) + 1 } ?: 0,
                title = localizedText(ModuleText.LYRICS_CACHE_FILTER_SOURCE),
                summary = sourceFilter?.let { cacheSourceName(it) }
                    ?: localizedText(ModuleText.LYRICS_CACHE_ALL_SOURCES),
                startAction = {
                    EmbeddedSettingsIcon(EmbeddedSvgIcon.DocumentSearch, modifier = Modifier.size(22.dp))
                },
                insideMargin = EmbeddedPreferencePadding,
                showValue = false,
                enabled = cache?.indexAvailable == true && sources.isNotEmpty(),
                onSelectedIndexChange = { index ->
                    sourceFilter = if (index <= 0) null else sources.getOrNull(index - 1)
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            val order = LyricsCacheSort.entries
            OverlayDropdownPreference(
                items = order.map { lyricsCacheSortName(it) },
                selectedIndex = order.indexOf(sort),
                title = localizedText(ModuleText.LYRICS_CACHE_SORT),
                summary = lyricsCacheSortName(sort),
                startAction = {
                    EmbeddedSettingsIcon(EmbeddedSvgIcon.AutomaticLyrics, modifier = Modifier.size(22.dp))
                },
                insideMargin = EmbeddedPreferencePadding,
                showValue = false,
                onSelectedIndexChange = { index -> order.getOrNull(index)?.let { sort = it } },
            )
        }

        EmbeddedSettingsGroup {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = localizedText(ModuleText.LYRICS_CACHE_SEARCH),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    enabled = cache?.indexAvailable == true,
                    leadingIcon = {
                        EmbeddedSettingsIcon(
                            icon = EmbeddedSvgIcon.Search,
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            when {
                state.loading -> LyricsCacheEmptyRow(ModuleText.LYRICS_CACHE_LOADING)
                cache == null -> LyricsCacheEmptyRow(
                    if (runtime == null) ModuleText.LYRICS_CACHE_UNAVAILABLE else ModuleText.LYRICS_CACHE_EMPTY,
                )
                groups.isEmpty() -> LyricsCacheEmptyRow(
                    if (query.isBlank() && sourceFilter == null) ModuleText.LYRICS_CACHE_EMPTY
                    else ModuleText.NO_SEARCH_RESULTS,
                )
                else -> visibleGroups.forEachIndexed { index, group ->
                    key(group.appleMusicId) {
                        val title = group.title ?: localizedText(ModuleText.LYRICS_CACHE_UNKNOWN_SONG, group.appleMusicId)
                        val confirmDelete: (String?) -> Unit = { source ->
                            showLyricsCacheDeleteDialog(
                                activity = activity,
                                title = localizedText(
                                    if (source == null) ModuleText.LYRICS_CACHE_DELETE_SONG
                                    else ModuleText.LYRICS_CACHE_DELETE_SOURCE,
                                ),
                                notice = if (source == null) {
                                    localizedText(ModuleText.LYRICS_CACHE_DELETE_SONG_CONFIRM, group.entries.size, title)
                                } else {
                                    localizedText(ModuleText.LYRICS_CACHE_DELETE_SOURCE_CONFIRM, cacheSourceName(source), title)
                                },
                                onConfirm = { state.delete(group.appleMusicId, source) },
                            )
                        }
                        LyricsCacheSongRow(
                            group = group,
                            title = title,
                            canDelete = cache.indexAvailable && !state.busy,
                            onDelete = confirmDelete,
                            onShowSources = {
                                focusManager.clearFocus()
                                showLyricsCacheSourcesDialog(
                                    activity,
                                    title,
                                    group.entries,
                                    canDelete = cache.indexAvailable && !state.busy,
                                    onDelete = confirmDelete,
                                )
                            },
                        )
                    }
                    if (index < visibleGroups.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }

            if (groups.size > visibleGroupCount) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = localizedText(
                            ModuleText.LYRICS_VISIBLE_COUNT,
                            minOf(groups.size, visibleGroupCount),
                            groups.size,
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = localizedText(ModuleText.LOAD_MORE),
                        onClick = {
                            visibleGroupCount = minOf(
                                groups.size,
                                visibleGroupCount + ADDITIONAL_VISIBLE_CACHE_GROUPS,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.message != null && !state.messageIsError) {
            EmbeddedSettingsNotice(localizedText(state.message!!))
        }
        Text(
            text = localizedText(ModuleText.LYRICS_CACHE_SIZE_NOTE),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun EmbeddedSettingsHost.LyricsCacheSongRow(
    group: LyricsCacheSongGroup,
    title: String,
    canDelete: Boolean,
    onDelete: (String) -> Unit,
    onShowSources: () -> Unit,
) {
    val source = group.entries.singleOrNull()?.entry?.source
    val sourceSummary = source?.let(::cacheSourceName)
        ?: localizedText(ModuleText.LYRICS_CACHE_SOURCE_COUNT, group.entries.size)
    val nameParts = title.split(" - ", limit = 3)
    val showArtistSeparately = nameParts.size == 2
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = if (showArtistSeparately) nameParts.first() else title,
        summary = listOfNotNull(
            nameParts.getOrNull(1)?.takeIf { showArtistSeparately },
            sourceSummary,
            formatLyricsCacheSize(group.sizeBytes),
        ).joinToString(" · "),
        endActions = {
            if (source == null) {
                EmbeddedSettingsIcon(
                    icon = EmbeddedSvgIcon.ChevronRight,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LyricsCacheDeleteSourceButton(source, canDelete) { onDelete(source) }
            }
        },
        insideMargin = EmbeddedPreferencePadding,
        onClick = onShowSources.takeIf { source == null },
    )
}

@Composable
private fun EmbeddedSettingsHost.LyricsCacheDeleteSourceButton(
    source: String,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    IconButton(onClick = onDelete, enabled = enabled) {
        EmbeddedSettingsIcon(
            icon = EmbeddedSvgIcon.Delete,
            contentDescription = "${localizedText(ModuleText.LYRICS_CACHE_DELETE_SOURCE)} · ${cacheSourceName(source)}",
            modifier = Modifier.size(21.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
    }
}

private fun EmbeddedSettingsHost.showLyricsCacheSourcesDialog(
    activity: Activity,
    title: String,
    entries: List<LyricsCacheEntry>,
    canDelete: Boolean,
    onDelete: (String?) -> Unit,
) {
    var deleteAction: (() -> Unit)? = null
    showEmbeddedMiuixDialog(
        activity = activity,
        title = title,
        onDismiss = {
            if (currentActivity() === activity && !activity.isFinishing && !activity.isDestroyed) {
                deleteAction?.invoke()
            }
        },
        footer = { dismiss ->
            EmbeddedDialogActions(dismissText = localizedText(ModuleText.CLOSE), onDismiss = dismiss)
        },
    ) { dismiss ->
        EmbeddedSettingsGroup {
            entries.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val entry = item.entry
                val status = localizedText(ModuleText.LYRICS_CACHE_DISABLED).takeUnless { entry.enabled }
                BasicComponent(
                    title = cacheSourceName(entry.source),
                    summary = listOfNotNull(formatLyricsCacheSize(entry.sizeBytes), status).joinToString(" · "),
                    insideMargin = EmbeddedPreferencePadding,
                    endActions = {
                        LyricsCacheDeleteSourceButton(entry.source, canDelete) {
                            deleteAction = { onDelete(entry.source) }
                            dismiss()
                        }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            EmbeddedSettingLink(
                title = localizedText(ModuleText.LYRICS_CACHE_DELETE_SONG),
                icon = EmbeddedSvgIcon.Delete,
                enabled = canDelete,
                onClick = {
                    deleteAction = { onDelete(null) }
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun EmbeddedSettingsHost.LyricsCacheEmptyRow(text: ModuleText) {
    BasicComponent(
        title = localizedText(text),
        startAction = {
            EmbeddedSettingsIcon(
                icon = EmbeddedSvgIcon.DocumentSearch,
                modifier = Modifier.size(24.dp),
            )
        },
        insideMargin = EmbeddedPreferencePadding,
    )
}

private fun EmbeddedSettingsHost.showLyricsCacheDeleteDialog(
    activity: Activity,
    title: String,
    notice: String,
    onConfirm: () -> Unit,
) {
    var confirmed = false
    showEmbeddedMiuixDialog(
        activity = activity,
        title = title,
        onDismiss = {
            if (confirmed && !activity.isFinishing && !activity.isDestroyed && currentActivity() === activity) {
                onConfirm()
            }
        },
    ) { dismiss ->
        EmbeddedSettingsNotice(notice)
        EmbeddedSettingsNotice(localizedText(ModuleText.LYRICS_CACHE_REDOWNLOAD_NOTICE))
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

private fun cacheSources(entries: List<LyricsCacheEntry>): List<String> {
    val known = listOf(
        CustomLyricsSources.AUTO_CACHE,
        CustomLyricsSources.AMLL,
        CustomLyricsSources.AM_LYRICS,
        CustomLyricsSources.LUNABEAT,
    )
    return entries.map { it.entry.source }.distinct().sortedWith(
        compareBy<String> { known.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .thenBy { it.lowercase() },
    )
}

private fun buildCacheGroups(
    entries: List<LyricsCacheEntry>,
    query: String,
    sourceFilter: String?,
    sort: LyricsCacheSort,
): List<LyricsCacheSongGroup> {
    val needle = query.trim().lowercase()
    val groups = entries.groupBy { it.entry.appleMusicId }.mapNotNull { (id, songEntries) ->
        if (sourceFilter != null && songEntries.none { it.entry.source == sourceFilter }) return@mapNotNull null
        if (needle.isNotEmpty() &&
            !id.toString().contains(needle) &&
            songEntries.none { it.entry.cachedSongDisplayName()?.contains(needle, ignoreCase = true) == true }
        ) return@mapNotNull null

        val sortedEntries = songEntries.sortedWith(cacheEntryComparator(sort))
        val names = sortedEntries.mapNotNull { it.entry.cachedSongDisplayName() }
        val title = names.firstOrNull(String::isCompleteCacheSongName) ?: names.firstOrNull()
        LyricsCacheSongGroup(
            appleMusicId = id,
            entries = sortedEntries,
            title = title,
            sizeBytes = songEntries.distinctBy { it.entry.fileId }.sumOf { it.entry.sizeBytes },
            lastUsedAtMs = songEntries.maxOfOrNull(LyricsCacheEntry::lastUsedAtMs) ?: 0L,
        )
    }
    return when (sort) {
        LyricsCacheSort.RECENT -> groups.sortedWith(
            compareByDescending<LyricsCacheSongGroup>(LyricsCacheSongGroup::lastUsedAtMs)
                .thenByDescending(LyricsCacheSongGroup::sizeBytes)
                .thenBy(LyricsCacheSongGroup::appleMusicId),
        )
        LyricsCacheSort.SIZE -> groups.sortedWith(
            compareByDescending<LyricsCacheSongGroup>(LyricsCacheSongGroup::sizeBytes)
                .thenByDescending(LyricsCacheSongGroup::lastUsedAtMs)
                .thenBy(LyricsCacheSongGroup::appleMusicId),
        )
    }
}

private fun cacheEntryComparator(sort: LyricsCacheSort): Comparator<LyricsCacheEntry> = when (sort) {
    LyricsCacheSort.RECENT -> compareByDescending(LyricsCacheEntry::lastUsedAtMs)
        .thenByDescending { it.entry.sizeBytes }
        .thenBy { it.entry.source }
    LyricsCacheSort.SIZE -> compareByDescending<LyricsCacheEntry> { it.entry.sizeBytes }
        .thenByDescending(LyricsCacheEntry::lastUsedAtMs)
        .thenBy { it.entry.source }
}

private fun EmbeddedSettingsHost.cacheSourceName(source: String): String =
    automaticLyricsSourceName(source) { localizedText(it) }

private fun EmbeddedSettingsHost.lyricsCacheSortName(sort: LyricsCacheSort): String = localizedText(
    when (sort) {
        LyricsCacheSort.RECENT -> ModuleText.LYRICS_CACHE_SORT_RECENT
        LyricsCacheSort.SIZE -> ModuleText.LYRICS_CACHE_SORT_SIZE
    },
)

private fun EmbeddedSettingsHost.cacheClearConfirmation(
    snapshot: LyricsCacheSnapshot,
    songs: Int,
): String = if (snapshot.indexAvailable) {
    localizedText(
        ModuleText.LYRICS_CACHE_CLEAR_CONFIRM,
        songs,
        snapshot.entries.size,
        formatLyricsCacheSize(snapshot.usageBytes),
    )
} else {
    localizedText(
        ModuleText.LYRICS_CACHE_CLEAR_CONFIRM_UNAVAILABLE,
        formatLyricsCacheSize(snapshot.usageBytes),
    )
}

private const val INITIAL_VISIBLE_CACHE_GROUPS = 20
private const val ADDITIONAL_VISIBLE_CACHE_GROUPS = 50
