package dev.amenhancer.module.hook

import com.apple.android.music.model.BaseContentItem
import com.apple.android.music.player.fragment.m
import com.apple.android.music.player.fragment.mWrongType
import com.apple.android.music.player.fragment.n
import com.apple.android.music.ttml.javanative.model.SongInfo
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
    fun `650 profile resolves the lyric session processor without scanning dex`() {
        val processorName = "com.apple.android.music.ttml.SongInfoTimeProcessor"
        val source = FakeTargetClassSource(
            classes = mapOf(processorName to ProfileSessionProcessor::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsSessionProcessor)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals("processEvents", resolution.value.name)
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

    @Test
    fun `650 profile resolves the exact I2 lyrics install method among same-shaped R2`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            classes = mapOf(fragmentName to LyricsInstallFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsInstallMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("I2", (resolution as TargetResolution.Found).value.name)
        assertEquals(SymbolMatch.VERSION_PROFILE, resolution.match)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the player metadata identity funnel`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.f" to PlayerMetadataHubFixture::class.java,
                "com.apple.android.music.player.P" to MetadataConverterFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val publish = resolver.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val converter = resolver.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)

        assertTrue(publish is TargetResolution.Found)
        assertEquals("g", (publish as TargetResolution.Found).value.name)
        assertTrue(converter is TargetResolution.Found)
        assertEquals("b", (converter as TargetResolution.Found).value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the lyrics availability gate`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.d1" to LyricsAvailabilityFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val availability = resolver.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)

        assertTrue(availability is TargetResolution.Found)
        assertEquals("i", (availability as TargetResolution.Found).value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `structural fallback never mistakes same-shaped R2 for I2`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName),
            classes = mapOf(fragmentName to LyricsInstallFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsInstallMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("I2", (resolution as TargetResolution.Found).value.name)
    }

    @Test
    fun `a class with only the same-shaped R2 method stays missing`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName),
            classes = mapOf(fragmentName to LyricsR2OnlyFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsInstallMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `650 profile resolves the native ttml parse method`() {
        val parserName =
            "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative"
        val source = FakeTargetClassSource(
            classes = mapOf(parserName to TtmlParserNativeFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(
            "songInfoFromTTML",
            (resolution as TargetResolution.Found).value.name,
        )
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the song info pointer class with its contract`() {
        val ptrName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
        val source = FakeTargetClassSource(
            classes = mapOf(ptrName to SongInfo.SongInfoPtr::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.SongInfoPtr)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(ptrName, (resolution as TargetResolution.Found).value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the song info native class with its contract`() {
        val nativeName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative"
        val source = FakeTargetClassSource(
            classes = mapOf(nativeName to SongInfo.SongInfoNative::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.SongInfoNative)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SongInfo.SongInfoNative::class.java, (resolution as TargetResolution.Found).value)
    }

    @Test
    fun `650 profile resolves the ttml parser native class with its contract`() {
        val parserName =
            "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative"
        val source = FakeTargetClassSource(
            classes = mapOf(parserName to TtmlParserNativeContractFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.TtmlParserNative)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(
            TtmlParserNativeContractFixture::class.java,
            (resolution as TargetResolution.Found).value,
        )
        assertEquals(SymbolMatch.VERSION_PROFILE, resolution.match)
    }

    @Test
    fun `a contract broken class stays missing instead of matching by name`() {
        val ptrName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
        val source = FakeTargetClassSource(
            names = listOf(ptrName),
            classes = mapOf(ptrName to BrokenSongInfoPtrFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.SongInfoPtr)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `650 profile resolves the exact current lyric item field without scanning dex`() {
        val ownerName = "com.apple.android.music.player.fragment.m"
        val source = FakeTargetClassSource(classes = mapOf(ownerName to m::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        val found = resolution as TargetResolution.Found
        assertEquals(SymbolMatch.VERSION_PROFILE, found.match)
        assertEquals("c", found.value.name)
        assertEquals(BaseContentItem::class.java, found.value.type)
        assertEquals(0, source.classNameReads)
        assertEquals(1, source.loadCounts[ownerName])
    }

    @Test
    fun `a contract broken current item field stays missing under the profile`() {
        val ownerName = "com.apple.android.music.player.fragment.m"
        val source = FakeTargetClassSource(classes = mapOf(ownerName to mWrongType::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Missing)
        assertNull(source.loadCounts["com.apple.android.music.model.BaseContentItem"])
    }

    @Test
    fun `structural ambiguity of the current item field is reported instead of a silent first match`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.music.player.fragment.m",
                "com.apple.android.music.player.fragment.n",
            ),
            classes = mapOf(
                "com.apple.android.music.player.fragment.m" to m::class.java,
                "com.apple.android.music.player.fragment.n" to n::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Ambiguous)
        resolution as TargetResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
        assertTrue(
            resolution.candidates.any { candidate ->
                candidate.contains(
                    "com.apple.android.music.player.fragment.m#c:" +
                        "com.apple.android.music.model.BaseContentItem",
                )
            },
        )
    }

    @Test
    fun `structural fallback uniquely finds the current item field`() {
        val ownerName = "com.apple.android.music.player.fragment.m"
        val source = FakeTargetClassSource(
            names = listOf(ownerName),
            classes = mapOf(ownerName to m::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals("c", resolution.value.name)
        assertEquals(BaseContentItem::class.java, resolution.value.type)
    }

    private fun fixtureKey(predicate: (String) -> Boolean) = TargetSymbolKey(
        id = "fixture-" + System.identityHashCode(predicate),
        structuralCandidates = { classes(predicate) { true } },
        identity = { type: Class<*> -> type.name },
    )
}

private class LyricsInstallFixture {
    @Suppress("UNUSED_PARAMETER")
    fun I2(ptr: SongInfo.SongInfoPtr) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun R2(ptr: SongInfo.SongInfoPtr) = Unit
}

private class LyricsR2OnlyFixture {
    @Suppress("UNUSED_PARAMETER")
    fun R2(ptr: SongInfo.SongInfoPtr) = Unit
}

private class PlayerMetadataHubFixture {
    @Suppress("UNUSED_PARAMETER")
    fun g(metadata: v3.v) = Unit
}

private class MetadataConverterFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun b(metadata: v3.v): com.apple.android.music.model.PlaybackItem =
            com.apple.android.music.model.PlaybackItem()
    }
}

private class LyricsAvailabilityFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun i(item: com.apple.android.music.model.PlaybackItem): Boolean = false
    }
}

private class TtmlParserNativeFixture {
    @Suppress("UNUSED_PARAMETER")
    fun songInfoFromTTML(ttml: String): SongInfo.SongInfoPtr = SongInfo.SongInfoPtr()
}

private class TtmlParserNativeContractFixture {
    @Suppress("UNUSED_PARAMETER")
    fun songInfoFromTTML(ttml: String): SongInfo.SongInfoPtr = SongInfo.SongInfoPtr()
}

/** Same binary name as the real SongInfoPtr but without the `get()` contract. */
private class BrokenSongInfoPtrFixture

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
private class ProfileSessionProcessor {
    @Suppress("UNUSED_PARAMETER")
    fun processEvents(
        songInfo: SongInfo.SongInfoPtr,
        position: Long,
        line: Any,
        word: Any,
        backgroundWord: Any,
        transliterationWord: Any,
        transliterationBackgroundWord: Any,
    ): Long = 0L
}
private class FirstFixture
private class SecondFixture
