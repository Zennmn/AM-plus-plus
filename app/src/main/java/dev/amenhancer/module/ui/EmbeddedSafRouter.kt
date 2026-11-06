package dev.amenhancer.module.ui


internal val EMBEDDED_FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "application/vnd.ms-opentype",
)

/** Stable, locale-aware signals for the fixed Apple Music settings surface. */
/** Matches the fixed PlayerActivity across subclasses and class-loader copies. */
internal enum class EmbeddedSafOperation {
    Font,
    Ttml,
    EditorTtml,
    Backup,
    RestoreOverwrite,
    RestoreKeepExisting,
    PluginZip,
}

internal data class EmbeddedSafPending(
    val requestCode: Int,
    val operation: EmbeddedSafOperation,
)

internal object EmbeddedSafResult {
    const val RESULT_CANCELED = 0
    const val RESULT_OK = -1
}

internal sealed interface EmbeddedSafRoute {
    data object Ignored : EmbeddedSafRoute

    data class Canceled(
        val operation: EmbeddedSafOperation,
    ) : EmbeddedSafRoute

    data class Selected(
        val operation: EmbeddedSafOperation,
        val uri: String,
    ) : EmbeddedSafRoute
}

/**
 * Owns only the module's pending SAF request. A result for any other request
 * code is ignored and leaves the pending request untouched for the host.
 */
internal class EmbeddedSafResultRouter {
    private var pendingRequest: EmbeddedSafPending? = null

    fun begin(operation: EmbeddedSafOperation): Int {
        val requestCode = when (operation) {
            EmbeddedSafOperation.Font -> REQUEST_PICK_FONT
            EmbeddedSafOperation.Ttml -> REQUEST_PICK_TTML
            EmbeddedSafOperation.EditorTtml -> REQUEST_PICK_EDITOR_TTML
            EmbeddedSafOperation.Backup -> REQUEST_CREATE_BACKUP
            EmbeddedSafOperation.RestoreOverwrite -> REQUEST_RESTORE_BACKUP
            EmbeddedSafOperation.RestoreKeepExisting -> REQUEST_RESTORE_BACKUP_KEEP
            EmbeddedSafOperation.PluginZip -> REQUEST_PICK_PLUGIN
        }
        pendingRequest = EmbeddedSafPending(requestCode, operation)
        return requestCode
    }

    fun pending(): EmbeddedSafPending? = pendingRequest

    fun route(
        requestCode: Int,
        resultCode: Int,
        uri: String?,
    ): EmbeddedSafRoute {
        val pending = pendingRequest ?: return EmbeddedSafRoute.Ignored
        if (pending.requestCode != requestCode) return EmbeddedSafRoute.Ignored

        pendingRequest = null
        return if (resultCode == EmbeddedSafResult.RESULT_OK && !uri.isNullOrBlank()) {
            EmbeddedSafRoute.Selected(pending.operation, uri)
        } else {
            EmbeddedSafRoute.Canceled(pending.operation)
        }
    }

    companion object {
        const val REQUEST_PICK_FONT = 6511
        const val REQUEST_PICK_TTML = 6512
        const val REQUEST_CREATE_BACKUP = 6513
        const val REQUEST_RESTORE_BACKUP = 6514
        const val REQUEST_RESTORE_BACKUP_KEEP = 6515
        const val REQUEST_PICK_PLUGIN = 6516
        const val REQUEST_PICK_EDITOR_TTML = 6517
        val OWN_REQUEST_CODES: Set<Int> = setOf(
            REQUEST_PICK_FONT,
            REQUEST_PICK_TTML,
            REQUEST_CREATE_BACKUP,
            REQUEST_RESTORE_BACKUP,
            REQUEST_RESTORE_BACKUP_KEEP,
            REQUEST_PICK_PLUGIN,
            REQUEST_PICK_EDITOR_TTML,
        )
    }
}

