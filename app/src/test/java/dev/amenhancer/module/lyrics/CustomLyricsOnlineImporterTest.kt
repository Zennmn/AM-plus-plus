package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsOnlineImporterTest {
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"

    @Test
    fun `amll is fetched only for the user supplied apple music id`() {
        var requestedId = 0L
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { id -> requestedId = id; ttml },
            fetchAmLyrics = { error("must not fetch AM-Lyrics") },
            fetchNeteaseYrc = { error("must not fetch NetEase") },
        )

        val result = importer.importAmll(42L)

        assertEquals(42L, requestedId)
        assertEquals(
            CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.AMLL),
            result,
        )
    }

    @Test
    fun `netease import needs an explicit netease id and serializes word timing`() {
        var requestedId = 0L
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { error("must not fetch AMLL") },
            fetchAmLyrics = { error("must not fetch AM-Lyrics") },
            fetchNeteaseYrc = { id ->
                requestedId = id
                LyricDocument(listOf(LyricLine(0, 1_000, listOf(LyricWord("word", 0, 1_000)))))
            },
        )

        val result = importer.importNetease(99L, "Song")

        assertEquals(99L, requestedId)
        assertTrue(result is CustomLyricsOnlineImportResult.Imported)
        assertEquals(CustomLyricsSources.NETEASE, (result as CustomLyricsOnlineImportResult.Imported).source)
    }

    @Test
    fun `am lyrics import uses the supplied apple music id and source`() {
        var requestedId = 0L
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { error("must not fetch AMLL") },
            fetchAmLyrics = { id -> requestedId = id; ttml },
            fetchNeteaseYrc = { error("must not fetch NetEase") },
        )

        val result = importer.importAmLyrics(7335408332109193189L)

        assertEquals(7335408332109193189L, requestedId)
        assertEquals(
            CustomLyricsOnlineImportResult.Imported(ttml, CustomLyricsSources.AM_LYRICS),
            result,
        )
    }

    @Test
    fun `am lyrics import fails open for invalid ids and invalid ttml`() {
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { error("must not fetch AMLL") },
            fetchAmLyrics = { "not ttml" },
            fetchNeteaseYrc = { error("must not fetch NetEase") },
        )

        assertTrue(importer.importAmLyrics(0L) is CustomLyricsOnlineImportResult.Failed)
        assertTrue(importer.importAmLyrics(42L) is CustomLyricsOnlineImportResult.Failed)
    }
}
