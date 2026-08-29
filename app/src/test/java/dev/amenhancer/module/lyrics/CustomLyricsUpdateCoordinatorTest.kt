package dev.amenhancer.module.lyrics

import dev.amenhancer.module.hook.AmLyricsIndex
import dev.amenhancer.module.hook.AmLyricsIndexEntry
import dev.amenhancer.module.hook.LunabeatCatalog
import dev.amenhancer.module.hook.LunabeatManifest
import dev.amenhancer.module.hook.LunabeatSong
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CustomLyricsUpdateCoordinatorTest {

    @Test
    fun `amll canonical conversion recognizes unchanged and changed hashes`() {
        val raw = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p itunes:key=\"L1\"><span ttm:role=\"x-translation\">Hi</span>歌</p></div></body></tt>"
        val converted = AmllTtmlFormatConverter.toAppleFormat(raw).ttml
        val same = entry(7L, CustomLyricsSources.AMLL, converted, "lyrics_same")
        val changed = entry(8L, CustomLyricsSources.AMLL, ttml("old"), "lyrics_changed")
        val writes = AtomicInteger()
        val result = coordinator(
            fetchAmll = { raw },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(same, changed)),
            fileIdFactory = object : () -> String {
                var index = 0
                override fun invoke() = "lyrics_new_${index++}"
            },
            writeRemoteFile = { _, _ -> writes.incrementAndGet(); true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(2, result.checked)
        assertEquals(1, result.unchanged)
        assertEquals(1, result.updated)
        assertEquals(1, writes.get())
    }

    @Test
    fun `am lyrics index fast path avoids body request`() {
        val bytes = ttml("same").toByteArray()
        val local = entry(42L, CustomLyricsSources.AM_LYRICS, ttml("same"), "lyrics_am")
        val bodyFetches = AtomicInteger()
        val remote = AmLyricsIndexEntry(
            appleMusicId = 42L,
            alternateIds = listOf(420L),
            displayName = "Remote",
            path = "am-lyrics/a.ttml",
            enabled = true,
            sizeBytes = bytes.size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256(bytes),
        )
        val result = coordinator(
            loadAmLyricsIndex = { AmLyricsIndex(listOf(remote)) },
            fetchAmLyrics = { bodyFetches.incrementAndGet(); ttml("new") },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(local)),
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(1, result.unchanged)
        assertEquals(0, result.updated)
        assertEquals(0, bodyFetches.get())
    }

    @Test
    fun `lunabeat catalog hash fast path and path deduplication`() {
        val body = ttml("new")
        val remoteSong = LunabeatSong(
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
            appleMusicIds = listOf(1L, 2L),
            path = "lyrics/shared.ttml",
            sha256 = CustomLyricsFilePolicy.sha256(body.toByteArray()),
        )
        val same = entry(1L, CustomLyricsSources.LUNABEAT, body, "lyrics_same")
        val changed = entry(2L, CustomLyricsSources.LUNABEAT, ttml("old"), "lyrics_changed")
        val fetches = AtomicInteger()
        val result = coordinator(
            loadLunabeatCatalog = {
                LunabeatCatalog(
                    manifest = LunabeatManifest(2, "r1", "songs.json"),
                    songs = listOf(remoteSong),
                )
            },
            fetchLunabeat = { fetches.incrementAndGet(); body },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(same, changed)),
            fileIdFactory = object : () -> String {
                var index = 0
                override fun invoke() = "lyrics_new_${index++}"
            },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(1, result.unchanged)
        assertEquals(1, result.updated)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `manual and auto cache are skipped and source failures are fail open`() {
        val manual = entry(1L, CustomLyricsSources.MANUAL, ttml("manual"), "lyrics_manual")
        val auto = entry(2L, CustomLyricsSources.AUTO_CACHE, ttml("auto"), "lyrics_auto")
        val remote = entry(3L, CustomLyricsSources.AMLL, ttml("old"), "lyrics_amll")
        val result = coordinator(fetchAmll = { null }).update(
            oldManifest = CustomLyricsManifest(listOf(manual, auto, remote)),
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> error("must not write") },
            publishManifest = { error("must not publish") },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(2, result.skipped)
        assertEquals(1, result.failed)
        assertTrue(result.issues.any { it.appleMusicId == 3L })
    }

    @Test
    fun `metadata imports stay skipped when all official sources miss`() {
        val qq = entry(11L, CustomLyricsSources.QQ_MUSIC, ttml("qq"), "lyrics_qq")
        val netease = entry(12L, CustomLyricsSources.NETEASE_CLOUD_MUSIC, ttml("ne"), "lyrics_ne")
        val result = coordinator(
            loadLunabeatCatalog = {
                LunabeatCatalog(
                    manifest = LunabeatManifest(1, "r1", "songs.json"),
                    songs = emptyList(),
                )
            },
            loadAmLyricsIndex = { AmLyricsIndex(emptyList()) },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(qq, netease)),
            fileIdFactory = { error("must not allocate files") },
            writeRemoteFile = { _, _ -> error("must not write") },
            publishManifest = { error("must not publish") },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(2, result.skipped)
        assertEquals(0, result.updated)
        assertEquals(0, result.failed)
        assertEquals(CustomLyricsManifest(listOf(qq, netease)), result.manifest)
    }

    @Test
    fun `metadata entry promotes to AMLL before lower priority sources`() {
        val local = entry(42L, CustomLyricsSources.QQ_MUSIC, ttml("local"), "lyrics_old")
        val amllFetches = AtomicInteger()
        val lunabeatFetches = AtomicInteger()
        val amLyricsFetches = AtomicInteger()
        val result = coordinator(
            fetchAmll = {
                amllFetches.incrementAndGet()
                ttml("amll")
            },
            loadLunabeatCatalog = {
                LunabeatCatalog(
                    manifest = LunabeatManifest(1, "r1", "songs.json"),
                    songs = listOf(
                        LunabeatSong(
                            title = "Song",
                            artists = listOf("Artist"),
                            album = "Album",
                            appleMusicIds = listOf(42L),
                            path = "lyrics/luna.ttml",
                            sha256 = "0".repeat(64),
                        ),
                    ),
                )
            },
            fetchLunabeat = {
                lunabeatFetches.incrementAndGet()
                ttml("lunabeat")
            },
            loadAmLyricsIndex = {
                AmLyricsIndex(
                    listOf(
                        AmLyricsIndexEntry(
                            appleMusicId = 42L,
                            alternateIds = emptyList(),
                            displayName = "AM",
                            path = "am-lyrics/a.ttml",
                            enabled = true,
                            sizeBytes = 1L,
                            sha256 = "0".repeat(64),
                        ),
                    ),
                )
            },
            fetchAmLyrics = {
                amLyricsFetches.incrementAndGet()
                ttml("am-lyrics")
            },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(local)),
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(1, result.updated)
        assertEquals(CustomLyricsSources.AMLL, result.manifest.entries.single().source)
        assertEquals(1, amllFetches.get())
        assertEquals(0, lunabeatFetches.get())
        assertEquals(0, amLyricsFetches.get())
    }

    @Test
    fun `metadata entry falls back to Lunabeat before AM-Lyrics and promotes even with identical bytes`() {
        val localBody = ttml("same")
        val local = entry(43L, CustomLyricsSources.NETEASE_CLOUD_MUSIC, localBody, "lyrics_old")
        val amLyricsFetches = AtomicInteger()
        val result = coordinator(
            fetchAmll = { null },
            loadLunabeatCatalog = {
                LunabeatCatalog(
                    manifest = LunabeatManifest(1, "r1", "songs.json"),
                    songs = listOf(
                        LunabeatSong(
                            title = "Song",
                            artists = listOf("Artist"),
                            album = "Album",
                            appleMusicIds = listOf(43L),
                            path = "lyrics/luna.ttml",
                            sha256 = "0".repeat(64),
                        ),
                    ),
                )
            },
            fetchLunabeat = { localBody },
            loadAmLyricsIndex = {
                AmLyricsIndex(
                    listOf(
                        AmLyricsIndexEntry(
                            appleMusicId = 43L,
                            alternateIds = emptyList(),
                            displayName = "AM",
                            path = "am-lyrics/a.ttml",
                            enabled = true,
                            sizeBytes = 1L,
                            sha256 = "0".repeat(64),
                        ),
                    ),
                )
            },
            fetchAmLyrics = {
                amLyricsFetches.incrementAndGet()
                ttml("am-lyrics")
            },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(local)),
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(1, result.updated)
        assertEquals(CustomLyricsSources.LUNABEAT, result.manifest.entries.single().source)
        assertEquals(0, amLyricsFetches.get())
    }

    @Test
    fun `metadata entry promotes to AM-Lyrics when higher priority sources miss`() {
        val local = entry(44L, CustomLyricsSources.QQ_MUSIC, ttml("local"), "lyrics_old")
        val result = coordinator(
            fetchAmll = { null },
            loadLunabeatCatalog = {
                LunabeatCatalog(
                    manifest = LunabeatManifest(1, "r1", "songs.json"),
                    songs = emptyList(),
                )
            },
            loadAmLyricsIndex = {
                AmLyricsIndex(
                    listOf(
                        AmLyricsIndexEntry(
                            appleMusicId = 44L,
                            alternateIds = emptyList(),
                            displayName = "AM",
                            path = "am-lyrics/a.ttml",
                            enabled = true,
                            sizeBytes = 1L,
                            sha256 = "0".repeat(64),
                        ),
                    ),
                )
            },
            fetchAmLyrics = { ttml("am-lyrics") },
        ).update(
            oldManifest = CustomLyricsManifest(listOf(local)),
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(1, result.updated)
        assertEquals(CustomLyricsSources.AM_LYRICS, result.manifest.entries.single().source)
    }

    private fun coordinator(
        fetchAmll: (Long) -> String? = { null },
        loadAmLyricsIndex: () -> AmLyricsIndex? = { null },
        fetchAmLyrics: (AmLyricsIndexEntry) -> String? = { null },
        loadLunabeatCatalog: () -> LunabeatCatalog? = { null },
        fetchLunabeat: (LunabeatSong) -> String? = { null },
    ) = CustomLyricsUpdateCoordinator(
        CustomLyricsUpdateSources(
            fetchAmll = fetchAmll,
            loadAmLyricsIndex = loadAmLyricsIndex,
            fetchAmLyricsTtml = fetchAmLyrics,
            loadLunabeatCatalog = loadLunabeatCatalog,
            fetchLunabeatTtml = fetchLunabeat,
        ),
    )

    private fun entry(id: Long, source: String, ttml: String, fileId: String) =
        CustomLyricsEntry(
            appleMusicId = id,
            displayName = "Local $id",
            fileId = fileId,
            sizeBytes = ttml.toByteArray().size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256(ttml.toByteArray()),
            source = source,
            enabled = false,
        )

    private fun ttml(text: String): String =
        "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p>$text</p></div></body></tt>"
}
