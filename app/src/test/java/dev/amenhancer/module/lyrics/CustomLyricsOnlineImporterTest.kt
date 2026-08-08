package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `amll formatted lyrics are reformatted into the apple music format on import`() {
        val amllFormat = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
            <body dur="00:03.000"><div xmlns="" begin="00:01.000" end="00:03.000">
            <p begin="00:01.000" end="00:03.000" ttm:agent="v1" itunes:key="L1">
            <span begin="00:01.000" end="00:03.000">word</span></p></div></body></tt>
        """.trimIndent()
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { amllFormat },
            fetchAmLyrics = { error("must not fetch AM-Lyrics") },
            fetchNeteaseYrc = { error("must not fetch NetEase") },
        )

        val result = importer.importAmll(42L)

        assertTrue(result is CustomLyricsOnlineImportResult.Imported)
        val imported = result as CustomLyricsOnlineImportResult.Imported
        assertTrue(imported.reformatted)
        assertEquals(CustomLyricsSources.AMLL, imported.source)
        assertFalse(imported.ttml.contains("""xmlns=""""))
        assertTrue(imported.ttml.contains("""itunes:timing="Word""""))
        assertTrue(TtmlInputPolicy.isAcceptable(imported.ttml))
    }

    @Test
    fun `apple formatted amll payloads are imported unchanged`() {
        val importer = CustomLyricsOnlineImporter(
            fetchAmll = { ttml },
            fetchAmLyrics = { error("must not fetch AM-Lyrics") },
            fetchNeteaseYrc = { error("must not fetch NetEase") },
        )

        val result = importer.importAmll(42L)

        assertEquals(
            CustomLyricsOnlineImportResult.Imported(
                ttml,
                CustomLyricsSources.AMLL,
                reformatted = false,
            ),
            result,
        )
    }
}
