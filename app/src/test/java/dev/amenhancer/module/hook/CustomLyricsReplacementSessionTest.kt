package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsReplacementSessionTest {
    @Test
    fun `only an enabled exact apple music id is parsed and bound`() {
        var reads = 0
        val pointer = Pointer(0L)
        val session = session(
            manifest = manifest(entry(42L)),
            read = { reads += 1; TTML },
            parse = { pointer },
        )

        assertNull(session.replacementFor(41L))
        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, reads)
        assertEquals(42L, pointer.adamId)
    }

    @Test
    fun `a cached pointer is reused only for the same published file hash`() {
        var parses = 0
        val pointer = Pointer(42L)
        val session = session(
            manifest = manifest(entry(42L)),
            read = { TTML },
            parse = { parses += 1; pointer },
        )

        assertSame(pointer, session.replacementFor(42L))
        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, parses)
    }

    @Test
    fun `disabled mappings and binding failures fail open`() {
        val disabled = session(manifest(entry(42L, enabled = false)), { TTML }, { Pointer(0L) })
        assertNull(disabled.replacementFor(42L))

        val failedBinding = session(
            manifest(entry(42L)),
            { TTML },
            { Pointer(0L) },
            bind = { _, _ -> false },
        )
        assertNull(failedBinding.replacementFor(42L))
    }

    @Test
    fun `i2 lookup never reads a manifest file or parses on the hook thread`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var fileReads = 0
        var parses = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { fileReads += 1; TTML },
            parseTtml = { parses += 1; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()

        assertNull(session.replacementFor(42L))
        assertEquals(0, manifestReads)
        assertEquals(0, fileReads)
        assertEquals(0, parses)

        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, manifestReads)
        assertEquals(1, fileReads)
        assertEquals(1, parses)
    }

    @Test
    fun `start loads only the lightweight index and never lyric bodies`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var fileReads = 0
        var parses = 0
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manyEntries(1000).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { fileReads += 1; TTML },
            parseTtml = { parses += 1; Pointer(0L) },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()

        assertEquals(1, manifestReads)
        assertEquals(0, fileReads)
        assertEquals(0, parses)
    }

    @Test
    fun `first request among a thousand parses only the requested entry`() {
        val queued = QueuedExecutor()
        var fileReads = 0
        var parses = 0
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manyEntries(1000).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { fileReads += 1; TTML },
            parseTtml = { parses += 1; Pointer(0L) },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertEquals(0, fileReads)

        assertNull(session.replacementFor(777L))
        queued.runAll()
        assertNotNull(session.replacementFor(777L))
        assertEquals(1, fileReads)
        assertEquals(1, parses)

        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertNotNull(session.replacementFor(42L))
        assertEquals(2, fileReads)
        assertEquals(2, parses)
    }

    @Test
    fun `a failed initial index read retries from a later i2 without blocking it`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                if (manifestReads == 1) error("remote preferences unavailable")
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(2, manifestReads)
    }

    @Test
    fun `an unknown id reloads a mapping published after target startup`() {
        val queued = QueuedExecutor()
        var manifest = CustomLyricsManifest()
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        manifest = manifest(entry(42L))

        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertSame(pointer, session.replacementFor(42L))
    }

    @Test
    fun `ensure requested prewarms an unknown mapping before availability check`() {
        val queued = QueuedExecutor()
        var manifest = CustomLyricsManifest()
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertFalse(session.isTracking(42L))

        manifest = manifest(entry(42L))
        session.ensureRequested(42L)
        assertTrue(session.isTracking(42L))

        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertTrue(session.isTracking(42L))
    }

    @Test
    fun `concurrent duplicate known-id misses prepare a single entry`() {
        val queued = QueuedExecutor()
        var parses = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { parses += 1; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()

        assertNull(session.replacementFor(42L))
        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertEquals(1, parses)
        assertSame(pointer, session.replacementFor(42L))
        queued.runAll()
        assertEquals(1, parses)
    }

    @Test
    fun `concurrent unknown-id misses refresh the index only once`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var parses = 0
        var manifest = CustomLyricsManifest()
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { parses += 1; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        assertNull(session.replacementFor(42L))
        assertNull(session.replacementFor(42L))
        assertNull(session.replacementFor(42L))
        manifest = manifest(entry(42L))
        queued.runAll()

        assertEquals(1, manifestReads)
        assertEquals(1, parses)
        assertSame(pointer, session.replacementFor(42L))
    }

    @Test
    fun `availability lookup returns only a ready pointer without queueing work`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var fileReads = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { fileReads += 1; TTML },
            parseTtml = { pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        assertNull(session.readyReplacementFor(42L))
        queued.runAll()
        assertEquals(0, manifestReads)

        session.start()
        queued.runAll()
        assertEquals(1, manifestReads)
        assertEquals(0, fileReads)
        assertNull(session.readyReplacementFor(42L))

        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertSame(pointer, session.readyReplacementFor(42L))
        assertNull(session.readyReplacementFor(41L))
        queued.runAll()
        assertEquals(1, manifestReads)
        assertEquals(1, fileReads)
    }

    @Test
    fun `availability queues a single-file reprepare for a mapped dead pointer`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var fileReads = 0
        var parses = 0
        val first = Pointer(42L)
        val second = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { fileReads += 1; TTML },
            parseTtml = { parses += 1; if (parses == 1) first else second },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertNull(session.replacementOrPrepareFor(42L))
        queued.runAll()
        assertSame(first, session.replacementOrPrepareFor(42L))
        first.live = false

        assertNull(session.replacementOrPrepareFor(42L))
        assertEquals(1, manifestReads)
        queued.runAll()
        assertSame(second, session.replacementOrPrepareFor(42L))
        assertEquals(2, fileReads)
        assertEquals(2, parses)

        assertNull(session.replacementOrPrepareFor(41L))
        queued.runAll()
        assertEquals(1, manifestReads)
    }

    @Test
    fun `lru eviction re-prepares a single evicted entry on the next request`() {
        val queued = QueuedExecutor()
        var parses = 0
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manyEntries(1000).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { parses += 1; Pointer(0L) },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()

        (1L..33L).forEach { id ->
            assertNull(session.replacementFor(id))
            queued.runAll()
        }
        assertEquals(33, parses)

        assertNull(session.replacementFor(1L))
        queued.runAll()
        assertNotNull(session.replacementFor(1L))
        assertEquals(34, parses)
    }

    @Test
    fun `a changed manifest hash invalidates the cached pointer after refresh`() {
        val queued = QueuedExecutor()
        var fileReads = 0
        var parses = 0
        val readFiles = mutableListOf<String>()
        var manifest = CustomLyricsManifest(
            listOf(entry(42L, fileId = "lyrics_v1", sha256 = SHA256_A)),
        )
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { entry ->
                fileReads += 1
                readFiles += entry.fileId
                TTML
            },
            parseTtml = { parses += 1; Pointer(0L) },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertNull(session.replacementFor(42L))
        queued.runAll()
        val first = session.replacementFor(42L)
        assertNotNull(first)
        assertEquals(listOf("lyrics_v1"), readFiles)

        manifest = CustomLyricsManifest(
            listOf(entry(42L, fileId = "lyrics_v2", sha256 = SHA256_B)),
        )
        assertNull(session.replacementFor(999L))
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertNull(session.replacementFor(42L))
        queued.runAll()
        val second = session.replacementFor(42L)
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(listOf("lyrics_v1", "lyrics_v2"), readFiles)
        assertEquals(2, parses)
    }

    @Test
    fun `publish callback fires only for a successfully prepared replacement`() {
        val queued = QueuedExecutor()
        val published = mutableListOf<Long>()
        var parses = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { parses += 1; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onReplacementPublished = { published += it },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertTrue(published.isEmpty())

        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertEquals(listOf(42L), published)

        assertSame(pointer, session.replacementFor(42L))
        queued.runAll()
        assertEquals(listOf(42L), published)
        assertEquals(1, parses)
    }

    @Test
    fun `publish callback is silent for failed prepares and unknown ids`() {
        val queued = QueuedExecutor()
        val published = mutableListOf<Long>()
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifest(entry(42L)).entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { null },
            parseTtml = { Pointer(0L) },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onReplacementPublished = { published += it },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertTrue(published.isEmpty())

        assertNull(session.replacementFor(41L))
        queued.runAll()
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a rejected index refresh drops the unknown pending id so it can retry later`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var parses = 0
        var reject = true
        val pointer = Pointer(42L)
        var manifest = CustomLyricsManifest()
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                manifestReads += 1
                manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { TTML },
            parseTtml = { parses += 1; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = Executor { command ->
                if (reject) {
                    throw RejectedExecutionException("index refresh rejected")
                } else {
                    queued.execute(command)
                }
            },
            logger = {},
        )

        session.start()
        assertEquals(0, manifestReads)

        assertNull(session.replacementFor(42L))
        assertEquals(0, manifestReads)

        reject = false
        manifest = manifest(entry(42L))
        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, manifestReads)
        assertEquals(1, parses)
    }

    private fun session(
        manifest: CustomLyricsManifest,
        read: (CustomLyricsEntry) -> String?,
        parse: (String) -> Any?,
        bind: (Pointer, Long) -> Boolean = { pointer, id -> pointer.adamId = id; true },
    ): CustomLyricsReplacementSession = CustomLyricsReplacementSession(
        index = CustomLyricsIndexProvider {
            manifest.entries.associateBy(CustomLyricsEntry::appleMusicId)
        },
        readTtml = read,
        parseTtml = parse,
        isAlive = { it is Pointer },
        verifyPtr = { it is Pointer },
        readAdamId = { (it as Pointer).adamId },
        bindAdamId = { pointer, id -> bind(pointer as Pointer, id) },
        executor = Executor { command -> command.run() },
        logger = {},
    ).also(CustomLyricsReplacementSession::start)

    private fun manifest(entry: CustomLyricsEntry) = CustomLyricsManifest(listOf(entry))

    private fun manyEntries(count: Int): CustomLyricsManifest = CustomLyricsManifest(
        (1L..count.toLong()).map(::entry),
    )

    private fun entry(
        id: Long,
        enabled: Boolean = true,
        fileId: String = "lyrics_$id",
        sha256: String = SHA256_A,
    ) = CustomLyricsEntry(
        appleMusicId = id,
        displayName = "Song",
        fileId = fileId,
        sizeBytes = TTML.toByteArray().size.toLong(),
        sha256 = sha256,
        source = CustomLyricsSources.MANUAL,
        enabled = enabled,
    )

    private data class Pointer(var adamId: Long, var live: Boolean = true)

    private class QueuedExecutor : Executor {
        private val commands = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            commands += command
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeAt(0).run()
        }
    }

    private companion object {
        const val TTML = "<tt><body><p><span>word</span></p></body></tt>"
        const val SHA256_A = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"
        const val SHA256_B = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
