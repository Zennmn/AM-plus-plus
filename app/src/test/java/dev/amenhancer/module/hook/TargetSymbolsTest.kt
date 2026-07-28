package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSymbolsTest {
    @Test
    fun `650 profile resolves exact symbol without enumerating dex names`() {
        val source = FakeTargetClassSource(classes = mapOf("Hd.b" to ProfileFixture::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StackedNavigationMenu)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals(ProfileFixture::class.java, resolution.value)
        assertEquals(0, source.classNameReads)
        assertEquals(1, source.loadCounts["Hd.b"])
    }

    @Test
    fun `650 profile resolves the verified lyric callback without scanning dex`() {
        val vectorName = "com.apple.android.music.ttml.javanative.model.LyricsLineVector"
        val callbackName =
            "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents\$lineEventCallback\$1"
        val source = FakeTargetClassSource(
            classes = mapOf(
                vectorName to ProfileVector::class.java,
                callbackName to ProfileCallback::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsHighlightCallback)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals("call", resolution.value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `unknown build does not trust the 650 exact profile`() {
        val source = FakeTargetClassSource(classes = mapOf("Hd.b" to ProfileFixture::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.6.0", 1600L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StackedNavigationMenu)

        assertTrue(resolution is TargetResolution.Missing)
        assertEquals(1, source.classNameReads)
        assertNull(source.loadCounts["Hd.b"])
    }

    @Test
    fun `mismatched version name does not trust a reused version code`() {
        val source = FakeTargetClassSource(classes = mapOf("Hd.b" to ProfileFixture::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StackedNavigationMenu)

        assertTrue(resolution is TargetResolution.Missing)
        assertNull(source.loadCounts["Hd.b"])
    }

    @Test
    fun `structural fallback returns its only matching candidate`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.first", "com.apple.second"),
            classes = mapOf(
                "com.apple.first" to FirstFixture::class.java,
                "com.apple.second" to SecondFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(fixtureKey { it.endsWith("second") })

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals(SecondFixture::class.java, resolution.value)
    }

    @Test
    fun `structural ambiguity is reported instead of taking the first class`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.first", "com.apple.second"),
            classes = mapOf(
                "com.apple.first" to FirstFixture::class.java,
                "com.apple.second" to SecondFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(fixtureKey { true })

        assertTrue(resolution is TargetResolution.Ambiguous)
        resolution as TargetResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
        assertTrue(resolution.summary.contains(FirstFixture::class.java.name))
    }

    @Test
    fun `one broken class contract does not block another structural candidate`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.first", "com.apple.second"),
            classes = mapOf(
                "com.apple.first" to FirstFixture::class.java,
                "com.apple.second" to SecondFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)
        val key = TargetSymbolKey(
            id = "throwing-contract",
            structuralCandidates = {
                classes(namePredicate = { true }) { candidate ->
                    if (candidate == FirstFixture::class.java) error("broken reflection")
                    true
                }
            },
            identity = { type: Class<*> -> type.name },
        )

        val resolution = resolver.resolve(key)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SecondFixture::class.java, (resolution as TargetResolution.Found).value)
    }

    @Test
    fun `dex inventory and class loads are cached across symbol keys`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.shared"),
            classes = mapOf("com.apple.shared" to FirstFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        resolver.resolve(fixtureKey { it == "com.apple.shared" })
        resolver.resolve(fixtureKey { it.startsWith("com.apple.") })

        assertEquals(1, source.classNameReads)
        assertEquals(1, source.loadCounts["com.apple.shared"])
    }

    @Test
    fun `failed class loads are cached and remain missing`() {
        val source = FakeTargetClassSource(names = listOf("com.apple.missing"))
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val first = resolver.resolve(fixtureKey { true })
        val second = resolver.resolve(fixtureKey { true })

        assertTrue(first is TargetResolution.Missing)
        assertTrue(second is TargetResolution.Missing)
        assertEquals(1, source.classNameReads)
        assertEquals(1, source.loadCounts["com.apple.missing"])
    }

    private fun fixtureKey(predicate: (String) -> Boolean) = TargetSymbolKey(
        id = "fixture-" + System.identityHashCode(predicate),
        structuralCandidates = { classes(predicate) { true } },
        identity = { type: Class<*> -> type.name },
    )
}

private class FakeTargetClassSource(
    private val names: List<String> = emptyList(),
    private val classes: Map<String, Class<*>> = emptyMap(),
) : TargetClassSource {
    var classNameReads: Int = 0
        private set
    val loadCounts = mutableMapOf<String, Int>()

    override fun classNames(): List<String> {
        classNameReads += 1
        return names
    }

    override fun loadClass(name: String): Class<*>? {
        loadCounts[name] = loadCounts.getOrDefault(name, 0) + 1
        return classes[name]
    }
}

private class ProfileFixture
private class ProfileVector
private class ProfileCallback {
    @Suppress("UNUSED_PARAMETER")
    fun call(time: Long, lines: ProfileVector, position: Long) = Unit
}
private class FirstFixture
private class SecondFixture
