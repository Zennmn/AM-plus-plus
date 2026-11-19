package dev.amenhancer.module.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amenhancer.module.hook.LyricsPlaybackRuntimeRegistry
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsStatusSnapshot
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import dev.amenhancer.module.model.LlmLyricsThinkingFormat
import dev.amenhancer.module.model.ModuleSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix content for the shared OpenAI-compatible translation and transliteration settings. */
@Composable
internal fun EmbeddedSettingsHost.EmbeddedLlmLyricsPage(
    activity: Activity,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
    active: Boolean = true,
) {
    val host = this
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSettingsChanged by rememberUpdatedState(onSettingsChanged)
    val currentActive by rememberUpdatedState(active)
    val llmFallbackActive = settings.customLyricsEnabled && settings.automaticLyricsEnabled
    val liveLlmStatus by LyricsPlaybackRuntimeRegistry.llmStatus.collectAsState()
    val currentSongAppleMusicId = remember(liveLlmStatus, active, settings) {
        runCatching { controller.currentSongDetails()?.appleMusicId ?: 0L }.getOrDefault(0L)
    }
    val thinkingFormat = settings.llmLyricsThinkingFormat
    val hasThinkingSwitch = thinkingFormat == LlmLyricsThinkingFormat.THINKING_TYPE
    val currentEffort = settings.llmLyricsThinkingEffort
        .takeIf(thinkingFormat::supportsEffort)
        ?: LlmLyricsThinkingEffort.DEFAULT
    val effortEnabled = !hasThinkingSwitch || settings.llmLyricsThinkingEnabled
    val request = remember(
        settings.llmLyricsBaseUrl,
        settings.llmLyricsApiKey,
        settings.llmLyricsModel,
        settings.llmLyricsCustomHeaders,
        settings.llmLyricsThinkingEnabled,
        settings.llmLyricsThinkingEffort,
        settings.llmLyricsThinkingFormat,
    ) { LlmConnectionRequest() }
    val connectionConfig = remember(request) { LlmLyricsConfig.fromSettings(settings) }
    val scope = rememberCoroutineScope()
    var testingConnection by remember(request) { mutableStateOf(false) }

    DisposableEffect(request, active) {
        if (!active) {
            request.cancel()
            testingConnection = false
        }
        onDispose { request.cancel() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LlmLyricsStatusSection(
            settings = settings,
            snapshot = liveLlmStatus,
            currentAppleMusicId = currentSongAppleMusicId,
        )
        EmbeddedSettingsSection(title = localizedText(ModuleText.FEATURES))
        EmbeddedSettingsGroup {
            EmbeddedSettingSwitch(
                title = localizedText(ModuleText.LLM_LYRICS_ENABLED),
                summary = localizedText(
                    if (llmFallbackActive) ModuleText.LLM_LYRICS_ENABLED_SUMMARY
                    else ModuleText.LLM_LYRICS_REQUIRES_AUTO,
                ),
                checked = settings.llmLyricsEnabled,
                enabled = llmFallbackActive,
                icon = EmbeddedSvgIcon.Translation,
                onCheckedChange = { checked ->
                    currentOnSettingsChanged(currentSettings.copy(llmLyricsEnabled = checked))
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            EmbeddedSettingSwitch(
                title = localizedText(ModuleText.LLM_LYRICS_TRANSLITERATION_ENABLED),
                summary = localizedText(ModuleText.LLM_LYRICS_TRANSLITERATION_ENABLED_SUMMARY),
                checked = settings.llmLyricsTransliterationEnabled,
                icon = EmbeddedSvgIcon.Translation,
                onCheckedChange = { checked ->
                    currentOnSettingsChanged(
                        currentSettings.copy(llmLyricsTransliterationEnabled = checked),
                    )
                },
            )
        }

        LlmLyricsCacheCard(activity, active)

        EmbeddedSettingsSection(
            title = localizedText(ModuleText.LLM_LYRICS_API_SETTINGS),
            summary = localizedText(ModuleText.LLM_LYRICS_SHARED_API_CONFIG),
        )
        EmbeddedSettingsGroup {
            LlmEditableField.entries.forEach { field ->
                val value = settings.llmValue(field)
                EmbeddedSettingLink(
                    title = localizedText(field.title),
                    summary = when {
                        value.isBlank() -> localizedText(ModuleText.LLM_LYRICS_NOT_CONFIGURED)
                        field == LlmEditableField.CustomHeaders -> LlmLyricsConfig.parseCustomHeaders(value)
                            ?.let { localizedText(ModuleText.LLM_LYRICS_CUSTOM_HEADERS_COUNT, it.size) }
                            ?: localizedText(ModuleText.LLM_LYRICS_CUSTOM_HEADERS_INVALID)
                        field.masked -> "••••••"
                        else -> value
                    },
                    icon = field.icon,
                    onClick = {
                        host.showLlmFieldDialog(
                            activity = activity,
                            field = field,
                            settings = currentSettings,
                            onSettingsChanged = currentOnSettingsChanged,
                        )
                    },
                )
            }
            val thinkingFormats = LlmLyricsThinkingFormat.entries
            OverlayDropdownPreference(
                entry = DropdownEntry(
                    items = thinkingFormats.map { choice ->
                        DropdownItem(
                            text = host.llmThinkingFormatLabel(choice),
                            selected = choice == thinkingFormat,
                            summary = localizedText(
                                when (choice) {
                                    LlmLyricsThinkingFormat.NONE ->
                                        ModuleText.LLM_LYRICS_THINKING_EFFORT_DEFAULT
                                    LlmLyricsThinkingFormat.THINKING_TYPE ->
                                        ModuleText.LLM_LYRICS_THINKING_FORMAT_TYPE_HINT
                                    LlmLyricsThinkingFormat.REASONING_EFFORT ->
                                        ModuleText.LLM_LYRICS_THINKING_FORMAT_EFFORT_HINT
                                },
                            ),
                            onClick = {
                                currentOnSettingsChanged(
                                    currentSettings.copy(llmLyricsThinkingFormat = choice),
                                )
                            },
                        )
                    },
                ),
                title = localizedText(ModuleText.LLM_LYRICS_THINKING_FORMAT),
                summary = localizedText(llmThinkingFormatSummary(thinkingFormat)),
                startAction = {
                    EmbeddedSettingsIcon(EmbeddedSvgIcon.Thinking, modifier = Modifier.size(22.dp))
                },
                insideMargin = EmbeddedPreferencePadding,
            )
            if (hasThinkingSwitch) {
                EmbeddedSettingSwitch(
                    title = localizedText(ModuleText.LLM_LYRICS_THINKING),
                    summary = localizedText(ModuleText.LLM_LYRICS_THINKING_SUMMARY),
                    checked = settings.llmLyricsThinkingEnabled,
                    icon = EmbeddedSvgIcon.Thinking,
                    onCheckedChange = { checked ->
                        currentOnSettingsChanged(
                            currentSettings.copy(llmLyricsThinkingEnabled = checked),
                        )
                    },
                )
            }
            if (thinkingFormat != LlmLyricsThinkingFormat.NONE) {
                val choices = LlmLyricsThinkingEffort.entries
                    .filter(thinkingFormat::supportsEffort)
                OverlayDropdownPreference(
                    entry = DropdownEntry(
                        items = choices.map { effort ->
                            DropdownItem(
                                text = host.llmThinkingEffortLabel(effort),
                                selected = effort == currentEffort,
                                onClick = {
                                    currentOnSettingsChanged(
                                        currentSettings.copy(llmLyricsThinkingEffort = effort),
                                    )
                                },
                            )
                        },
                    ),
                    title = localizedText(ModuleText.LLM_LYRICS_THINKING_EFFORT),
                    startAction = {
                        EmbeddedSettingsIcon(EmbeddedSvgIcon.Thinking, modifier = Modifier.size(22.dp))
                    },
                    insideMargin = EmbeddedPreferencePadding,
                    enabled = effortEnabled,
                )
            }
        }

        EmbeddedSettingsGroup {
            TextButton(
                text = localizedText(
                    if (testingConnection) ModuleText.LLM_LYRICS_TEST_RUNNING
                    else ModuleText.LLM_LYRICS_TEST_CONNECTION,
                ),
                onClick = {
                    if (currentActive && !testingConnection) {
                        testingConnection = true
                        val requestId = request.begin()
                        scope.launch {
                            val result = try {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        LlmLyricsClient().testConnection(connectionConfig) {
                                            request.isCancelled
                                        }
                                    }.getOrElse {
                                        LlmLyricsClient.ConnectionResult("transport")
                                    }
                                }
                            } catch (_: CancellationException) {
                                return@launch
                            } catch (_: Throwable) {
                                LlmLyricsClient.ConnectionResult("transport")
                            }
                            if (!currentActive ||
                                !request.isCurrent(requestId) ||
                                activity.isFinishing ||
                                activity.isDestroyed ||
                                host.currentActivity() !== activity
                            ) {
                                return@launch
                            }
                            testingConnection = false
                            val title = localizedText(ModuleText.LLM_LYRICS_TEST_CONNECTION)
                            host.showEmbeddedMiuixDialog(
                                activity = activity,
                                title = title,
                            ) { dismiss ->
                                EmbeddedSettingsNotice(
                                    text = host.llmConnectionResultText(result),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                EmbeddedDialogActions(
                                    dismissText = localizedText(ModuleText.CLOSE),
                                    onDismiss = dismiss,
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = currentActive && !testingConnection,
            )
        }
    }
}

@Composable
private fun EmbeddedSettingsHost.LlmLyricsCacheCard(activity: Activity, active: Boolean) {
    val playback = LyricsPlaybackRuntimeRegistry.controls()
    val scope = rememberCoroutineScope()
    var usageBytes by remember(playback) { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(playback, active) {
        if (active && playback != null) usageBytes = withContext(Dispatchers.IO) { playback.llmCacheUsageBytes() }
    }

    EmbeddedSettingsGroup {
        BasicComponent(
            title = localizedText(ModuleText.LYRICS_LLM_CACHE),
            summary = when {
                clearing -> localizedText(ModuleText.LYRICS_CACHE_WORKING)
                playback == null -> localizedText(ModuleText.LYRICS_CACHE_UNAVAILABLE)
                else -> usageBytes?.let { localizedText(ModuleText.LYRICS_LLM_CACHE_USAGE, formatLyricsCacheSize(it)) }
                    ?: localizedText(ModuleText.LYRICS_CACHE_LOADING)
            },
            insideMargin = EmbeddedPreferencePadding,
            endActions = if (playback == null || (usageBytes ?: 0L) <= 0L) null else {
                {
                    TextButton(
                        text = localizedText(ModuleText.LYRICS_CACHE_CLEAR),
                        enabled = active && !clearing,
                        onClick = {
                            showEmbeddedMiuixDialog(activity, title = localizedText(ModuleText.LYRICS_LLM_CACHE_CLEAR)) { dismiss ->
                                EmbeddedSettingsNotice(localizedText(ModuleText.LYRICS_LLM_CACHE_CLEAR_CONFIRM))
                                EmbeddedDialogActions(
                                    dismissText = localizedText(ModuleText.CANCEL),
                                    onDismiss = dismiss,
                                    confirmText = localizedText(ModuleText.DELETE),
                                    onConfirm = {
                                        dismiss()
                                        clearing = true
                                        scope.launch {
                                            val (cleared, remaining) = withContext(Dispatchers.IO) {
                                                playback.clearLlmCache() to playback.llmCacheUsageBytes()
                                            }
                                            usageBytes = remaining
                                            clearing = false
                                            val message = if (cleared) ModuleText.LYRICS_LLM_CACHE_CLEARED
                                                else ModuleText.LYRICS_LLM_CACHE_CLEAR_FAILED
                                            Toast.makeText(activity, localizedText(message), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }
}

/** Flat in the lyric menu; one ordinary Miuix group in settings. */
@Composable
internal fun LlmLyricsStatusSection(
    settings: ModuleSettings,
    snapshot: LlmLyricsStatusSnapshot?,
    currentAppleMusicId: Long? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val config = remember(settings) { LlmLyricsConfig.fromSettings(settings) }
    val configurationKey = config.hashCode()
    val activeSnapshot = snapshot?.takeIf { status ->
        status.isCurrentFor(currentAppleMusicId, configurationKey)
    }
    val translation = resolvedLlmPhase(
        settings = settings,
        config = config,
        configurationKey = configurationKey,
        snapshot = snapshot,
        currentAppleMusicId = currentAppleMusicId,
        transliteration = false,
    )
    val transliteration = resolvedLlmPhase(
        settings = settings,
        config = config,
        configurationKey = configurationKey,
        snapshot = snapshot,
        currentAppleMusicId = currentAppleMusicId,
        transliteration = true,
    )
    val model = activeSnapshot?.model?.trim()?.takeIf(String::isNotEmpty)
        ?: settings.llmLyricsModel.trim().takeIf(String::isNotEmpty)
    val songTitle = activeSnapshot?.songTitle?.trim()?.takeIf(String::isNotEmpty)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (compact) 0.dp else 16.dp,
                vertical = 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmbeddedSettingsIcon(
                    icon = EmbeddedSvgIcon.Model,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = ModuleText.LLM_STATUS_TITLE.text(locale = locale),
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                model?.let {
                    Text(
                        text = it,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (!compact && songTitle != null) {
                Text(
                    text = songTitle,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            LlmLyricsStatusRow(
                label = ModuleText.LLM_STATUS_TRANSLATION.text(locale = locale),
                phase = translation,
                existsText = ModuleText.LLM_STATUS_EXISTS_TRANSLATION,
                locale = locale,
                animationLabel = "llm-translation-status",
            )
            LlmLyricsStatusRow(
                label = ModuleText.LLM_STATUS_TRANSLITERATION.text(locale = locale),
                phase = transliteration,
                existsText = ModuleText.LLM_STATUS_EXISTS_TRANSLITERATION,
                locale = locale,
                animationLabel = "llm-transliteration-status",
            )
            if (translation.isFailure() || transliteration.isFailure()) {
                Text(
                    text = ModuleText.LLM_STATUS_ORIGINAL_KEPT.text(locale = locale),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
    if (compact) Box(modifier.fillMaxWidth()) { content() }
    else EmbeddedSettingsGroup(modifier = modifier) { content() }
}

@Composable
private fun LlmLyricsStatusRow(
    label: String,
    phase: LlmLyricsPhase,
    existsText: ModuleText,
    locale: Locale,
    animationLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.weight(0.64f).semantics { liveRegion = LiveRegionMode.Polite },
            transitionSpec = {
                (fadeIn(tween(140)) togetherWith fadeOut(tween(90)))
                    .using(SizeTransform(clip = true))
            },
            contentAlignment = Alignment.CenterEnd,
            label = animationLabel,
        ) { targetPhase ->
            val failure = targetPhase.isFailure()
            val emphasized = targetPhase.isBusy() || targetPhase == LlmLyricsPhase.READY ||
                targetPhase == LlmLyricsPhase.CACHED
            val tint = when {
                failure -> MiuixTheme.colorScheme.error
                emphasized -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Row(
                modifier = Modifier.background(
                    color = tint.copy(alpha = if (failure) 0.07f else 0.10f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (targetPhase.isBusy()) {
                    Box(Modifier.size(14.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            size = 12.dp,
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                Text(
                    text = llmStatusText(targetPhase, existsText, locale),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = tint,
                )
            }
        }
    }
}

private fun LlmLyricsStatusSnapshot.isCurrentFor(
    appleMusicId: Long?,
    configurationKey: Int,
): Boolean {
    return appleMusicId != null &&
        appleMusicId > 0L &&
        this.appleMusicId == appleMusicId &&
        (this.configurationKey == null || this.configurationKey == configurationKey)
}

private fun resolvedLlmPhase(
    settings: ModuleSettings,
    config: LlmLyricsConfig,
    configurationKey: Int,
    snapshot: LlmLyricsStatusSnapshot?,
    currentAppleMusicId: Long?,
    transliteration: Boolean,
): LlmLyricsPhase = when {
    transliteration -> when {
        !settings.llmLyricsTransliterationEnabled -> LlmLyricsPhase.OFF
        !config.isConfigured -> LlmLyricsPhase.CONFIG_REQUIRED
        snapshot == null || !snapshot.isCurrentFor(currentAppleMusicId, configurationKey) ->
            LlmLyricsPhase.WAITING
        else -> snapshot.transliteration.takeUnless { it == LlmLyricsPhase.OFF } ?: LlmLyricsPhase.WAITING
    }
    else -> when {
        !settings.llmLyricsEnabled -> LlmLyricsPhase.OFF
        !settings.customLyricsEnabled || !settings.automaticLyricsEnabled ->
            LlmLyricsPhase.REQUIRES_AUTO
        !config.isConfigured -> LlmLyricsPhase.CONFIG_REQUIRED
        snapshot == null || !snapshot.isCurrentFor(currentAppleMusicId, configurationKey) ->
            LlmLyricsPhase.WAITING
        else -> snapshot.translation.takeUnless { it == LlmLyricsPhase.OFF } ?: LlmLyricsPhase.WAITING
    }
}

private fun LlmLyricsPhase.isBusy(): Boolean = when (this) {
    LlmLyricsPhase.QUEUED,
    LlmLyricsPhase.GENERATING,
    LlmLyricsPhase.APPLYING,
    -> true
    else -> false
}

private fun LlmLyricsPhase.isFailure(): Boolean = when (this) {
    LlmLyricsPhase.REQUEST_FAILED,
    LlmLyricsPhase.AUTH_FAILED,
    LlmLyricsPhase.RATE_LIMITED,
    LlmLyricsPhase.TIMED_OUT,
    LlmLyricsPhase.INVALID_RESPONSE,
    -> true
    else -> false
}

private fun llmStatusText(
    phase: LlmLyricsPhase,
    existsText: ModuleText,
    locale: Locale,
): String {
    val status = when (phase) {
        LlmLyricsPhase.OFF -> ModuleText.LLM_STATUS_OFF
        LlmLyricsPhase.WAITING -> ModuleText.LLM_STATUS_WAITING
        LlmLyricsPhase.CONFIG_REQUIRED -> ModuleText.LLM_STATUS_CONFIG_REQUIRED
        LlmLyricsPhase.REQUIRES_AUTO -> ModuleText.LLM_STATUS_REQUIRES_AUTO
        LlmLyricsPhase.NATIVE_ONLY -> ModuleText.LLM_STATUS_NATIVE_ONLY
        LlmLyricsPhase.EXISTS -> existsText
        LlmLyricsPhase.NOT_NEEDED -> if (existsText == ModuleText.LLM_STATUS_EXISTS_TRANSLITERATION)
            ModuleText.LLM_STATUS_NO_TRANSLITERATION_NEEDED else ModuleText.LLM_STATUS_NOT_NEEDED
        LlmLyricsPhase.UNSUPPORTED -> ModuleText.LLM_STATUS_UNSUPPORTED
        LlmLyricsPhase.QUEUED -> ModuleText.LLM_STATUS_QUEUED
        LlmLyricsPhase.GENERATING -> ModuleText.LLM_STATUS_GENERATING
        LlmLyricsPhase.APPLYING -> ModuleText.LLM_STATUS_APPLYING
        LlmLyricsPhase.READY -> ModuleText.LLM_STATUS_READY
        LlmLyricsPhase.CACHED -> ModuleText.LLM_STATUS_CACHED
        LlmLyricsPhase.REQUEST_FAILED -> ModuleText.LLM_STATUS_REQUEST_FAILED
        LlmLyricsPhase.AUTH_FAILED -> ModuleText.LLM_STATUS_AUTH_FAILED
        LlmLyricsPhase.RATE_LIMITED -> ModuleText.LLM_STATUS_RATE_LIMITED
        LlmLyricsPhase.TIMED_OUT -> ModuleText.LLM_STATUS_TIMED_OUT
        LlmLyricsPhase.INVALID_RESPONSE -> ModuleText.LLM_STATUS_INVALID_RESPONSE
    }.text(locale = locale)
    return status
}

private enum class LlmEditableField(
    val title: ModuleText,
    val summary: ModuleText,
    val hint: ModuleText,
    val icon: EmbeddedSvgIcon,
    val maxLength: Int,
    val keyboardType: KeyboardType,
    val masked: Boolean = false,
) {
    BaseUrl(
        title = ModuleText.LLM_LYRICS_BASE_URL,
        summary = ModuleText.LLM_LYRICS_BASE_URL_SUMMARY,
        hint = ModuleText.LLM_LYRICS_INPUT_HINT_BASE_URL,
        icon = EmbeddedSvgIcon.ApiEndpoint,
        maxLength = ModuleSettings.MAX_LLM_LYRICS_BASE_URL_LENGTH,
        keyboardType = KeyboardType.Uri,
    ),
    ApiKey(
        title = ModuleText.LLM_LYRICS_API_KEY,
        summary = ModuleText.LLM_LYRICS_API_KEY_SUMMARY,
        hint = ModuleText.LLM_LYRICS_INPUT_HINT_API_KEY,
        icon = EmbeddedSvgIcon.ApiKey,
        maxLength = ModuleSettings.MAX_LLM_LYRICS_API_KEY_LENGTH,
        keyboardType = KeyboardType.Password,
        masked = true,
    ),
    Model(
        title = ModuleText.LLM_LYRICS_MODEL,
        summary = ModuleText.LLM_LYRICS_MODEL_SUMMARY,
        hint = ModuleText.LLM_LYRICS_INPUT_HINT_MODEL,
        icon = EmbeddedSvgIcon.Model,
        maxLength = ModuleSettings.MAX_LLM_LYRICS_MODEL_LENGTH,
        keyboardType = KeyboardType.Text,
    ),
    CustomHeaders(
        title = ModuleText.LLM_LYRICS_CUSTOM_HEADERS,
        summary = ModuleText.LLM_LYRICS_CUSTOM_HEADERS_SUMMARY,
        hint = ModuleText.LLM_LYRICS_INPUT_HINT_CUSTOM_HEADERS,
        icon = EmbeddedSvgIcon.ApiEndpoint,
        maxLength = ModuleSettings.MAX_LLM_LYRICS_CUSTOM_HEADERS_LENGTH,
        keyboardType = KeyboardType.Text,
    ),
}

private class LlmConnectionRequest {
    private val cancelled = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    val isCancelled: Boolean get() = cancelled.get()

    fun begin(): Int {
        cancelled.set(false)
        return generation.incrementAndGet()
    }

    fun cancel() {
        cancelled.set(true)
        generation.incrementAndGet()
    }

    fun isCurrent(requestId: Int): Boolean =
        !cancelled.get() && generation.get() == requestId
}

private fun ModuleSettings.llmValue(field: LlmEditableField): String = when (field) {
    LlmEditableField.BaseUrl -> llmLyricsBaseUrl
    LlmEditableField.ApiKey -> llmLyricsApiKey
    LlmEditableField.Model -> llmLyricsModel
    LlmEditableField.CustomHeaders -> llmLyricsCustomHeaders
}

private fun ModuleSettings.withLlmValue(
    field: LlmEditableField,
    value: String,
): ModuleSettings = when (field) {
    LlmEditableField.BaseUrl -> copy(llmLyricsBaseUrl = value)
    LlmEditableField.ApiKey -> copy(llmLyricsApiKey = value)
    LlmEditableField.Model -> copy(llmLyricsModel = value)
    LlmEditableField.CustomHeaders -> copy(llmLyricsCustomHeaders = value)
}

private fun llmThinkingFormatSummary(format: LlmLyricsThinkingFormat): ModuleText =
    when (format) {
        LlmLyricsThinkingFormat.NONE -> ModuleText.LLM_LYRICS_THINKING_FORMAT_NONE
        LlmLyricsThinkingFormat.THINKING_TYPE -> ModuleText.LLM_LYRICS_THINKING_FORMAT_TYPE_HINT
        LlmLyricsThinkingFormat.REASONING_EFFORT -> ModuleText.LLM_LYRICS_THINKING_FORMAT_EFFORT_HINT
    }

private fun EmbeddedSettingsHost.llmThinkingFormatLabel(format: LlmLyricsThinkingFormat): String =
    when (format) {
        LlmLyricsThinkingFormat.NONE -> localizedText(ModuleText.LLM_LYRICS_THINKING_FORMAT_NONE)
        LlmLyricsThinkingFormat.THINKING_TYPE -> "thinking.type"
        LlmLyricsThinkingFormat.REASONING_EFFORT -> "reasoning_effort"
    }

private fun EmbeddedSettingsHost.llmThinkingEffortLabel(effort: LlmLyricsThinkingEffort): String =
    localizedText(
        when (effort) {
            LlmLyricsThinkingEffort.DEFAULT -> ModuleText.LLM_LYRICS_THINKING_EFFORT_DEFAULT
            LlmLyricsThinkingEffort.LOW -> ModuleText.LLM_LYRICS_THINKING_EFFORT_LOW
            LlmLyricsThinkingEffort.HIGH -> ModuleText.LLM_LYRICS_THINKING_EFFORT_HIGH
            LlmLyricsThinkingEffort.MAX -> ModuleText.LLM_LYRICS_THINKING_EFFORT_MAX
        },
    )

private fun EmbeddedSettingsHost.showLlmFieldDialog(
    activity: Activity,
    field: LlmEditableField,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(field.title),
    ) { dismiss ->
        var input by remember {
            mutableStateOf(settings.llmValue(field).take(field.maxLength))
        }
        var invalidHeaders by remember { mutableStateOf(false) }
        val multiline = field == LlmEditableField.CustomHeaders

        fun save() {
            val value = if (multiline) ModuleSettings.normalizeLlmLyricsCustomHeaders(input) else input.trim()
            if (multiline && LlmLyricsConfig.parseCustomHeaders(value) == null) {
                invalidHeaders = true
                return
            }
            onSettingsChanged(settings.withLlmValue(field, value))
            dismiss()
        }

        if (multiline) {
            Text(
                text = localizedText(field.summary),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            EmbeddedSettingsNotice(
                text = localizedText(field.summary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextField(
            value = input,
            onValueChange = { value ->
                input = value.take(field.maxLength)
                invalidHeaders = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = localizedText(field.hint),
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = field.keyboardType,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            singleLine = !multiline,
            minLines = 1,
            maxLines = if (multiline) 6 else 1,
            visualTransformation = if (field.masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
        if (invalidHeaders) {
            Text(
                text = localizedText(ModuleText.LLM_LYRICS_CUSTOM_HEADERS_INVALID),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MiuixTheme.colorScheme.error,
            )
        } else if (multiline) {
            Text(
                text = localizedText(ModuleText.LLM_LYRICS_CUSTOM_HEADERS_OVERRIDE_HINT),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = dismiss,
            confirmText = localizedText(ModuleText.SAVE),
            onConfirm = { save() },
        )
    }
}

private fun EmbeddedSettingsHost.llmConnectionResultText(
    result: LlmLyricsClient.ConnectionResult,
): String = when (result.failureReason) {
    null -> localizedText(ModuleText.LLM_LYRICS_TEST_SUCCESS)
    "invalid_endpoint" -> localizedText(ModuleText.LLM_LYRICS_TEST_INVALID_ENDPOINT)
    "missing_model" -> localizedText(ModuleText.LLM_LYRICS_TEST_MISSING_MODEL)
    "invalid_headers" -> localizedText(ModuleText.LLM_LYRICS_CUSTOM_HEADERS_INVALID)
    "http_status" -> when (result.httpStatus) {
        401, 403 -> localizedText(
            ModuleText.LLM_LYRICS_TEST_AUTH_FAILED,
            result.httpStatus,
        )
        404 -> localizedText(ModuleText.LLM_LYRICS_TEST_NOT_FOUND)
        429 -> localizedText(ModuleText.LLM_LYRICS_TEST_RATE_LIMITED)
        else -> localizedText(
            ModuleText.LLM_LYRICS_TEST_HTTP_FAILED,
            result.httpStatus,
        )
    }
    "timeout" -> localizedText(ModuleText.LLM_LYRICS_TEST_TIMEOUT)
    "dns" -> localizedText(ModuleText.LLM_LYRICS_TEST_DNS_FAILED)
    "tls" -> localizedText(ModuleText.LLM_LYRICS_TEST_TLS_FAILED)
    "connect", "network", "connection_unavailable" ->
        localizedText(ModuleText.LLM_LYRICS_TEST_NETWORK_FAILED)
    "invalid_response", "response_limit" ->
        localizedText(ModuleText.LLM_LYRICS_TEST_INVALID_RESPONSE)
    else -> localizedText(ModuleText.LLM_LYRICS_TEST_FAILED)
}
