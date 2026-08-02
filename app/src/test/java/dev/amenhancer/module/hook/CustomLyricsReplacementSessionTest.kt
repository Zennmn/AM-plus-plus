package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            manifestProvider = { manifestReads += 1; manifest(entry(42L)) },
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
    fun `a failed initial manifest read retries from a later i2 without blocking it`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            manifestProvider = {
                manifestReads += 1
                if (manifestReads == 1) error("remote preferences unavailable")
                manifest(entry(42L))
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
            manifestProvider = { manifest },
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
    fun `availability lookup returns only a ready pointer without queueing preload`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        val pointer = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            manifestProvider = { manifestReads += 1; manifest(entry(42L)) },
            readTtml = { TTML },
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
        assertSame(pointer, session.readyReplacementFor(42L))
        assertNull(session.readyReplacementFor(41L))
        queued.runAll()
        assertEquals(1, manifestReads)
    }

    @Test
    fun `availability queues background reprepare only for a mapped dead pointer`() {
        val queued = QueuedExecutor()
        var manifestReads = 0
        var parses = 0
        val first = Pointer(42L)
        val second = Pointer(42L)
        val session = CustomLyricsReplacementSession(
            manifestProvider = { manifestReads += 1; manifest(entry(42L)) },
            readTtml = { TTML },
            parseTtml = { if (parses++ == 0) first else second },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )

        session.start()
        queued.runAll()
        first.live = false

        assertNull(session.replacementOrPrepareFor(42L))
        assertEquals(1, manifestReads)
        queued.runAll()
        assertSame(second, session.replacementOrPrepareFor(42L))

        assertNull(session.replacementOrPrepareFor(41L))
        queued.runAll()
        assertEquals(2, manifestReads)
    }

    private fun session(
        manifest: CustomLyricsManifest,
        read: (CustomLyricsEntry) -> String?,
        parse: (String) -> Any?,
        bind: (Pointer, Long) -> Boolean = { pointer, id -> pointer.adamId = id; true },
    ): CustomLyricsReplacementSession = CustomLyricsReplacementSession(
        manifestProvider = { manifest },
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

    private fun entry(id: Long, enabled: Boolean = true) = CustomLyricsEntry(
        appleMusicId = id,
        displayName = "Song",
        fileId = "lyrics_$id",
        sizeBytes = TTML.toByteArray().size.toLong(),
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
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
    }
}
