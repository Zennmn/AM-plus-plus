package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsSources

internal sealed interface CustomLyricsOnlineImportResult {
    data class Imported(
        val ttml: String,
        val source: String,
        /** True when the AMLL TTML format was rewritten into the Apple Music format. */
        val reformatted: Boolean = false,
    ) : CustomLyricsOnlineImportResult

    data class Failed(val message: String) : CustomLyricsOnlineImportResult
}

/** User-triggered online imports. Playback hooks never call this class. */
internal class CustomLyricsOnlineImporter(
    private val fetchAmll: (Long) -> String?,
    private val fetchAmLyrics: (Long) -> String?,
    private val fetchNeteaseYrc: (Long) -> LyricDocument?,
) {
    fun importAmll(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed("Apple Music ID 必须是正整数")
        val fetched = runCatching { fetchAmll(appleMusicId) }.getOrNull()
            ?: return CustomLyricsOnlineImportResult.Failed("AMLL 未找到可用 TTML")
        // AMLL serves its own TTML format; reformat it before Apple's parser sees it.
        val conversion = AmllTtmlFormatConverter.toAppleFormat(fetched)
        val ttml = conversion.ttml.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("AMLL 未找到可用 TTML")
        return CustomLyricsOnlineImportResult.Imported(
            ttml = ttml,
            source = CustomLyricsSources.AMLL,
            reformatted = conversion.converted,
        )
    }

    fun importAmLyrics(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed(
            "Apple Music ID 必须是正整数",
        )
        val ttml = runCatching { fetchAmLyrics(appleMusicId) }.getOrNull()
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("GitHub 未找到可用 TTML")
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.AM_LYRICS)
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
