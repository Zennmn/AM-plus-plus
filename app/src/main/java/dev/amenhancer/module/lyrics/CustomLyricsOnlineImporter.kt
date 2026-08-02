package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsSources

internal sealed interface CustomLyricsOnlineImportResult {
    data class Imported(
        val ttml: String,
        val source: String,
    ) : CustomLyricsOnlineImportResult

    data class Failed(val message: String) : CustomLyricsOnlineImportResult
}

/** User-triggered online imports. Playback hooks never call this class. */
internal class CustomLyricsOnlineImporter(
    private val fetchAmll: (Long) -> String?,
    private val fetchNeteaseYrc: (Long) -> LyricDocument?,
) {
    fun importAmll(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed("Apple Music ID 必须是正整数")
        val ttml = runCatching { fetchAmll(appleMusicId) }.getOrNull()
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("AMLL 未找到可用 TTML")
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.AMLL)
    }

    fun importNetease(
        neteaseSongId: Long,
        title: String,
    ): CustomLyricsOnlineImportResult {
        if (neteaseSongId <= 0L) return CustomLyricsOnlineImportResult.Failed("网易云歌曲 ID 必须是正整数")
        val document = runCatching { fetchNeteaseYrc(neteaseSongId) }.getOrNull()
            ?: return CustomLyricsOnlineImportResult.Failed("网易云未找到逐字歌词")
        val ttml = WordTtmlSerializer.serialize(document, title.takeIf(String::isNotBlank))
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("网易云歌词无法转换为有效 TTML")
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.NETEASE)
    }
}
