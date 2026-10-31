package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LlmLyricsThinkingEffort
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmLyricsCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var cache: FileLlmLyricsCache
    private val original = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en"><body><div><p begin="00:00:01.000" end="00:00:02.000">Hello</p></div></body></tt>"""
    private val translated: String
        get() = checkNotNull(LlmLyricsDocument.prepare(original)?.withTranslations(listOf("你好")))

    @Before
    fun setUp() {
        directory = temporary.newFolder("llm")
        cache = FileLlmLyricsCache(directory)
    }

    @Test
    fun `explicit efforts round trip through a fresh cache instance with version two`() {
        val efforts = listOf(
            LlmLyricsThinkingEffort.LOW to "low",
            LlmLyricsThinkingEffort.HIGH to "high",
            LlmLyricsThinkingEffort.MAX to "max",
        )
        for ((index, pair) in efforts.withIndex()) {
            val id = index + 1L
            val config = config(effort = pair.first)
            assertTrue(cache.write(id, original, config, translated))
            assertEquals(translated, FileLlmLyricsCache(directory).read(id, original, config))
            val raw = JSONObject(entryFile(id).readText())
            assertEquals(2, raw.getInt("version"))
            assertEquals(pair.second, raw.getString("thinkingEffort"))
        }
    }

    @Test
    fun `different thinking modes and explicit efforts never share a cached translation`() {
        val configs = listOf(
            config(thinking = false), config(), config(effort = LlmLyricsThinkingEffort.LOW),
            config(effort = LlmLyricsThinkingEffort.HIGH), config(effort = LlmLyricsThinkingEffort.MAX),
        )
        var id = 1L
        for (written in configs) {
            for (requested in configs) {
                if (written == requested) continue
                assertTrue(cache.write(id, original, written, translated))
                assertNull("Unexpected reuse from $written to $requested", cache.read(id, original, requested))
                id++
            }
        }
    }

    @Test
    fun `saved effort is ignored by cache identity while thinking is disabled`() {
        assertTrue(cache.write(1, original, config(thinking = false, effort = LlmLyricsThinkingEffort.MAX), translated))
        for (effort in LlmLyricsThinkingEffort.values()) {
            assertEquals(translated, cache.read(1, original, config(thinking = false, effort = effort)))
        }
        val raw = JSONObject(entryFile(1).readText())
        assertFalse(raw.getBoolean("thinkingEnabled"))
        assertEquals("default", raw.getString("thinkingEffort"))
    }

    @Test
    fun `legacy version one without effort is only reusable by default effort`() {
        for ((index, effort) in LlmLyricsThinkingEffort.values().withIndex()) {
            val id = index + 1L
            assertTrue(cache.write(id, original, config(), translated))
            rewrite(id) { put("version", 1); remove("thinkingEffort") }
            val actual = FileLlmLyricsCache(directory).read(id, original, config(effort = effort))
            if (effort == LlmLyricsThinkingEffort.DEFAULT) assertEquals(translated, actual)
            else assertNull(actual)
        }
    }

    @Test
    fun `legacy entries missing the thinking flag remain disabled`() {
        assertTrue(cache.write(1, original, config(thinking = false), translated))
        rewrite(1) { put("version", 1); remove("thinkingEffort"); remove("thinkingEnabled") }
        assertEquals(translated, cache.read(1, original, config(thinking = false)))
        assertNull(cache.read(1, original, config()))
    }

    @Test
    fun `version two with missing or unknown effort is rejected rather than treated as default`() {
        val invalid = listOf<Any?>(null, "unknown", "", 42, JSONObject.NULL)
        for ((index, effort) in invalid.withIndex()) {
            val id = index + 1L
            assertTrue(cache.write(id, original, config(), translated))
            rewrite(id) { if (effort == null) remove("thinkingEffort") else put("thinkingEffort", effort) }
            assertNull(FileLlmLyricsCache(directory).read(id, original, config()))
        }
    }

    @Test
    fun `a future cache version is not accepted with a known effort`() {
        assertTrue(cache.write(1, original, config(effort = LlmLyricsThinkingEffort.HIGH), translated))
        rewrite(1) { put("version", 3) }
        assertNull(cache.read(1, original, config(effort = LlmLyricsThinkingEffort.HIGH)))
    }

    @Test
    fun `changing effort replaces the existing song slot`() {
        val low = config(effort = LlmLyricsThinkingEffort.LOW)
        val high = config(effort = LlmLyricsThinkingEffort.HIGH)
        assertTrue(cache.write(1, original, low, translated))
        val revised = checkNotNull(LlmLyricsDocument.prepare(original)?.withTranslations(listOf("您好")))
        assertTrue(cache.write(1, original, high, revised))
        assertEquals(revised, FileLlmLyricsCache(directory).read(1, original, high))
        assertEquals(1, directory.listFiles()!!.count { it.name.startsWith("llm_") && it.extension == "json" })
        assertNull(cache.read(1, original, low))
    }

    private fun config(
        thinking: Boolean = true,
        effort: LlmLyricsThinkingEffort = LlmLyricsThinkingEffort.DEFAULT,
    ) = LlmLyricsConfig(
        baseUrl = "http://127.0.0.1:12345", model = "lyrics-model",
        thinkingEnabled = thinking, thinkingEffort = effort,
    )

    private fun entryFile(id: Long) = File(directory, "llm_$id.json")

    private fun rewrite(id: Long, change: JSONObject.() -> Unit) {
        val file = entryFile(id)
        file.writeText(JSONObject(file.readText()).apply(change).toString())
    }
}
