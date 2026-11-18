package dev.amenhancer.module.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.LyricsPlaybackRuntimeRegistry
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.LyricsPlaybackControls
import dev.amenhancer.module.lyrics.LyricsPlaybackSnapshot
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.filter
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication

private const val LYRICS_MENU_OFFSET_STEP_MS = 100
private val LyricsMenuBodyInset = 7.dp
private val LyricsMenuFeedbackInset = 8.dp
private val LyricsMenuFeedbackShape = RoundedCornerShape(12.dp)
private val LyricsMenuRowPadding = PaddingValues(vertical = 12.dp)
private val LyricsMenuPressableRowPadding = PaddingValues(horizontal = LyricsMenuFeedbackInset, vertical = 12.dp)

private enum class LyricsMenuDetail { OFFSET, SOURCE_ORDER }

private data class LyricsSourceOption(
    val id: String,
    val title: ModuleText,
)

/** A source menu independent of the settings window and its AndroidX view-tree owners. */
internal fun showEmbeddedLyricsMenu(
    activity: Activity,
    songs: CurrentSongIdentityCache,
    controls: LyricsPlaybackControls?,
    readSettings: () -> ModuleSettings,
    writeSourceOrder: ((List<String>) -> Boolean)?,
    writeAutomaticLyricsEnabled: ((Boolean) -> Boolean)? = null,
) {
    if (activity.isFinishing || activity.isDestroyed) return
    fun showFailure() {
        val message = ModuleText.LYRICS_MENU_OPEN_FAILED.text(locale = menuLocale(activity))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
    if (controls == null) {
        showFailure()
        return
    }
    val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-lyrics-menu").apply { isDaemon = true }
    }
    val hostView = activity.window.decorView
    val menuVisible = mutableStateOf(false)
    val visibleDetail = mutableStateOf<LyricsMenuDetail?>(null)
    var presented = false
    var closing = false
    fun requestDismiss(closeWindow: () -> Unit) {
        if (closing) return
        closing = true
        visibleDetail.value = null
        // An overlay that never reached the popup host has no exit to wait for.
        if (presented) menuVisible.value = false else closeWindow()
    }
    var hostWindowListener: View.OnAttachStateChangeListener? = null
    fun release() {
        hostWindowListener?.let(hostView::removeOnAttachStateChangeListener)
        hostWindowListener = null
        worker.shutdown()
    }
    val dialog = runCatching {
        createEmbeddedSettingsDialog(
            activity = activity,
            overlayOnly = true,
            onDismiss = ::release,
            onBack = { closeWindow ->
                if (visibleDetail.value != null) visibleDetail.value = null else requestDismiss(closeWindow)
            },
        ) { closeWindow ->
            LaunchedEffect(Unit) {
                if (!closing) menuVisible.value = true
            }
            EmbeddedLyricsMenuDialog(
                activity = activity,
                songs = songs,
                controls = controls,
                worker = worker,
                show = menuVisible.value,
                visibleDetail = visibleDetail,
                readSettings = readSettings,
                writeSourceOrder = writeSourceOrder,
                writeAutomaticLyricsEnabled = writeAutomaticLyricsEnabled,
                dismiss = { requestDismiss(closeWindow) },
                onPresented = { presented = true },
                onDismissFinished = { if (closing) closeWindow() },
            )
        }
    }.getOrElse { error ->
        release()
        ModernXposedRuntime.log("Lyrics menu window creation failed", error)
        showFailure()
        return
    }
    hostWindowListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dialog.dismiss()
    }.also(hostView::addOnAttachStateChangeListener)
    runCatching {
        dialog.show()
    }.onFailure { error ->
        runCatching { dialog.dismiss() }
        release()
        ModernXposedRuntime.log("Lyrics menu window show failed", error)
        showFailure()
    }
    if (!dialog.isShowing) release()
}

@Composable
private fun EmbeddedLyricsMenuDialog(
    activity: Activity,
    songs: CurrentSongIdentityCache,
    controls: LyricsPlaybackControls,
    worker: java.util.concurrent.ExecutorService,
    show: Boolean,
    visibleDetail: MutableState<LyricsMenuDetail?>,
    readSettings: () -> ModuleSettings,
    writeSourceOrder: ((List<String>) -> Boolean)?,
    writeAutomaticLyricsEnabled: ((Boolean) -> Boolean)?,
    dismiss: () -> Unit,
    onPresented: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val locale = remember(activity) { menuLocale(activity) }
    val llmStatus by LyricsPlaybackRuntimeRegistry.llmStatus.collectAsState()
    fun text(key: ModuleText, vararg arguments: Any?): String = key.text(*arguments, locale = locale)

    var song by remember(songs) { mutableStateOf(songs.current()?.details) }
    val appleMusicId = song?.appleMusicId ?: 0L
    val initial = remember(appleMusicId, controls) {
        appleMusicId.takeIf { it > 0L }?.let { id ->
            runCatching { controls.snapshot(id) }.getOrNull()?.takeIf { it.appleMusicId == id }
        } ?: LyricsPlaybackSnapshot(appleMusicId, isCurrentSong = false)
    }
    val currentAppleMusicId by rememberUpdatedState(appleMusicId)
    var snapshot by remember(appleMusicId, controls) { mutableStateOf(initial) }
    val currentControls by rememberUpdatedState(controls)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var detail by visibleDetail
    val showOffsetEditor = detail == LyricsMenuDetail.OFFSET
    var orderSettings by remember { mutableStateOf<ModuleSettings?>(null) }
    var offsetInput by remember { mutableStateOf(initial.offsetMs.toString()) }
    var offsetInvalid by remember { mutableStateOf(false) }
    var offsetBusy by remember { mutableStateOf(false) }
    var offsetMessage by remember { mutableStateOf<String?>(null) }
    var pendingOffset by remember { mutableStateOf<Int?>(initial.offsetMs) }
    var menuSettings by remember { mutableStateOf(runCatching(readSettings).getOrNull()) }
    var automaticSaving by remember { mutableStateOf(false) }
    val currentShow by rememberUpdatedState(show)
    val activeSource = snapshot.activeSource
    var sourceExpanded by remember { mutableStateOf(false) }
    val songReady = appleMusicId > 0L && snapshot.isCurrentSong
    val canAdjust = songReady && snapshot.canAdjustTiming && activeSource != null && snapshot.pendingSource == null
    val selectedSource = snapshot.preferredSource
    val sources = buildList {
        add(LyricsSourceOption(LyricsPlaybackSources.AUTOMATIC, ModuleText.LYRICS_SOURCE_AUTOMATIC))
        add(LyricsSourceOption(CustomLyricsSources.AM_NATIVE, ModuleText.AM_NATIVE))
        add(LyricsSourceOption(CustomLyricsSources.AMLL, ModuleText.LYRICS_SOURCE_AMLL))
        add(LyricsSourceOption(CustomLyricsSources.AM_LYRICS, ModuleText.LYRICS_SOURCE_AM_LYRICS))
        add(LyricsSourceOption(CustomLyricsSources.LUNABEAT, ModuleText.LYRICS_SOURCE_LUNABEAT))
        if (snapshot.hasManualLyrics) {
            add(LyricsSourceOption(CustomLyricsSources.MANUAL, ModuleText.LYRICS_SOURCE_MANUAL))
        }
    }
    val sourceValue = activeSource?.let { sourceLabel(it, locale) }
    val pendingSource = snapshot.pendingSource
    val sourceFeedback = when {
        !songReady -> text(ModuleText.LYRICS_MENU_SONG_NOT_READY)
        pendingSource != null -> text(ModuleText.LYRICS_SOURCE_PENDING, sourceLabel(pendingSource, locale))
        else -> snapshot.message?.takeIf(String::isNotBlank)
    }
    val offsetSourceSummary = activeSource
        ?.takeIf { snapshot.canAdjustTiming }
        ?.let { text(ModuleText.LYRICS_OFFSET_SOURCE, sourceLabel(it, locale)) }
        ?: text(ModuleText.LYRICS_OFFSET_DISABLED)

    LaunchedEffect(show, appleMusicId, llmStatus) {
        if (show) menuSettings = runCatching(readSettings).getOrNull()
    }

    fun refreshSnapshot() {
        val latest = songs.current()?.details
        // Metadata can briefly disappear while the player refreshes. Only a different
        // verified song should close this menu; keep the current controls disabled meanwhile.
        if (latest == null) {
            snapshot = LyricsPlaybackSnapshot(currentAppleMusicId, isCurrentSong = false)
            return
        }
        if (currentAppleMusicId > 0L && latest.appleMusicId != currentAppleMusicId) {
            dismiss()
            return
        }
        song = latest
        if (currentAppleMusicId <= 0L) return
        runCatching { currentControls.snapshot(currentAppleMusicId) }.getOrNull()?.let { next ->
            if (next.appleMusicId != currentAppleMusicId) return
            snapshot = next
            if (!offsetBusy && detail != LyricsMenuDetail.OFFSET) {
                pendingOffset = next.offsetMs
                offsetInput = next.offsetMs.toString()
                offsetInvalid = false
            }
        }
    }

    val cacheStatus = remember(activity, worker) {
        LyricsCacheStatusState(
            worker = worker,
            mainHandler = mainHandler,
            controls = { currentControls },
            isAlive = { !activity.isFinishing && !activity.isDestroyed },
        )
    }
    DisposableEffect(cacheStatus) {
        onDispose { cacheStatus.close() }
    }

    fun refreshFromRuntime() {
        menuSettings = runCatching(readSettings).getOrNull()
        refreshSnapshot()
        cacheStatus.refresh()
    }

    fun saveAutomaticLyricsEnabled(enabled: Boolean) {
        val current = menuSettings ?: return
        val write = writeAutomaticLyricsEnabled ?: return
        if (!show || automaticSaving || !current.customLyricsEnabled ||
            current.automaticLyricsEnabled == enabled
        ) return
        automaticSaving = true
        runCatching {
            worker.execute {
                val saved = runCatching { write(enabled) }.getOrDefault(false)
                mainHandler.post {
                    if (!currentShow || activity.isFinishing || activity.isDestroyed) return@post
                    automaticSaving = false
                    if (saved) {
                        menuSettings = runCatching(readSettings).getOrNull()
                            ?: current.copy(automaticLyricsEnabled = enabled)
                        refreshSnapshot()
                    } else {
                        Toast.makeText(activity, text(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.onFailure {
            automaticSaving = false
            Toast.makeText(activity, text(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
        }
    }

    fun applyOffset(value: Int) {
        val source = snapshot.activeSource ?: return
        if (!canAdjust || offsetBusy) return
        offsetBusy = true
        offsetMessage = text(ModuleText.LYRICS_OFFSET_SAVING)
        runCatching {
            worker.execute {
                val changed = runCatching { currentControls.setOffset(currentAppleMusicId, source, value) }
                    .getOrDefault(false)
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    offsetBusy = false
                    refreshSnapshot()
                    if (changed) {
                        pendingOffset = value
                        offsetInput = value.toString()
                        offsetInvalid = false
                        offsetMessage = null
                        if (detail == LyricsMenuDetail.OFFSET) detail = null
                    } else {
                        offsetMessage = runCatching { currentControls.snapshot(currentAppleMusicId).message }.getOrNull()
                            ?: text(ModuleText.LYRICS_OFFSET_UNAVAILABLE)
                    }
                }
            }
        }.onFailure {
            mainHandler.post {
                offsetBusy = false
                offsetMessage = text(ModuleText.LYRICS_OFFSET_SAVE_FAILED)
            }
        }
    }

    LaunchedEffect(appleMusicId, controls) {
        refreshFromRuntime()
    }
    DisposableEffect(appleMusicId, controls, songs) {
        var active = true
        val subscription = runCatching { controls.observe { if (active) refreshFromRuntime() } }.getOrNull()
        val songSubscription = songs.addListener {
            mainHandler.post { if (active) refreshSnapshot() }
        }
        onDispose {
            active = false
            songSubscription.close()
            subscription?.close()
        }
    }
    LaunchedEffect(activeSource, snapshot.canAdjustTiming) {
        if (!snapshot.canAdjustTiming || activeSource == null) {
            if (detail == LyricsMenuDetail.OFFSET) detail = null
            offsetBusy = false
            offsetMessage = null
        }
    }
    fun closeOffsetEditor() {
        offsetMessage = null
        offsetInvalid = false
        if (detail == LyricsMenuDetail.OFFSET) detail = null
    }
    fun closeOrderEditor() {
        if (detail == LyricsMenuDetail.SOURCE_ORDER) detail = null
    }
    fun saveSourceOrder(nextOrder: List<String>): Boolean {
        if (detail != LyricsMenuDetail.SOURCE_ORDER) return false
        val current = orderSettings ?: return false
        if (runCatching { writeSourceOrder?.invoke(nextOrder) }.getOrNull() != true) {
            Toast.makeText(activity, text(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
            return false
        }
        orderSettings = current.copy(automaticLyricsSourceOrder = nextOrder)
        return true
    }
    OverlayDialog(
        show = show,
        title = text(ModuleText.LYRICS_MENU),
        insideMargin = DpSize(
            (DialogDefaults.insideMargin.width - LyricsMenuFeedbackInset).coerceAtLeast(0.dp),
            DialogDefaults.insideMargin.height,
        ),
        enableWindowDim = true,
        onDismissRequest = dismiss,
        onDismissFinished = onDismissFinished,
        renderInRootScaffold = true,
        maxWidth = 380.dp,
    ) {
        SideEffect(onPresented)
        // Restore the dialog inset for the footer and the text inside the pressable rows.
        val contentInset = Modifier.padding(horizontal = LyricsMenuFeedbackInset)
        Column(Modifier.fillMaxWidth()) {
            EmbeddedSettingsGroup(
                modifier = Modifier.weight(1f, fill = false)
                    .padding(horizontal = LyricsMenuBodyInset)
                    .verticalScroll(rememberScrollState()),
            ) {
                menuSettings?.let { settings ->
                    if (writeAutomaticLyricsEnabled != null) {
                        val canToggle = show && settings.customLyricsEnabled && !automaticSaving
                        SwitchPreference(
                            modifier = contentInset,
                            title = text(ModuleText.AUTO_LYRICS),
                            summary = text(
                                if (settings.customLyricsEnabled) ModuleText.LYRICS_MENU_AUTO_SUMMARY
                                else ModuleText.LYRICS_MENU_AUTO_DISABLED,
                            ),
                            checked = settings.automaticLyricsEnabled,
                            enabled = canToggle,
                            onCheckedChange = ::saveAutomaticLyricsEnabled,
                            startAction = {
                                EmbeddedSettingsIcon(
                                    EmbeddedSvgIcon.AutomaticLyrics,
                                    modifier = Modifier.size(22.dp),
                                    tint = if (canToggle) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                                )
                            },
                            insideMargin = LyricsMenuRowPadding,
                        )
                        HorizontalDivider(contentInset)
                    }
                }
                val colors = MiuixTheme.colorScheme
                val sourceColors = if (sourceExpanded) {
                    colors.copy(onSurfaceVariantActions = lerp(colors.onSurfaceVariantActions, colors.onSurface, 0.4f))
                } else colors
                MiuixTheme(colors = sourceColors, textStyles = MiuixTheme.textStyles) {
                    LyricsMenuPressFeedback {
                        OverlayDropdownPreference(
                            modifier = Modifier.clip(LyricsMenuFeedbackShape),
                            entry = DropdownEntry(items = sources.map { option ->
                                DropdownItem(
                                    text = text(option.title),
                                    selected = selectedSource == option.id,
                                    summary = text(ModuleText.LYRICS_SOURCE_AUTOMATIC_SUMMARY)
                                        .takeIf { option.id == LyricsPlaybackSources.AUTOMATIC },
                                    onClick = {
                                        if (selectedSource != option.id ||
                                            (option.id != LyricsPlaybackSources.AUTOMATIC && activeSource != option.id)
                                        ) currentControls.selectSource(currentAppleMusicId, option.id)
                                    },
                                )
                            }),
                            title = text(ModuleText.LYRICS_SOURCE_SECTION),
                            summary = sourceValue?.takeIf { activeSource != selectedSource }
                                ?.let { text(ModuleText.LYRICS_ACTIVE_SOURCE, it) },
                            enabled = show && songReady && pendingSource == null,
                            insideMargin = LyricsMenuPressableRowPadding,
                            renderInRootScaffold = true,
                            onExpandedChange = { sourceExpanded = it },
                        )
                    }
                }
                sourceFeedback?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth().then(contentInset).padding(vertical = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (writeSourceOrder != null) {
                    HorizontalDivider(contentInset)
                    LyricsMenuLink(
                        title = text(ModuleText.LYRICS_MENU_AUTO_SOURCE_ORDER),
                        value = null,
                        enabled = show,
                        onClick = {
                            val latest = runCatching(readSettings).getOrNull()
                            if (latest != null) {
                                orderSettings = latest
                                detail = LyricsMenuDetail.SOURCE_ORDER
                            } else {
                                Toast.makeText(activity, text(ModuleText.LYRICS_MENU_OPEN_FAILED), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                menuSettings?.let { statusSettings ->
                    HorizontalDivider(contentInset)
                    LlmLyricsStatusSection(
                        settings = statusSettings,
                        snapshot = llmStatus,
                        currentAppleMusicId = if (songReady) appleMusicId else 0L,
                        compact = true,
                        modifier = contentInset,
                    )
                }
                HorizontalDivider(contentInset)
                LyricsMenuLink(
                    title = text(ModuleText.LYRICS_OFFSET_TITLE),
                    value = if (snapshot.canAdjustTiming) "${snapshot.offsetMs} ms" else "—",
                    enabled = show && canAdjust,
                    onClick = {
                        pendingOffset = snapshot.offsetMs
                        offsetInput = snapshot.offsetMs.toString()
                        offsetInvalid = false
                        offsetMessage = null
                        detail = LyricsMenuDetail.OFFSET
                    },
                )
                HorizontalDivider(contentInset)
                BasicComponent(
                    modifier = contentInset,
                    title = text(ModuleText.LYRICS_THIRD_PARTY_CACHE),
                    summary = cacheStatus.usageBytes?.let(::formatLyricsCacheSize),
                    endActions = {
                        TextButton(
                            text = text(ModuleText.LYRICS_CACHE_CLEAR),
                            onClick = { cacheStatus.refresh(clearFirst = true) },
                            enabled = show && !cacheStatus.busy,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    },
                    insideMargin = LyricsMenuRowPadding,
                )
                cacheStatus.message?.let { message ->
                    Text(
                        text = text(message),
                        modifier = Modifier.fillMaxWidth().then(contentInset).padding(bottom = 10.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            TextButton(
                text = text(ModuleText.CLOSE),
                onClick = dismiss,
                modifier = Modifier.fillMaxWidth().then(contentInset).padding(top = 10.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    OverlayDialog(
        show = show && showOffsetEditor,
        title = text(ModuleText.LYRICS_OFFSET_TITLE),
        enableWindowDim = true,
        onDismissRequest = ::closeOffsetEditor,
        renderInRootScaffold = true,
        maxWidth = 380.dp,
    ) {
        val offsetPadding = PaddingValues(
            top = EmbeddedPreferencePadding.calculateTopPadding(),
            bottom = EmbeddedPreferencePadding.calculateBottomPadding(),
        )
        Column(Modifier.fillMaxWidth().imePadding()) {
            EmbeddedSettingsGroup(
                modifier = Modifier.weight(1f, fill = false)
                    .padding(horizontal = LyricsMenuBodyInset)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicComponent(
                    title = offsetSourceSummary,
                    endActions = {
                        Text(
                            text = "${pendingOffset ?: snapshot.offsetMs} ms",
                            style = MiuixTheme.textStyles.body1,
                            color = if (canAdjust) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    insideMargin = offsetPadding,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(offsetPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = offsetInput,
                        onValueChange = { value ->
                            offsetInput = value
                            offsetInvalid = false
                            value.trim().toIntOrNull()?.let { pendingOffset = it }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = text(ModuleText.LYRICS_OFFSET_INPUT),
                        enabled = showOffsetEditor && canAdjust && !offsetBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                    )
                    if (offsetInvalid) Text(
                        text = text(ModuleText.LYRICS_OFFSET_INVALID),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(-LYRICS_MENU_OFFSET_STEP_MS, LYRICS_MENU_OFFSET_STEP_MS).forEach { delta ->
                            val label = if (delta < 0) ModuleText.LYRICS_OFFSET_DECREASE else ModuleText.LYRICS_OFFSET_INCREASE
                            TextButton(
                                text = text(label),
                                onClick = {
                                    val next = adjustedOffset(pendingOffset, snapshot.offsetMs, delta)
                                    pendingOffset = next
                                    offsetInput = next.toString()
                                },
                                modifier = Modifier.weight(1f),
                                enabled = showOffsetEditor && canAdjust && !offsetBusy,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = text(ModuleText.LYRICS_OFFSET_RESET),
                            onClick = { pendingOffset = 0; offsetInput = "0"; offsetInvalid = false },
                            modifier = Modifier.weight(1f),
                            enabled = showOffsetEditor && canAdjust && !offsetBusy,
                        )
                        TextButton(
                            text = text(ModuleText.LYRICS_OFFSET_APPLY),
                            onClick = {
                                val parsed = offsetInput.trim().toIntOrNull()
                                if (parsed == null) offsetInvalid = true else applyOffset(parsed)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = showOffsetEditor && canAdjust && !offsetBusy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                    offsetMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth(),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            LyricsMenuFooter(text(ModuleText.BACK), ::closeOffsetEditor)
        }
    }

    OverlayDialog(
        show = show && detail == LyricsMenuDetail.SOURCE_ORDER,
        title = text(ModuleText.LYRICS_MENU_AUTO_SOURCE_ORDER),
        enableWindowDim = true,
        onDismissRequest = ::closeOrderEditor,
        renderInRootScaffold = true,
        maxWidth = 380.dp,
    ) {
        val settings = orderSettings ?: return@OverlayDialog
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false)
                    .padding(horizontal = LyricsMenuBodyInset)
                    .verticalScroll(rememberScrollState()),
            ) {
                AutomaticLyricsSourceOrderEditor(
                    order = settings.automaticLyricsSourceOrder,
                    localize = { key, arguments -> text(key, *arguments) },
                    onOrderChanged = ::saveSourceOrder,
                    compact = true,
                )
            }
            TextButton(
                text = text(ModuleText.BACK),
                onClick = ::closeOrderEditor,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun LyricsMenuFooter(label: String, onClick: () -> Unit) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

@Composable
private fun LyricsMenuLink(title: String, value: String?, enabled: Boolean, onClick: () -> Unit) {
    LyricsMenuPressFeedback {
        BasicComponent(
            modifier = Modifier.clip(LyricsMenuFeedbackShape),
            title = title,
            enabled = enabled,
            insideMargin = LyricsMenuPressableRowPadding,
            onClick = onClick,
            endActions = {
                val tint = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.disabledOnSecondaryVariant
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (value != null) {
                        Text(
                            value,
                            modifier = Modifier.widthIn(max = 108.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.body2,
                            color = tint,
                        )
                    }
                    EmbeddedSettingsIcon(EmbeddedSvgIcon.ChevronRight, modifier = Modifier.size(16.dp), tint = tint)
                }
            },
        )
    }
}

@Composable
private fun LyricsMenuPressFeedback(content: @Composable () -> Unit) {
    val color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val indication = remember(color) { LyricsMenuIndication(color) }
    CompositionLocalProvider(LocalIndication provides indication, content = content)
}

private data class LyricsMenuIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        // The dropdown emits an extra held-down interaction for the entire expanded state.
        val transientSource = object : InteractionSource {
            override val interactions = interactionSource.interactions.filter {
                it is PressInteraction || it is FocusInteraction || it is HoverInteraction
            }
        }
        return MiuixIndication(color).create(transientSource)
    }
}

private fun adjustedOffset(pending: Int?, current: Int, delta: Int): Int {
    val base = pending ?: current
    return when {
        delta > 0 && base > Int.MAX_VALUE - delta -> Int.MAX_VALUE
        delta < 0 && base < Int.MIN_VALUE - delta -> Int.MIN_VALUE
        else -> base + delta
    }
}

private fun sourceLabel(source: String, locale: Locale): String = when (source) {
    LyricsPlaybackSources.AUTOMATIC -> ModuleText.LYRICS_SOURCE_AUTOMATIC.text(locale = locale)
    CustomLyricsSources.AM_NATIVE -> ModuleText.AM_NATIVE.text(locale = locale)
    CustomLyricsSources.AMLL -> ModuleText.LYRICS_SOURCE_AMLL.text(locale = locale)
    CustomLyricsSources.AM_LYRICS -> ModuleText.LYRICS_SOURCE_AM_LYRICS.text(locale = locale)
    CustomLyricsSources.LUNABEAT -> ModuleText.LYRICS_SOURCE_LUNABEAT.text(locale = locale)
    CustomLyricsSources.MANUAL -> ModuleText.LYRICS_SOURCE_MANUAL.text(locale = locale)
    CustomLyricsSources.AUTO_CACHE -> ModuleText.AUTO_CACHE.text(locale = locale)
    else -> source
}

internal fun formatLyricsCacheSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe == 0L -> "0 KB"
        safe < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.ROOT, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun menuLocale(activity: Activity): Locale {
    val locales = activity.resources.configuration.locales
    return if (locales.isEmpty) Locale.getDefault() else locales[0]
}
