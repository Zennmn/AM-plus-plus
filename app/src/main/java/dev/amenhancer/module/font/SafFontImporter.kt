package dev.amenhancer.module.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** SAF adapter; it deliberately keeps the selected URI only for the duration of one import. */
internal class SafFontImporter(
    context: Context,
    private val snapshot: XposedServiceSnapshot,
    private val configStore: ConfigStore,
) {
    private val appContext = context.applicationContext

    fun import(uri: Uri): FontImportResult {
        if (!isWritable()) return FontImportResult.Failed("libxposed remote file service is unavailable")

        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use(FontFilePolicy::readBounded)
                ?: return FontImportResult.Failed("Unable to read selected font")
        } catch (_: Throwable) {
            return FontImportResult.Failed("Unable to read selected font")
        }

        val displayName = queryDisplayName(uri)
        val oldManifest = configStore.settings(snapshot).fontManifest
        val result = FontImportTransaction(
            fileIdFactory = ::newFileId,
            writeRemoteFile = ::writeRemoteFile,
            publishManifest = { manifest ->
                if (!isWritable()) false else configStore.saveFontManifest(manifest, snapshot)
            },
            deleteRemoteFile = { fileId ->
                if (ModuleApplication.isCurrentSnapshot(snapshot)) snapshot.deleteRemoteFile(fileId)
            },
            validateTypeface = ::canBuildTypeface,
        ).import(displayName, bytes)
        if (result is FontImportResult.Imported && oldManifest.enabled &&
            oldManifest.fileId != result.manifest.fileId && ModuleApplication.isCurrentSnapshot(snapshot)
        ) {
            // The new manifest is already committed, so deleting the now-unused
            // old file cannot make a failed import visible as a broken manifest.
            snapshot.deleteRemoteFile(oldManifest.fileId)
        }
        return result
    }

    private fun isWritable(): Boolean =
        snapshot.isRemoteFileAvailable && ModuleApplication.isCurrentSnapshot(snapshot)

    private fun writeRemoteFile(fileId: String, bytes: ByteArray): Boolean {
        if (!isWritable()) return false
        val descriptor = snapshot.openRemoteFile(fileId) ?: return false
        return runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
            }
            true
        }.getOrDefault(false)
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
            } else {
                null
            }
        }
    }.getOrNull().orEmpty().ifBlank { "导入字体" }

    private fun canBuildTypeface(bytes: ByteArray): Boolean {
        val temporary = runCatching {
            File.createTempFile("ampp-font-", ".tmp", appContext.cacheDir)
        }.getOrNull() ?: return false
        return try {
            FileOutputStream(temporary).use { output -> output.write(bytes) }
            Typeface.Builder(temporary).build() != null
        } catch (_: Throwable) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun newFileId(): String = "font_" + UUID.randomUUID().toString().replace("-", "")
}
