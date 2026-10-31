package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmLyricsReplacementSessionTest {
    @Test
    fun `reasoning effort change rejects in-flight output before parse persist or publish`() {
        val queued = ManualExecutor()
        var configuration: LlmLyricsConfig? = config(LlmLyricsThinkingEffort.HIGH)
        var translationCancelled = false
        val parsed = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val published = mutableListOf<Long>()
        val session = session(
            queued = queued,
            configuration = { configuration },
            translate = { _, isCancelled ->
                configuration = config(LlmLyricsThinkingEffort.MAX)
                translationCancelled = isCancelled()
                STALE_TTML
            },
            parse = { ttml -> parsed += ttml; Pointer() },
            onPrepared = { _, ttml -> persisted += ttml },
            onPublished = { published += it },
        )

        session.onSongChanged(42L)
        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()

        assertTrue(translationCancelled)
        assertTrue(parsed.isEmpty())
        assertTrue(persisted.isEmpty())
        assertTrue(published.isEmpty())
        session.close()
    }

    @Test
    fun `new reasoning config starts a task and only its completed result is published`() {
        val queued = ManualExecutor()
        var configuration: LlmLyricsConfig? = config(LlmLyricsThinkingEffort.HIGH)
        val translatedConfigs = mutableListOf<LlmLyricsConfig>()
        val parsed = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val published = mutableListOf<Long>()
        val currentPointer = Pointer()
        lateinit var session: LlmLyricsReplacementSession
        session = session(
            queued = queued,
            configuration = { configuration },
            translate = { request, _ ->
                translatedConfigs += request.config
                if (request.config.thinkingEffort == LlmLyricsThinkingEffort.HIGH) {
                    configuration = config(LlmLyricsThinkingEffort.MAX)
                    assertNull(session.replacementFor(42L, ORIGINAL_TTML))
                    queued.runAll()
                    STALE_TTML
                } else {
                    CURRENT_TTML
                }
            },
            parse = { ttml -> parsed += ttml; currentPointer },
            onPrepared = { _, ttml -> persisted += ttml },
            onPublished = { published += it },
        )

        session.onSongChanged(42L)
        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()

        assertSame(currentPointer, session.replacementFor(42L, ORIGINAL_TTML))
        assertEquals(
            listOf(
                config(LlmLyricsThinkingEffort.HIGH),
                config(LlmLyricsThinkingEffort.MAX),
            ),
            translatedConfigs,
        )
        assertEquals(listOf(CURRENT_TTML), parsed)
        assertEquals(listOf(CURRENT_TTML), persisted)
        assertEquals(listOf(42L), published)
        assertTrue(queued.isEmpty())
        session.close()
    }

    @Test
    fun `a song change cancels an in-flight translation before native use`() {
        val queued = ManualExecutor()
        val configuration = config(LlmLyricsThinkingEffort.HIGH)
        var translationCancelled = false
        var translations = 0
        val parsed = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val published = mutableListOf<Long>()
        lateinit var session: LlmLyricsReplacementSession
        session = session(
            queued = queued,
            configuration = { configuration },
            translate = { _, isCancelled ->
                translations += 1
                session.onSongChanged(43L)
                translationCancelled = isCancelled()
                STALE_TTML
            },
            parse = { ttml -> parsed += ttml; Pointer() },
            onPrepared = { _, ttml -> persisted += ttml },
            onPublished = { published += it },
        )

        session.onSongChanged(42L)
        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()

        assertEquals(1, translations)
        assertTrue(translationCancelled)
        assertTrue(parsed.isEmpty())
        assertTrue(persisted.isEmpty())
        assertTrue(published.isEmpty())
        session.close()
    }

    @Test
    fun `disabling configuration cancels an in-flight translation before native use`() {
        val queued = ManualExecutor()
        var configuration: LlmLyricsConfig? = config(LlmLyricsThinkingEffort.HIGH)
        var translationCancelled = false
        val parsed = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val published = mutableListOf<Long>()
        val session = session(
            queued = queued,
            configuration = { configuration },
            translate = { _, isCancelled ->
                configuration = null
                translationCancelled = isCancelled()
                STALE_TTML
            },
            parse = { ttml -> parsed += ttml; Pointer() },
            onPrepared = { _, ttml -> persisted += ttml },
            onPublished = { published += it },
        )

        session.onSongChanged(42L)
        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()

        assertTrue(translationCancelled)
        assertTrue(parsed.isEmpty())
        assertTrue(persisted.isEmpty())
        assertTrue(published.isEmpty())
        session.close()
    }

    @Test
    fun `turning off thinking drops old output but still allows a nonthinking translation`() {
        val queued = ManualExecutor()
        var configuration = config(LlmLyricsThinkingEffort.MAX)
        var translationCancelled = false
        val parsed = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val published = mutableListOf<Long>()
        val pointer = Pointer()
        val session = session(
            queued = queued,
            configuration = { configuration },
            translate = { request, isCancelled ->
                if (request.config.thinkingEnabled) {
                    configuration = configuration.copy(thinkingEnabled = false)
                    translationCancelled = isCancelled()
                    STALE_TTML
                } else CURRENT_TTML
            },
            parse = { ttml -> parsed += ttml; pointer },
            onPrepared = { _, ttml -> persisted += ttml },
            onPublished = { published += it },
        )
        session.onSongChanged(42L)
        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()
        assertTrue(translationCancelled)
        assertTrue(parsed.isEmpty())
        assertTrue(persisted.isEmpty())
        assertTrue(published.isEmpty())

        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        queued.runAll()
        assertEquals(LlmLyricsThinkingEffort.MAX, configuration.thinkingEffort)
        assertSame(pointer, session.replacementFor(42L, ORIGINAL_TTML))
        assertEquals(listOf(CURRENT_TTML), parsed)
        assertEquals(listOf(CURRENT_TTML), persisted)
        assertEquals(listOf(42L), published)
        session.close()
    }

    @Test
    fun `translation starts only after the active native document is selected`() {
        val queued = ManualExecutor()
        val configuration = config(LlmLyricsThinkingEffort.HIGH)
        val pointer = Pointer()
        var translations = 0
        val published = mutableListOf<Long>()
        val session = session(
            queued = queued,
            configuration = { configuration },
            translate = { _, _ -> translations += 1; CURRENT_TTML },
            parse = { pointer },
            onPublished = { published += it },
        )

        session.onSongChanged(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        assertTrue(queued.isEmpty())
        assertEquals(0, translations)

        session.onNativeSelected(43L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        assertTrue(queued.isEmpty())
        assertEquals(0, translations)

        session.onNativeSelected(42L)
        assertNull(session.replacementFor(42L, ORIGINAL_TTML))
        assertEquals(1, queued.size())
        queued.runAll()

        assertSame(pointer, session.replacementFor(42L, ORIGINAL_TTML))
        assertEquals(1, translations)
        assertEquals(listOf(42L), published)
        session.close()
    }

    private fun session(
        queued: ManualExecutor,
        configuration: () -> LlmLyricsConfig?,
        translate: (LlmLyricsRequest, () -> Boolean) -> String?,
        parse: (String) -> Any?,
        onPrepared: (LlmLyricsRequest, String) -> Unit = { _, _ -> },
        onPublished: (Long) -> Unit = {},
    ): LlmLyricsReplacementSession = LlmLyricsReplacementSession(
        runtime = LlmLyricsRuntime(
            configuration = configuration,
            translate = translate,
            executor = queued,
            onPrepared = onPrepared,
        ),
        parseTtml = parse,
        isAlive = { it is Pointer && it.live },
        verifyPtr = { it is Pointer && it.live },
        readAdamId = { (it as Pointer).adamId },
        bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
        isAllowed = { true },
        onReplacementPublished = onPublished,
    )

    private class Pointer(
        var adamId: Long = 0L,
        var live: Boolean = true,
    )

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun execute(command: Runnable) {
            tasks.addLast { command.run() }
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }

        fun size(): Int = tasks.size

        fun isEmpty(): Boolean = tasks.isEmpty()
    }

    private companion object {
        const val ORIGINAL_TTML = "<tt><body><p>hello</p></body></tt>"
        const val STALE_TTML = "<tt><body><p>old translation</p></body></tt>"
        const val CURRENT_TTML = "<tt><body><p>current translation</p></body></tt>"

        fun config(effort: LlmLyricsThinkingEffort): LlmLyricsConfig = LlmLyricsConfig(
            baseUrl = "https://example.invalid/v1",
            model = "test-model",
            thinkingEnabled = true,
            thinkingEffort = effort,
        )
    }
}
