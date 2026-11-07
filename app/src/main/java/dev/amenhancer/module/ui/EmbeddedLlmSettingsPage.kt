package dev.amenhancer.module.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import dev.amenhancer.module.model.LlmLyricsThinkingFormat
import dev.amenhancer.module.model.ModuleSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** Miuix content for the OpenAI-compatible translation fallback settings page. */
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
            LlmEditableField.entries.forEach { field ->
                val value = settings.llmValue(field)
                EmbeddedSettingLink(
                    title = localizedText(field.title),
                    summary = when {
                        value.isBlank() -> localizedText(ModuleText.LLM_LYRICS_NOT_CONFIGURED)
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
}

private fun ModuleSettings.withLlmValue(
    field: LlmEditableField,
    value: String,
): ModuleSettings = when (field) {
    LlmEditableField.BaseUrl -> copy(llmLyricsBaseUrl = value)
    LlmEditableField.ApiKey -> copy(llmLyricsApiKey = value)
    LlmEditableField.Model -> copy(llmLyricsModel = value)
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

        fun save() {
            onSettingsChanged(settings.withLlmValue(field, input.trim()))
            dismiss()
        }

        EmbeddedSettingsNotice(
            text = localizedText(field.summary),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = input,
            onValueChange = { value -> input = value.take(field.maxLength) },
            modifier = Modifier.fillMaxWidth(),
            label = localizedText(field.hint),
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = field.keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            singleLine = true,
            visualTransformation = if (field.masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        )
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
