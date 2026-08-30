package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.ArrayDeque
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLyricsReplacementSessionTest {
    @Test
    fun `cold lookup keeps original path until a validated candidate publishes`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache()
        val pointer = Pointer()
        var fetches = 0
        var published = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { pointer },
            onPublished = { published += 1 },
        )

        assertNull(session.replacementFor(42L))
        assertEquals(0, fetches)

        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, fetches)
        assertEquals(1, published)
        assertEquals(42L, pointer.adamId)
        assertEquals(WORD_TTML, cache.values[42L])
    }

    @Test
    fun `a persisted Word cache is prepared before any network source`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(mapOf(42L to WORD_TTML))
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { fetches += 1; AutoLyricsCandidate("network", WORD_TTML) },
            parse = { pointer },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fetches)
    }

    @Test
    fun `validated automatic result is published to configured storage and cache is retired`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(mapOf(42L to WORD_TTML))
        var published = 0
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { null },
            cache = cache,
            parseTtml = { Pointer() },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            publisher = { id, candidate ->
                assertEquals(42L, id)
                assertEquals(CustomLyricsSources.AUTO_CACHE, candidate.source)
                published += 1
                AutoLyricsPublishResult.PUBLISHED
            },
            executor = queued,
            logger = {},
        )

        session.replacementFor(42L)
        queued.runAll()

        assertEquals(1, published)
        assertEquals(null, cache.read(42L))
    }

    @Test
    fun `stale generation cannot publish after the current song changes`() {
        val queued = QueuedExecutor()
        var fetches = 0
        var published = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { Pointer() },
            onPublished = { published += 1 },
        )

        session.onSongChanged(42L)
        assertNull(session.replacementFor(42L))
        session.onSongChanged(43L)
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertEquals(0, fetches)
        assertEquals(0, published)
    }

    @Test
    fun `repeated metadata for the same song keeps the pending lookup and pointer`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        session.onSongChanged(42L)
        queued.runAll()

        assertEquals(1, fetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `already configured publisher never publishes an automatic ready late replacement`() {
        val queued = QueuedExecutor()
        var published = 0
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { AutoLyricsCandidate("amll", WORD_TTML) },
            cache = MemoryCache(),
            parseTtml = { Pointer() },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onReplacementPublished = { published += 1 },
            publisher = { _, _ -> AutoLyricsPublishResult.ALREADY_CONFIGURED },
            executor = queued,
            logger = {},
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()

        assertEquals(0, published)
        assertEquals(null, session.readyReplacementFor(42L))
    }

    @Test
    fun `a manual replacement becoming ready cancels the queued automatic lookup`() {
        val queued = QueuedExecutor()
        var allowed = true
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { Pointer() },
            isAllowed = { allowed },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        allowed = false
        queued.runAll()

        assertEquals(0, fetches)
        assertTrue(session.isTracking(42L).not())
    }

    @Test
    fun `already applied takeover survives unknown refresh but yields to better native Word lyrics`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        val session = session(
            queued = queued,
            fetch = { AutoLyricsCandidate("amll", WORD_TTML) },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.replacementFor(42L)
        queued.runAll()
        assertNull(session.takeoverReplacementFor(42L, Any(), metadata = null))

        session.markTakeoverApplied(42L)
        assertSame(pointer, session.takeoverReplacementFor(42L, Any(), metadata = null))
        assertNull(
            session.takeoverReplacementFor(
                42L,
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "zh", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `non Word candidates fail open and never reach the native parser`() {
        val queued = QueuedExecutor()
        var parses = 0
        val session = session(
            queued = queued,
            fetch = { AutoLyricsCandidate("line-source", LINE_TTML) },
            parse = { parses += 1; Pointer() },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertEquals(0, parses)
        assertTrue(session.isTracking(42L).not())
    }

    @Test
    fun `metadata sources wait for stable identity and do not repeat Apple ID sources`() {
        val queued = QueuedExecutor()
        var appleIdFetches = 0
        var metadataFetches = 0
        val pointer = Pointer()
        val session = session(
            queued = queued,
            fetch = { appleIdFetches += 1; null },
            fetchMetadata = {
                metadataFetches += 1
                assertEquals("修正后歌名", it.title)
                AutoLyricsCandidate("qq-music", WORD_TTML)
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()
        assertEquals(1, appleIdFetches)
        assertEquals(0, metadataFetches)

        session.onStableMetadata(stableMetadata(42L, "修正后歌名"))
        queued.runAll()

        assertEquals(1, appleIdFetches)
        assertEquals(1, metadataFetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `provisional raw song change does not cancel the serial metadata fallback`() {
        val queued = QueuedExecutor()
        var appleIdFetches = 0
        var metadataFetches = 0
        val pointer = Pointer()
        lateinit var session: AutoLyricsReplacementSession
        session = session(
            queued = queued,
            fetch = {
                appleIdFetches += 1
                session.onSongChanged(43L)
                null
            },
            fetchMetadata = {
                metadataFetches += 1
                AutoLyricsCandidate("qq-music", WORD_TTML)
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.onStableMetadata(stableMetadata(42L, "Confirmed"))
        session.ensureRequested(42L)
        queued.runAll()

        assertEquals(1, appleIdFetches)
        assertEquals(1, metadataFetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `stable metadata confirmation alone does not start a new primary chain`() {
        val queued = QueuedExecutor()
        val fetchedIds = mutableListOf<Long>()
        val session = session(
            queued = queued,
            fetch = { appleMusicId ->
                fetchedIds += appleMusicId
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            fetchMetadata = { AutoLyricsCandidate("qq-music", WORD_TTML) },
            parse = { Pointer() },
        )

        session.onSongChanged(42L)
        session.onStableMetadata(stableMetadata(42L, "Old"))
        session.onSongChanged(43L)
        session.onStableMetadata(stableMetadata(43L, "New"))
        queued.runAll()

        assertEquals(emptyList<Long>(), fetchedIds)
        assertNull(session.readyReplacementFor(42L))
        assertNull(session.readyReplacementFor(43L))
    }

    @Test
    fun `stable metadata from the active song can resume the serial fallback`() {
        val queued = QueuedExecutor()
        var metadataFetches = 0
        val session = session(
            queued = queued,
            fetch = { null },
            fetchMetadata = { metadataFetches += 1; AutoLyricsCandidate("qq-music", WORD_TTML) },
            parse = { Pointer() },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()
        session.onStableMetadata(stableMetadata(42L, "Old"))
        queued.runAll()

        assertEquals(1, metadataFetches)
        assertTrue(session.readyReplacementFor(42L) != null)
    }

    @Test
    fun `failed Apple ID lookup retries after its cooldown when metadata fallback is disabled`() {
        val queued = QueuedExecutor()
        var now = 0L
        var appleIdFetches = 0
        val pointer = Pointer()
        val session = session(
            queued = queued,
            fetch = {
                appleIdFetches += 1
                if (appleIdFetches == 2) AutoLyricsCandidate("amll", WORD_TTML) else null
            },
            parse = { pointer },
            nowMs = { now },
            retryCooldownMs = 30L,
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()
        assertEquals(1, appleIdFetches)

        session.ensureRequested(42L)
        queued.runAll()
        assertEquals(1, appleIdFetches)

        now = 31L
        session.ensureRequested(42L)
        queued.runAll()

        assertEquals(2, appleIdFetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `duplicate stable metadata does not clear a failed lyric lookup cooldown`() {
        val queued = QueuedExecutor()
        var metadataFetches = 0
        val session = session(
            queued = queued,
            fetch = { null },
            fetchMetadata = {
                metadataFetches += 1
                null
            },
            parse = { null },
        )
        val coordinator = StablePlaybackMetadataCoordinator(true, schedule = { _, _ -> })
        coordinator.addListener(session::onStableMetadata)

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        coordinator.onCurrentSong(CurrentSongDetails(42L, "Song", "Artist"))
        coordinator.onResolutionFinished(42L, "Song", "Artist", "Album", 180_000L)
        queued.runAll()
        assertEquals(1, metadataFetches)

        coordinator.onResolutionFinished(42L, "Song", "Artist", "Album", 180_000L)
        queued.runAll()

        assertEquals(1, metadataFetches)
    }

    @Test
    fun `file cache persists only Word TTML and reloads it by Adam ID`() {
        val directory = Files.createTempDirectory("ampp-auto-lyrics-test").toFile()
        try {
            val cache = FileAutoLyricsCache(directory, maxEntries = 2)
            assertTrue(cache.write(42L, WORD_TTML))
            assertEquals(WORD_TTML, FileAutoLyricsCache(directory).read(42L))
            assertTrue(!cache.write(43L, LINE_TTML))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun session(
        queued: QueuedExecutor,
        cache: AutoLyricsCache = MemoryCache(),
        fetch: (Long) -> AutoLyricsCandidate?,
        fetchMetadata: ((StablePlaybackMetadata) -> AutoLyricsCandidate?)? = null,
        parse: (String) -> Any?,
        onPublished: (Long) -> Unit = {},
        isAllowed: (Long) -> Boolean = { true },
        nowMs: () -> Long = { System.currentTimeMillis() },
        retryCooldownMs: Long = 30_000L,
    ): AutoLyricsReplacementSession {
        return AutoLyricsReplacementSession(
            fetchCandidate = fetch,
            fetchMetadataCandidate = fetchMetadata,
            cache = cache,
            parseTtml = parse,
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id ->
                (value as Pointer).adamId = id
                true
            },
            onReplacementPublished = onPublished,
            isAllowed = isAllowed,
            executor = queued,
            logger = {},
            nowMs = nowMs,
            retryCooldownMs = retryCooldownMs,
        )
    }

    private class Pointer(
        var adamId: Long = 0L,
        var live: Boolean = true,
    )

    private fun stableMetadata(id: Long, title: String) = StablePlaybackMetadata(
        appleMusicId = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        outcome = StableMetadataOutcome.CORRECTED,
        generation = 1L,
    )

    private class MemoryCache(initial: Map<Long, String> = emptyMap()) : AutoLyricsCache {
        val values = initial.toMutableMap()
        override fun read(appleMusicId: Long): String? = values[appleMusicId]
        override fun write(appleMusicId: Long, ttml: String): Boolean {
            values[appleMusicId] = ttml
            return true
        }
        override fun delete(appleMusicId: Long): Boolean = values.remove(appleMusicId) != null
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(command: Runnable) {
            tasks.addLast { command.run() }
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
