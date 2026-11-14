package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.amenhancer.plugin.runtime.PluginRunState
import dev.amenhancer.plugin.runtime.PluginStatus
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun EmbeddedSettingsHost.EmbeddedPluginManagementPage(
    activity: Activity,
    revision: Int,
    active: Boolean,
) {
    val manager = plugins
    val state = remember(manager) { EmbeddedPluginManagementState() }
    if (manager == null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmbeddedSettingsNotice(localizedText(ModuleText.PLUGIN_RUNTIME_UNAVAILABLE))
        }
        return
    }

    fun canShowDialog(): Boolean =
        state.active && !activity.isFinishing && !activity.isDestroyed && currentActivity() === activity

    fun action(pluginId: String, block: () -> Unit) {
        if (!state.active || state.busyPluginId != null) return
        state.busyPluginId = pluginId
        manager.execute {
            val result = runCatching(block)
            mainHandler.post {
                state.busyPluginId = null
                state.enabledOverrides = state.enabledOverrides - pluginId
                if (!state.active) return@post
                state.refreshKey++
                val current = currentActivity()
                if (current === activity && !activity.isFinishing && !activity.isDestroyed) {
                    val failure = result.exceptionOrNull()
                    val message = if (failure == null) {
                        localizedText(ModuleText.SAVED_RESTART)
                    } else {
                        failure.message.orEmpty().ifBlank {
                            localizedText(ModuleText.OPERATION_FAILED)
                        }
                    }
                    Toast.makeText(current, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun statusLabel(runState: PluginRunState): String = when (runState) {
        PluginRunState.ACTIVE -> localizedText(ModuleText.PLUGIN_ACTIVE)
        PluginRunState.DISABLED -> localizedText(ModuleText.PLUGIN_DISABLED)
        PluginRunState.LOADING -> localizedText(ModuleText.PLUGIN_LOADING)
        PluginRunState.BLOCKED -> localizedText(ModuleText.PLUGIN_BLOCKED)
        PluginRunState.UNSUPPORTED -> localizedText(ModuleText.PLUGIN_UNSUPPORTED)
        PluginRunState.FAILED -> localizedText(ModuleText.PLUGIN_FAILED)
    }

    fun showConflicts(status: PluginStatus) {
        if (!canShowDialog()) return
        val details = status.conflicts.joinToString("\n\n") {
            localizedText(
                ModuleText.PLUGIN_CONFLICT_DETAILS,
                localizedText(if (it.blocking) ModuleText.CONFLICT_BLOCKING else ModuleText.CONFLICT_WARNING),
                it.reason,
                it.target,
                it.owners.joinToString(" / "),
            )
        }
        showEmbeddedMiuixDialog(activity, localizedText(ModuleText.CONFLICT_DETAILS)) { dismiss ->
            EmbeddedSettingsNotice(details)
            EmbeddedDialogActions(
                dismissText = localizedText(ModuleText.CLOSE),
                onDismiss = dismiss,
            )
        }
    }

    fun showDeleteConfirmation(status: PluginStatus) {
        if (!canShowDialog()) return
        val manifest = status.installed.manifest
        showEmbeddedMiuixDialog(
            activity = activity,
            title = localizedText(ModuleText.DELETE_NAMED_ITEM, manifest.name),
        ) { dismiss ->
            EmbeddedSettingsNotice(localizedText(ModuleText.PLUGIN_DELETE_NOTICE))
            EmbeddedDialogActions(
                dismissText = localizedText(ModuleText.CANCEL),
                onDismiss = dismiss,
                confirmText = localizedText(ModuleText.DELETE),
                onConfirm = {
                    dismiss()
                    action(manifest.id) { manager.store.delete(manifest.id) }
                },
            )
        }
    }

    DisposableEffect(manager, activity, revision, state.refreshKey, active) {
        if (!active) return@DisposableEffect onDispose {}
        state.active = true
        state.startupError = manager.startupError
        val live = AtomicBoolean(true)
        val refreshing = AtomicBoolean()
        if (state.statuses == null) state.loading = true

        val poll = object : Runnable {
            override fun run() {
                if (!live.get() || !refreshing.compareAndSet(false, true)) return
                val next = this
                manager.execute {
                    val statuses = runCatching { manager.statuses() }
                    mainHandler.post {
                        if (!live.get()) {
                            refreshing.set(false)
                            return@post
                        }
                        refreshing.set(false)
                        if (currentActivity() !== activity || activity.isFinishing || activity.isDestroyed) {
                            return@post
                        }
                        state.loading = false
                        state.startupError = manager.startupError
                        statuses.onSuccess {
                            state.statuses = it
                            state.readError = null
                        }.onFailure { error ->
                            state.readError = localizedText(ModuleText.PLUGINS_READ_FAILED, error.message)
                        }
                        mainHandler.postDelayed(next, 1_500L)
                    }
                }
            }
        }
        mainHandler.post(poll)
        onDispose {
            live.set(false)
            state.active = false
            mainHandler.removeCallbacks(poll)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmbeddedSettingsGroup {
            Text(
                text = localizedText(ModuleText.PLUGIN_IMPORT_HINT) + "\n" +
                    localizedText(ModuleText.PLUGIN_RESTART_NOTICE),
                modifier = Modifier.fillMaxWidth().padding(EmbeddedPreferencePadding),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
        }
        state.startupError?.let {
            EmbeddedSettingsNotice(localizedText(ModuleText.LOAD_ERROR, it))
        }
        state.readError?.let { EmbeddedSettingsNotice(it) }
        if (state.statuses == null && state.loading) {
            EmbeddedSettingsNotice(localizedText(ModuleText.PROCESSING))
        }
        state.statuses?.let { statuses ->
            if (statuses.isEmpty()) {
                EmbeddedSettingsNotice(localizedText(ModuleText.NO_PLUGINS))
            }
            statuses.forEach { status ->
                val manifest = status.installed.manifest
                val controlsEnabled = state.busyPluginId == null
                val runtimeLabel = statusLabel(status.state)
                val defaultMessage = when (status.state) {
                    PluginRunState.DISABLED -> ModuleText.PLUGIN_DISABLED
                    PluginRunState.LOADING -> ModuleText.PLUGIN_LOADING_MESSAGE
                    PluginRunState.ACTIVE -> ModuleText.PLUGIN_RUNNING_MESSAGE
                    else -> null
                }
                val diagnostic = status.message.trim().takeIf { message ->
                    message.isNotEmpty() && message != runtimeLabel &&
                        (defaultMessage == null ||
                            (message != localizedText(defaultMessage) && message != defaultMessage.text()))
                }
                val statusColor = when (status.state) {
                    PluginRunState.FAILED, PluginRunState.BLOCKED, PluginRunState.UNSUPPORTED ->
                        MiuixTheme.colorScheme.error
                    else -> MiuixTheme.colorScheme.onSurfaceContainer
                }
                EmbeddedSettingsGroup {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(EmbeddedPreferencePadding),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = manifest.name,
                            modifier = Modifier.semantics { heading() },
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = listOf(manifest.author, manifest.versionName)
                                .filter(String::isNotBlank).joinToString(" · "),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = manifest.id,
                            style = MiuixTheme.textStyles.body2,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = runtimeLabel + status.runningVersion?.let {
                                localizedText(ModuleText.PLUGIN_RUNNING_VERSION, it)
                            }.orEmpty(),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = statusColor,
                        )
                        diagnostic?.let { message ->
                            Text(
                                text = message,
                                style = MiuixTheme.textStyles.body2,
                                color = statusColor,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    EmbeddedSettingSwitch(
                        title = localizedText(ModuleText.ENABLE_NEXT_START),
                        summary = if (status.pendingRestart) {
                            localizedText(ModuleText.RESTART_PENDING)
                        } else {
                            null
                        },
                        checked = state.enabledOverrides[manifest.id] ?: status.installed.enabled,
                        enabled = controlsEnabled,
                        onCheckedChange = { enabled ->
                            if (state.active && state.busyPluginId == null) {
                                state.enabledOverrides = state.enabledOverrides + (manifest.id to enabled)
                                action(manifest.id) { manager.store.setEnabled(manifest.id, enabled) }
                            }
                        },
                    )
                    if (status.conflicts.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EmbeddedSettingLink(
                            title = localizedText(ModuleText.CONFLICT_DETAILS),
                            summary = localizedText(ModuleText.ITEM_COUNT, status.conflicts.size),
                            icon = EmbeddedSvgIcon.Conflict,
                            onClick = { showConflicts(status) },
                        )
                    }
                    if (status.state == PluginRunState.ACTIVE) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        EmbeddedSettingLink(
                            title = localizedText(ModuleText.PLUGIN_SETTINGS),
                            icon = EmbeddedSvgIcon.Settings,
                            enabled = controlsEnabled,
                            onClick = { openPluginSettings(activity, manifest.id) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    EmbeddedSettingLink(
                        title = localizedText(ModuleText.DELETE),
                        icon = EmbeddedSvgIcon.Delete,
                        enabled = controlsEnabled,
                        onClick = { showDeleteConfirmation(status) },
                    )
                }
            }
        }
    }
}

internal fun EmbeddedSettingsHost.importPluginZip(uri: Uri) {
    val manager = plugins ?: return
    val activity = currentActivity() ?: return
    val settingsDialog = dialogReference?.takeIf { it.get()?.isShowing == true } ?: return
    fun ownsImport() = currentActivity() === activity && settingsDialog === dialogReference &&
        settingsDialog.get()?.isShowing == true && !activity.isFinishing && !activity.isDestroyed
    manager.execute {
        val result = runCatching {
            val input = application.contentResolver.openInputStream(uri)
                ?: error(localizedText(ModuleText.FILE_UNREADABLE))
            manager.store.prepare(input, Build.VERSION.SDK_INT)
        }
        mainHandler.post result@{
            if (!ownsImport()) {
                result.getOrNull()?.let { prepared -> manager.execute { runCatching { prepared.close() } } }
                return@result
            }
            result.onFailure {
                Toast.makeText(activity, localizedText(ModuleText.IMPORT_FAILED, it.message), Toast.LENGTH_LONG).show()
            }
            val prepared = result.getOrNull() ?: return@result
            // Read the installed version off the UI thread before showing replacement details.
            manager.execute {
                val oldResult = runCatching {
                    manager.store.installed().firstOrNull { it.manifest.id == prepared.manifest.id }
                }
                mainHandler.post confirm@{
                    val owned = ownsImport()
                    if (!owned || oldResult.isFailure) {
                        if (owned && oldResult.isFailure) {
                            Toast.makeText(
                                activity,
                                localizedText(
                                    ModuleText.INSTALLED_PLUGIN_READ_FAILED,
                                    oldResult.exceptionOrNull()?.message,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        manager.execute { runCatching { prepared.close() } }
                        return@confirm
                    }

                    val old = oldResult.getOrNull()
                    val committed = AtomicBoolean()
                    val released = AtomicBoolean()
                    fun releasePrepared() {
                        if (released.compareAndSet(false, true)) {
                            manager.execute { runCatching { prepared.close() } }
                        }
                    }
                    val title = localizedText(if (old == null) ModuleText.IMPORT_PLUGIN else ModuleText.REPLACE_PLUGIN)
                    val details = localizedText(
                        ModuleText.PLUGIN_AUTHOR_DETAILS,
                        prepared.manifest.name,
                        prepared.manifest.author,
                    ) + (old?.let { "${it.manifest.versionName} → " } ?: "") +
                        prepared.manifest.versionName + "\n" + prepared.manifest.description + "\n" +
                        if (old == null) {
                            localizedText(ModuleText.PLUGIN_IMPORTED_DISABLED)
                        } else {
                            localizedText(ModuleText.PLUGIN_REPLACEMENT_NOTICE)
                        }
                    try {
                        showEmbeddedMiuixDialog(
                            activity = activity,
                            title = title,
                            onDismiss = { if (!committed.get()) releasePrepared() },
                        ) { dismiss ->
                            EmbeddedSettingsNotice(details)
                            EmbeddedDialogActions(
                                dismissText = localizedText(ModuleText.CANCEL),
                                onDismiss = dismiss,
                                confirmText = localizedText(if (old == null) ModuleText.IMPORT else ModuleText.REPLACE),
                                onConfirm = {
                                    if (committed.compareAndSet(false, true)) {
                                        dismiss()
                                        manager.execute {
                                            val saved = runCatching { manager.store.commit(prepared) }
                                            runCatching { prepared.close() }
                                            mainHandler.post {
                                                if (!ownsImport()) return@post
                                                val failure = saved.exceptionOrNull()
                                                val message = if (failure == null) {
                                                    localizedText(ModuleText.PLUGIN_IMPORTED)
                                                } else {
                                                    failure.message.orEmpty().ifBlank {
                                                        localizedText(ModuleText.OPERATION_FAILED)
                                                    }
                                                }
                                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                                                if (failure == null) pageRefresh?.invoke()
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    } catch (_: Throwable) {
                        releasePrepared()
                    }
                }
            }
        }
    }
}

internal fun EmbeddedSettingsHost.openPluginSettings(activity: Activity, id: String) {
    if (activity.isFinishing || activity.isDestroyed || currentActivity() !== activity) return
    val session = plugins?.openSettings(id, embeddedThemedContext(activity))
        ?: return Toast.makeText(
            activity,
            localizedText(ModuleText.PLUGIN_NO_SETTINGS),
            Toast.LENGTH_SHORT,
        ).show()
    val closed = AtomicBoolean()
    fun closeSession() {
        if (closed.compareAndSet(false, true)) runCatching { session.close() }
    }
    try {
        val pluginView = session.view
        check(pluginView.parent == null) { localizedText(ModuleText.SETTINGS_OPEN_FAILED, id) }
        showFullScreenPluginSettings(activity, pluginView, ::closeSession)
    } catch (error: Throwable) {
        closeSession()
        Toast.makeText(
            activity,
            localizedText(ModuleText.SETTINGS_OPEN_FAILED, error.message),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** Lets the plugin's own top-bar back button close the host full-screen page. */
internal object PluginSettingsBackBridge {
    @Volatile
    var closeCurrent: (() -> Unit)? = null

    @JvmStatic
    fun close() { closeCurrent?.invoke() }
}

/** Plugin settings as a full page: no OverlayDialog card, no dimmed modal sheet. */
private fun EmbeddedSettingsHost.showFullScreenPluginSettings(
    activity: Activity,
    pluginView: android.view.View,
    onClose: () -> Unit,
) {
    if (activity.isFinishing || activity.isDestroyed) return
    val closed = java.util.concurrent.atomic.AtomicBoolean()
    fun close() {
        if (closed.compareAndSet(false, true)) {
            PluginSettingsBackBridge.closeCurrent = null
            onClose()
        }
    }
    var requestDismiss: (() -> Unit)? = null
    val dialog = createEmbeddedSettingsDialog(
        activity = activity,
        onBack = { dismiss -> dismiss() },
        onDismiss = ::close,
    ) { dismiss ->
        requestDismiss = dismiss
        AndroidView(factory = { pluginView }, modifier = Modifier.fillMaxSize())
    }
    // Use the dialog's animated dismiss path (slide-out) instead of dialog.dismiss(),
    // which would tear the window down immediately.
    PluginSettingsBackBridge.closeCurrent = { requestDismiss?.invoke() ?: dialog.dismiss() }
    // The plugin page's top-bar back button calls Activity.onBackPressed(). While this
    // dialog is open, consume it here so it dismisses the dialog instead of finishing
    // Apple Music (the system back gesture already reaches the dialog's own callback).
    val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { dialog.dismiss() }
    }
    (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(activity, backCallback)
    dialog.setOnDismissListener { backCallback.remove() }
    dialog.show()
}

@Stable
private class EmbeddedPluginManagementState {
    var active by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var statuses by mutableStateOf<List<PluginStatus>?>(null)
    var startupError by mutableStateOf<String?>(null)
    var readError by mutableStateOf<String?>(null)
    var busyPluginId by mutableStateOf<String?>(null)
    var enabledOverrides by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var refreshKey by mutableStateOf(0)
}
