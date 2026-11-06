package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import java.util.concurrent.atomic.AtomicBoolean

internal fun EmbeddedSettingsHost.updateEmbeddedLyrics(activity: Activity) {
    val owner = dialogReference ?: return
    if (owner.get()?.isShowing != true || activity.isFinishing || activity.isDestroyed) return
    val cancelled = AtomicBoolean(false)
    val progress = mutableStateOf(localizedText(ModuleText.CHECKING_LYRICS))
    val dialog = showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(ModuleText.LYRICS_UPDATE),
        dismissOnOutside = false,
        onDismiss = { cancelled.set(true) },
    ) { dismiss ->
        EmbeddedSettingsNotice(progress.value)
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = {
                cancelled.set(true)
                dismiss()
            },
        )
    }
    if (!dialog.isShowing) return
    worker.execute {
        val result = runCatching {
            controller.updateLyrics(
                isCancelled = cancelled::get,
                onProgress = { update ->
                    mainHandler.post {
                        if (dialog.isShowing) {
                            progress.value = localizedText(
                                ModuleText.LYRICS_UPDATE_PROGRESS,
                                update.checkedEntries,
                                update.totalEntries,
                                update.updatedEntries,
                                update.unchangedEntries,
                                update.skippedEntries,
                                update.failedEntries,
                            )
                        }
                    }
                },
            )
        }.getOrElse { error ->
            CustomLyricsUpdateResult.Failed(
                localizedText(ModuleText.LYRICS_UPDATE_FAILED, error.message.orEmpty()),
            )
        }
        mainHandler.post {
            if (dialog.isShowing) dialog.dismiss()
            val current = currentActivity()?.takeIf { it === activity && owner === dialogReference } ?: return@post
            when (result) {
                is CustomLyricsUpdateResult.Updated -> Toast.makeText(
                    current,
                    localizedText(
                        ModuleText.LYRICS_UPDATE_COMPLETE,
                        result.checked,
                        result.updated,
                        result.unchanged,
                        result.skipped,
                        result.failed,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                CustomLyricsUpdateResult.Cancelled -> Toast.makeText(
                    current,
                    localizedText(ModuleText.LYRICS_UPDATE_CANCELLED),
                    Toast.LENGTH_SHORT,
                ).show()
                is CustomLyricsUpdateResult.Failed -> Toast.makeText(
                    current,
                    result.message,
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (result is CustomLyricsUpdateResult.Updated) pageRefresh?.invoke()
        }
    }
}

internal fun EmbeddedSettingsHost.launchSafPicker(
    activity: Activity,
    operation: EmbeddedSafOperation,
    mimeType: String,
    extraMimeTypes: Array<String>? = null,
) {
    val owner = dialogReference?.takeIf { it.get()?.isShowing == true } ?: return
    val requestCode = safRouter.begin(operation)
    pendingSafDialog = owner
    val intent = Intent(
        if (operation == EmbeddedSafOperation.Backup) {
            Intent.ACTION_CREATE_DOCUMENT
        } else {
            Intent.ACTION_OPEN_DOCUMENT
        },
    )
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mimeType)
    extraMimeTypes?.let { intent.putExtra(Intent.EXTRA_MIME_TYPES, it) }
    if (operation == EmbeddedSafOperation.Backup) {
        intent.putExtra(Intent.EXTRA_TITLE, "AMPP-lyrics-backup.zip")
    }
    runCatching { activity.startActivityForResult(intent, requestCode) }
        .onFailure {
            safRouter.route(requestCode, EmbeddedSafResult.RESULT_CANCELED, null)
            pendingSafDialog = null
            if (operation == EmbeddedSafOperation.EditorTtml) pendingTtmlImport = null
            Toast.makeText(activity, localizedText(ModuleText.PICKER_OPEN_FAILED), Toast.LENGTH_SHORT).show()
        }
}

internal fun EmbeddedSettingsHost.handleSafSelection(operation: EmbeddedSafOperation, uri: Uri) {
    val activity = currentActivity() ?: return
    when (operation) {
        EmbeddedSafOperation.PluginZip -> importPluginZip(uri)
        EmbeddedSafOperation.Font -> runAsync(activity) { controller.importFont(uri) }
        EmbeddedSafOperation.EditorTtml -> {
            val editorImport = pendingTtmlImport ?: return
            pendingTtmlImport = null
            val owner = dialogReference
            worker.execute {
                val imported = runCatching { controller.readTtml(uri) }.getOrNull()
                mainHandler.post {
                    if (owner === dialogReference && currentActivity() === activity) editorImport(imported)
                }
            }
        }
        EmbeddedSafOperation.Ttml -> {
            val song = controller.currentSongDetails()
            if (song == null) {
                Toast.makeText(activity, localizedText(ModuleText.NO_CURRENT_SONG), Toast.LENGTH_SHORT).show()
            } else {
                runAsync(activity) {
                    val replacing = controller.lyricsEntries()
                        .firstOrNull { it.appleMusicId == song.appleMusicId }
                        ?.appleMusicId
                    controller.importTtml(
                        uri,
                        song.appleMusicId,
                        song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                        replacing,
                    )
                }
            }
        }
        EmbeddedSafOperation.Backup -> runAsync(activity) { controller.backupLyrics(uri) }
        EmbeddedSafOperation.RestoreOverwrite -> confirmEmbeddedRestore(activity, uri)
        EmbeddedSafOperation.RestoreKeepExisting -> runAsync(activity) {
            controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
        }
    }
}

internal fun EmbeddedSettingsHost.confirmEmbeddedRestore(activity: Activity, uri: Uri) {
    if (activity.isFinishing || activity.isDestroyed) return
    showEmbeddedMiuixDialog(
        activity = activity,
        title = localizedText(ModuleText.RESTORE_LYRICS_BACKUP),
    ) { dismiss ->
        EmbeddedSettingsNotice(localizedText(ModuleText.RESTORE_CONFLICT_NOTICE))
        EmbeddedSettingsGroup {
            EmbeddedSettingLink(
                title = localizedText(ModuleText.KEEP_EXISTING),
                icon = EmbeddedSvgIcon.BackupRestore,
                onClick = {
                    dismiss()
                    runAsync(activity) {
                        controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
                    }
                },
            )
        }
        EmbeddedDialogActions(
            dismissText = localizedText(ModuleText.CANCEL),
            onDismiss = dismiss,
            confirmText = localizedText(ModuleText.OVERWRITE),
            onConfirm = {
                dismiss()
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
                }
            },
        )
    }
}

internal fun EmbeddedSettingsHost.runAsync(activity: Activity, action: () -> EmbeddedActionResult) {
    val owner = dialogReference?.takeIf { it.get()?.isShowing == true } ?: return
    Toast.makeText(activity, localizedText(ModuleText.PROCESSING), Toast.LENGTH_SHORT).show()
    worker.execute {
        val result = runCatching(action).getOrElse {
            EmbeddedActionResult.Failed(it.message.orEmpty().ifBlank { localizedText(ModuleText.OPERATION_FAILED) })
        }
        mainHandler.post {
            val current = currentActivity()?.takeIf {
                it === activity && owner === dialogReference && owner.get()?.isShowing == true
            } ?: return@post
            val message = when (result) {
                is EmbeddedActionResult.Done -> result.message
                is EmbeddedActionResult.Failed -> result.message
            }
            Toast.makeText(
                current,
                message,
                if (result is EmbeddedActionResult.Done) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
            if (result is EmbeddedActionResult.Done) {
                pageRefresh?.invoke()
            }
        }
    }
}
