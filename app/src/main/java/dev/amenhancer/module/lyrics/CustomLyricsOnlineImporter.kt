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
    private val fetchLunabeat: (Long) -> String?,
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

    fun importLunabeat(appleMusicId: Long): CustomLyricsOnlineImportResult {
        if (appleMusicId <= 0L) return CustomLyricsOnlineImportResult.Failed(
            "Apple Music ID 必须是正整数",
        )
        val ttml = runCatching { fetchLunabeat(appleMusicId) }.getOrNull()
            ?.takeIf(TtmlInputPolicy::isAcceptable)
            ?: return CustomLyricsOnlineImportResult.Failed("Lunabeat 未找到可用 TTML")
        return CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.LUNABEAT)
    }
}
