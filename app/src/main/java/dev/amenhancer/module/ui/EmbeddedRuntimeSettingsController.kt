package dev.amenhancer.module.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.EmbeddedLyricsMutationResult
import dev.amenhancer.module.font.FontFilePolicy
import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.hook.AmLyricsClient
import dev.amenhancer.module.hook.AmllTtmlClient
import dev.amenhancer.module.hook.HttpLyricTransport
import dev.amenhancer.module.hook.NeteaseLyricClient
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.lyrics.CustomLyricsRestoreResult
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import java.io.FileOutputStream

internal enum class EmbeddedOnlineSource {
    AMLL,
    AM_LYRICS,
    NETEASE,
}

internal sealed interface EmbeddedActionResult {
    data class Done(val message: String) : EmbeddedActionResult
    data class Failed(val message: String) : EmbeddedActionResult
}

/** Android/SAF/network facade consumed only by the in-host settings dialog. */
internal class EmbeddedRuntimeSettingsController(
    context: Context,
    private val session: EmbeddedConfigurationSession,
    private val currentSong: () -> CurrentSongDetails?,
) : EmbeddedSettingsController {
    private val appContext = context.applicationContext
    private val content = EmbeddedContentManager(
        session = session,
        validateTypeface = ::canBuildTypeface,
    )

    override fun currentSettings(): ModuleSettings = session.settings()

    override fun saveOrdinarySettings(settings: ModuleSettings): Boolean = session.saveSettings(settings)

    override fun currentSongDetails(): CurrentSongDetails? = currentSong()

    override fun lyricsEntries(): List<CustomLyricsEntry> = content.listLyrics()

    override fun readLyrics(appleMusicId: Long): String? = content.readLyrics(appleMusicId)

    override fun saveLyrics(
        draft: CustomLyricsDraft,
        replacingAppleMusicId: Long?,
    ): EmbeddedActionResult = content.saveLyrics(draft, replacingAppleMusicId).toActionResult()

    override fun setLyricsEnabled(appleMusicId: Long, enabled: Boolean): EmbeddedActionResult =
        content.setLyricsEnabled(appleMusicId, enabled).toActionResult("歌词状态已更新")

    override fun deleteLyrics(appleMusicId: Long): EmbeddedActionResult =
        content.deleteLyrics(appleMusicId).toActionResult("歌词映射已删除")

    override fun importFont(uri: Uri): EmbeddedActionResult {
        val bytes = try {
            appContext.contentResolver.openInputStream(uri)?.use(FontFilePolicy::readBounded)
                ?: return EmbeddedActionResult.Failed("无法读取所选字体")
        } catch (_: FontFilePolicy.FontSizeLimitExceeded) {
            return EmbeddedActionResult.Failed("字体超过 16 MiB")
        } catch (_: Throwable) {
            return EmbeddedActionResult.Failed("无法读取所选字体")
        }
        return when (val result = content.importFont(displayName(uri, "导入字体"), bytes)) {
            is FontImportResult.Imported -> EmbeddedActionResult.Done("字体已导入，重启 Apple Music 后生效")
            is FontImportResult.Failed -> EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun clearFont(): EmbeddedActionResult =
        if (content.clearFont()) {
            EmbeddedActionResult.Done("已清除自定义字体，重启 Apple Music 后生效")
        } else {
            EmbeddedActionResult.Failed("清除字体失败")
        }

    override fun importTtml(
        uri: Uri,
        appleMusicId: Long,
        displayName: String,
        replacingAppleMusicId: Long?,
    ): EmbeddedActionResult {
        val ttml = try {
            appContext.contentResolver.openInputStream(uri)?.use(CustomLyricsFilePolicy::readBounded)
                ?.toString(Charsets.UTF_8)
                ?: return EmbeddedActionResult.Failed("无法读取 TTML")
        } catch (_: Throwable) {
            return EmbeddedActionResult.Failed("无法读取 TTML")
        }
        return content.saveLyrics(
            CustomLyricsDraft(
                appleMusicId = appleMusicId,
                displayName = displayName,
                ttml = ttml,
                source = CustomLyricsSources.MANUAL,
            ),
            replacingAppleMusicId,
        ).toActionResult()
    }

    override fun backupLyrics(uri: Uri): EmbeddedActionResult {
        val output = runCatching { appContext.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: return EmbeddedActionResult.Failed("无法创建备份文件")
        return when (val result = output.use(content::backupLyrics)) {
            is dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult.Encoded ->
                EmbeddedActionResult.Done("已备份 ${result.entryCount} 条歌词")
            is dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult.Failed ->
                EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun restoreLyrics(
        uri: Uri,
        policy: dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy,
    ): EmbeddedActionResult {
        val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return EmbeddedActionResult.Failed("无法读取备份文件")
        return when (val result = input.use { content.restoreLyrics(it, policy) }) {
            is CustomLyricsRestoreResult.Restored ->
                EmbeddedActionResult.Done("已恢复 ${result.manifest.entries.size} 条歌词")
            is CustomLyricsRestoreResult.Failed -> EmbeddedActionResult.Failed(result.message)
        }
    }

    override fun importOnlineLyrics(
        source: EmbeddedOnlineSource,
        appleMusicId: Long,
        neteaseSongId: Long?,
        displayName: String,
    ): EmbeddedActionResult {
        val transport = HttpLyricTransport()
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = AmllTtmlClient(transport)::fetch,
            fetchAmLyrics = AmLyricsClient(transport)::fetch,
            fetchNeteaseYrc = NeteaseLyricClient(transport)::fetchYrc,
        )
        val imported = when (source) {
            EmbeddedOnlineSource.AMLL -> importer.importAmll(appleMusicId)
            EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(appleMusicId)
            EmbeddedOnlineSource.NETEASE -> importer.importNetease(
                neteaseSongId ?: return EmbeddedActionResult.Failed("需要网易云歌曲 ID"),
                displayName,
            )
        }
        return when (imported) {
            is CustomLyricsOnlineImportResult.Failed -> EmbeddedActionResult.Failed(imported.message)
            is CustomLyricsOnlineImportResult.Imported -> content.importOnlineLyrics(
                appleMusicId = appleMusicId,
                displayName = displayName,
                imported = imported,
                replacingAppleMusicId = content.listLyrics()
                    .firstOrNull { it.appleMusicId == appleMusicId }
                    ?.appleMusicId,
            ).toActionResult()
        }
    }

    private fun displayName(uri: Uri, fallback: String): String = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let(cursor::getString)
        }
    }.getOrNull().orEmpty().ifBlank { fallback }

    private fun canBuildTypeface(bytes: ByteArray): Boolean {
        val temporary = runCatching { File.createTempFile("ampp-font-", ".tmp", appContext.cacheDir) }
            .getOrNull() ?: return false
        return try {
            FileOutputStream(temporary).use { it.write(bytes) }
            Typeface.Builder(temporary).build() != null
        } catch (_: Throwable) {
            false
        } finally {
            temporary.delete()
        }
    }
}

private fun CustomLyricsSaveResult.toActionResult(): EmbeddedActionResult = when (this) {
    is CustomLyricsSaveResult.Saved -> EmbeddedActionResult.Done("歌词已保存")
    is CustomLyricsSaveResult.Failed -> EmbeddedActionResult.Failed(message)
}

private fun EmbeddedLyricsMutationResult.toActionResult(success: String): EmbeddedActionResult = when (this) {
    is EmbeddedLyricsMutationResult.Updated -> EmbeddedActionResult.Done(success)
    is EmbeddedLyricsMutationResult.Failed -> EmbeddedActionResult.Failed(message)
}
