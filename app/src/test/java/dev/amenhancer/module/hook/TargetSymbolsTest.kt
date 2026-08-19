package dev.amenhancer.module.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    fun `media library symbols resolve the singleton refresh reason and ready seam structurally`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.medialibrary.library.MediaLibrary",
                "com.apple.android.medialibrary.library.a",
            ),
            classes = mapOf(
                "com.apple.android.medialibrary.library.MediaLibrary" to
                    MediaLibraryFixture::class.java,
                "com.apple.android.medialibrary.library.a" to
                    MediaLibraryImplementationFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val type = resolver.resolve(AppleMusicSymbols.MediaLibraryType)
        val singleton = resolver.resolve(AppleMusicSymbols.MediaLibrarySingletonMethod)
        val update = resolver.resolve(AppleMusicSymbols.MediaLibraryUpdateMethod)
        val ready = resolver.resolve(AppleMusicSymbols.MediaLibraryReadyMethod)

        assertTrue(type is TargetResolution.Found)
        assertTrue(singleton is TargetResolution.Found)
        assertTrue(update is TargetResolution.Found)
        assertTrue(ready is TargetResolution.Found)
        assertEquals("W", (singleton as TargetResolution.Found).value.name)
        assertEquals("r0", (update as TargetResolution.Found).value.name)
        assertEquals("isReady", (ready as TargetResolution.Found).value.name)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (update as TargetResolution.Found).match)
    }

    @Test
    fun `title correction policy rejects blank or equal catalog values`() {
        assertTrue(TitleCorrectionPolicy.usable("English", "中文"))
        assertTrue(!TitleCorrectionPolicy.usable("", "中文"))
        assertTrue(!TitleCorrectionPolicy.usable("Same", "Same"))
        assertEquals(
            "catalog-title:zh-CN:42",
            TitleCorrectionPolicy.cacheKey("zh-CN", "42"),
        )
    }

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
    fun `650 profile resolves every dual pane and view model hook entry without scanning dex`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.fragment.w0" to DualPaneControllerFixture::class.java,
                "com.apple.android.music.common.activity.PlayerActivity" to DualPaneActivityFixture::class.java,
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    DualPaneLyricsFragmentFixture::class.java,
                "com.apple.android.music.player.fragment.e" to DualPaneLyricsChromeFixture::class.java,
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel" to
                    LyricsViewModelHookFixture::class.java,
                "Hd.b" to ProfileFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolutions = listOf(
            resolver.resolve(AppleMusicSymbols.StackedNavigationMenuOnMeasure),
            resolver.resolve(AppleMusicSymbols.LyricsFragmentOnResume),
            resolver.resolve(AppleMusicSymbols.LyricsChromeAnimate),
            resolver.resolve(AppleMusicSymbols.LyricsFragmentUpdateMetrics),
            resolver.resolve(AppleMusicSymbols.PlayerControllerInitialize),
            resolver.resolve(AppleMusicSymbols.PlayerControllerCreateView),
            resolver.resolve(AppleMusicSymbols.PlayerControllerSelectPane),
            resolver.resolve(AppleMusicSymbols.PlayerActivityCreateStackedNavigationHolder),
            resolver.resolve(AppleMusicSymbols.LyricsViewModelNotifyWordHighlight),
            resolver.resolve(AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine),
        )

        resolutions.forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
            assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        }
        assertEquals(
            listOf(
                "onMeasure",
                "onResume",
                "a2",
                "j2",
                "w1",
                "onCreateView",
                "F1",
                "k1",
                "notifyWordHighlight",
                "setCurrentHighlightedLine",
            ),
            resolutions.map { (it as TargetResolution.Found).value.name },
        )
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the holder root method and behavior field`() {
        val activityName = "com.apple.android.music.common.activity.PlayerActivity"
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = FakeTargetClassSource(classes = mapOf(activityName to DualPaneActivityFixture::class.java)),
        )

        val root = resolver.resolve(AppleMusicSymbols.PlayerActivityRoot)
        val behavior = resolver.resolve(AppleMusicSymbols.PlayerActivityBehaviorField)

        assertTrue(root is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (root as TargetResolution.Found).match)
        assertEquals("n0", root.value.name)
        assertTrue(behavior is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (behavior as TargetResolution.Found).match)
        assertEquals("c1", behavior.value.name)
    }

    @Test
    fun `unknown build structurally resolves unique holder root and behavior contracts`() {
        val activityName = "com.apple.android.music.common.activity.PlayerActivity"
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild.UNKNOWN,
            source = FakeTargetClassSource(
                names = listOf(activityName),
                classes = mapOf(activityName to ObfuscatedDualPaneActivityFixture::class.java),
            ),
        )

        val root = resolver.resolve(AppleMusicSymbols.PlayerActivityRoot)
        val behavior = resolver.resolve(AppleMusicSymbols.PlayerActivityBehaviorField)

        assertTrue(root is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (root as TargetResolution.Found).match)
        assertEquals("x0", root.value.name)
        assertTrue(behavior is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (behavior as TargetResolution.Found).match)
        assertEquals("z7", behavior.value.name)
    }

    @Test
    fun `651 profile resolves migrated dual pane and view model owners without scanning dex`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.fragment.q0" to DualPaneControllerFixture::class.java,
                "com.apple.android.music.common.activity.PlayerActivity" to DualPaneActivity651Fixture::class.java,
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    DualPaneLyricsFragmentFixture::class.java,
                "com.apple.android.music.player.fragment.d" to DualPaneLyricsChromeFixture::class.java,
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel" to
                    LyricsViewModelHookFixture::class.java,
                "Hd.b" to ProfileFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val resolutions = listOf(
            resolver.resolve(AppleMusicSymbols.StackedNavigationMenuOnMeasure),
            resolver.resolve(AppleMusicSymbols.LyricsFragmentOnResume),
            resolver.resolve(AppleMusicSymbols.LyricsChromeAnimate),
            resolver.resolve(AppleMusicSymbols.LyricsFragmentUpdateMetrics),
            resolver.resolve(AppleMusicSymbols.PlayerControllerInitialize),
            resolver.resolve(AppleMusicSymbols.PlayerControllerCreateView),
            resolver.resolve(AppleMusicSymbols.PlayerControllerSelectPane),
            resolver.resolve(AppleMusicSymbols.PlayerActivityCreateStackedNavigationHolder),
            resolver.resolve(AppleMusicSymbols.LyricsViewModelNotifyWordHighlight),
            resolver.resolve(AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine),
        )
        resolutions.forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
            assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
            assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        }
        assertEquals("j1", (resolutions[7] as TargetResolution.Found).value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `651 profile resolves holder support contracts through the activity hierarchy`() {
        val activityName = "com.apple.android.music.common.activity.PlayerActivity"
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = FakeTargetClassSource(classes = mapOf(activityName to DualPaneActivity651Fixture::class.java)),
        )

        val root = resolver.resolve(AppleMusicSymbols.PlayerActivityRoot)
        val behavior = resolver.resolve(AppleMusicSymbols.PlayerActivityBehaviorField)

        assertTrue(root is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (root as TargetResolution.Found).match)
        assertEquals("l1", root.value.name)
        assertTrue(behavior is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (behavior as TargetResolution.Found).match)
        assertEquals("c1", behavior.value.name)
    }

    @Test
    fun `652 profile resolves the APK verified dual pane identity changes`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.fragment.t0" to DualPaneControllerFixture::class.java,
                "com.apple.android.music.common.activity.PlayerActivity" to DualPaneActivityFixture::class.java,
                "com.apple.android.music.player.fragment.e" to DualPaneLyricsChromeFixture::class.java,
                "com.apple.android.music.player.fragment.m" to CurrentItem651Fixture::class.java,
                "Hd.b" to ProfileFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L),
            source = source,
        )

        val controller = resolver.resolve(AppleMusicSymbols.PlayerControllerInitialize)
        val holder = resolver.resolve(AppleMusicSymbols.PlayerActivityCreateStackedNavigationHolder)
        val root = resolver.resolve(AppleMusicSymbols.PlayerActivityRoot)
        val chrome = resolver.resolve(AppleMusicSymbols.LyricsChromeAnimate)
        val currentItem = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        listOf(controller, holder, root, chrome, currentItem).forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
            assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
            assertEquals("apple-music-6.5.2-1586", resolution.profileId)
        }
        assertEquals("k1", (holder as TargetResolution.Found).value.name)
        assertEquals("n0", (root as TargetResolution.Found).value.name)
        assertEquals("a2", (chrome as TargetResolution.Found).value.name)
        assertEquals("c", (currentItem as TargetResolution.Found).value.name)
    }

    @Test
    fun `static collapsed intercept stays independent of version profiles`() {
        val ownerName = "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior"
        val source = FakeTargetClassSource(
            names = listOf(ownerName),
            classes = mapOf(ownerName to StaticCollapsedBehaviorFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StaticCollapsedInterceptMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals("h", resolution.value.name)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        assertEquals(1, source.classNameReads)
    }

    @Test
    fun `unknown build resolves the static collapsed intercept by owner and signature`() {
        val ownerName = "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior"
        val source = FakeTargetClassSource(
            names = listOf(ownerName),
            classes = mapOf(ownerName to StaticCollapsedBehaviorFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.StaticCollapsedInterceptMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals("h", resolution.value.name)
    }

    @Test
    fun `static collapsed intercept rejects a same named wrong signature`() {
        val ownerName = "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior"
        val source = FakeTargetClassSource(
            names = listOf(ownerName),
            classes = mapOf(ownerName to StaticCollapsedWrongSignatureFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        assertTrue(
            resolver.resolve(AppleMusicSymbols.StaticCollapsedInterceptMethod) is TargetResolution.Missing,
        )
    }

    @Test
    fun `controller fallback rejects an isolated same shaped hook entry`() {
        val name = "com.apple.android.music.player.fragment.DecoyController"
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(name),
                classes = mapOf(name to ControllerInitializeOnlyFixture::class.java),
            ),
        )

        assertTrue(
            resolver.resolve(AppleMusicSymbols.PlayerControllerInitialize) is TargetResolution.Missing,
        )
    }

    @Test
    fun `lyrics chrome fallback requires the sibling view contract`() {
        val name = "com.apple.android.music.player.fragment.DecoyChrome"
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(name),
                classes = mapOf(name to DualPaneLyricsChromeFixture::class.java),
            ),
        )

        assertTrue(resolver.resolve(AppleMusicSymbols.LyricsChromeAnimate) is TargetResolution.Missing)
    }

    @Test
    fun `unknown build resolves the unique view model hook entries structurally`() {
        val name = "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(name),
                classes = mapOf(name to LyricsViewModelHookFixture::class.java),
            ),
        )

        val notify = resolver.resolve(AppleMusicSymbols.LyricsViewModelNotifyWordHighlight)
        val current = resolver.resolve(AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine)

        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (notify as TargetResolution.Found).match)
        assertEquals("notifyWordHighlight", notify.value.name)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (current as TargetResolution.Found).match)
        assertEquals("setCurrentHighlightedLine", current.value.name)
    }

    @Test
    fun `view model hook entries report missing when their contracts are absent`() {
        val name = "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(name),
                classes = mapOf(name to BrokenLyricsViewModelHookFixture::class.java),
            ),
        )

        assertTrue(
            resolver.resolve(AppleMusicSymbols.LyricsViewModelNotifyWordHighlight) is TargetResolution.Missing,
        )
        assertTrue(
            resolver.resolve(AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine) is TargetResolution.Missing,
        )
    }

    @Test
    fun `view model hook entry ambiguity reports every matching owner`() {
        val first = "com.apple.first.PlayerLyricsViewModel"
        val second = "com.apple.second.PlayerLyricsViewModel"
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(first, second),
                classes = mapOf(
                    first to LyricsViewModelHookFixture::class.java,
                    second to SecondLyricsViewModelHookFixture::class.java,
                ),
            ),
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsViewModelNotifyWordHighlight)

        assertTrue(resolution is TargetResolution.Ambiguous)
        assertEquals(2, (resolution as TargetResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `unknown build resolves the verified stable menu without trusting the 650 profile`() {
        val source = FakeTargetClassSource(classes = mapOf("Hd.b" to ProfileFixture::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.6.0", 1600L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StackedNavigationMenu)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STABLE_NAME, (resolution as TargetResolution.Found).match)
        assertNull(resolution.profileId)
        assertEquals(0, source.classNameReads)
        assertEquals(1, source.loadCounts["Hd.b"])
    }

    @Test
    fun `mismatched version name uses stable discovery instead of a reused profile`() {
        val source = FakeTargetClassSource(classes = mapOf("Hd.b" to ProfileFixture::class.java))
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.StackedNavigationMenu)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STABLE_NAME, (resolution as TargetResolution.Found).match)
        assertNull(resolution.profileId)
        assertEquals(1, source.loadCounts["Hd.b"])
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
    fun `650 profile resolves the exact o2 item update method without scanning dex`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            classes = mapOf(fragmentName to ItemUpdateFragmentFixture650::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Found)
        val found = resolution as TargetResolution.Found
        assertEquals("o2", found.value.name)
        assertEquals(SymbolMatch.VERSION_PROFILE, found.match)
        assertEquals("apple-music-6.5.0-1580", found.profileId)
        assertEquals(3, found.value.parameterTypes.size)
        assertEquals(
            ItemUpdateFlagsBaseFixture650.c::class.java,
            found.value.parameterTypes[2],
        )
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `651 profile resolves the migrated o2 item update method without scanning dex`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            classes = mapOf(fragmentName to ItemUpdateFragmentFixture651::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Found)
        val found = resolution as TargetResolution.Found
        assertEquals("o2", found.value.name)
        assertEquals(SymbolMatch.VERSION_PROFILE, found.match)
        assertEquals("apple-music-6.5.1-1583", found.profileId)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `structural fallback never selects an o2 without the verified flags contract`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            ),
            classes = mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    BrokenItemUpdateFlagsFragmentFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `structural fallback resolves an exact o2 when the profile is unknown`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            ),
            classes = mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    ItemUpdateFragmentFixture650::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("o2", (resolution as TargetResolution.Found).value.name)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
    }

    @Test
    fun `two same-shaped o2 fragments resolve ambiguous instead of silently choosing`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                "com.apple.android.music.player2.PlayerLyricsViewFragment",
            ),
            classes = mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    ItemUpdateFragmentFixture650::class.java,
                "com.apple.android.music.player2.PlayerLyricsViewFragment" to
                    ItemUpdateFragmentFixture651::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Ambiguous)
        assertEquals(2, (resolution as TargetResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `a fragment without the o2 item update contract stays missing under the profile`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val source = FakeTargetClassSource(
            classes = mapOf(fragmentName to LyricsInstallFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)

        assertTrue(resolution is TargetResolution.Missing)
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
    fun `651 profile resolves the migrated custom lyrics identity funnel without scanning dex`() {
        val source = FakeTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.fragment.l" to CurrentItem651Fixture::class.java,
                "com.apple.android.music.player.f" to PlayerMetadataHubFixture::class.java,
                "com.apple.android.music.player.O" to MetadataConverterFixture::class.java,
                "com.apple.android.music.player.e1" to LyricsAvailabilityFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val currentItem = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)
        val publish = resolver.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val converter = resolver.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)
        val availability = resolver.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)

        listOf(currentItem, publish, converter, availability).forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
            assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
            assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        }
        assertEquals("c", (currentItem as TargetResolution.Found).value.name)
        assertEquals("g", (publish as TargetResolution.Found).value.name)
        assertEquals("b", (converter as TargetResolution.Found).value.name)
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
        val source = FakeTargetClassSource(
            names = listOf(
                ownerName,
                "com.apple.android.music.player.fragment.n",
            ),
            classes = mapOf(
                ownerName to mWrongType::class.java,
                "com.apple.android.music.player.fragment.n" to n::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Missing)
        assertEquals(0, source.classNameReads)
        assertNull(source.loadCounts["com.apple.android.music.model.BaseContentItem"])
    }

    @Test
    fun `exact required profile policy never scans structural candidates`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.structural.Decoy"),
            classes = mapOf("com.apple.structural.Decoy" to FirstFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )
        val symbol = TargetSymbolKey(
            id = "exact-required-fixture",
            profilePolicy = ProfilePolicy.EXACT_REQUIRED,
            structuralCandidates = { classes({ true }) { true } },
            identity = { type: Class<*> -> type.name },
        )

        val resolution = resolver.resolve(symbol)

        assertTrue(resolution is TargetResolution.Missing)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `ambiguous exact preferred profile candidates never fall through`() {
        val source = FakeTargetClassSource(
            names = listOf("com.apple.structural.Fallback"),
            classes = mapOf("com.apple.structural.Fallback" to ProfileFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )
        val symbol = TargetSymbolKey(
            id = "ambiguous-preferred-fixture",
            profilePolicy = ProfilePolicy.EXACT_PREFERRED,
            profileCandidates = { listOf(FirstFixture::class.java, SecondFixture::class.java) },
            structuralCandidates = { classes({ true }) { true } },
            identity = { type: Class<*> -> type.name },
        )

        val resolution = resolver.resolve(symbol)

        assertTrue(resolution is TargetResolution.Ambiguous)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `profile backed symbols declare reviewed fallback policies`() {
        listOf(
            AppleMusicSymbols.PlayerController,
            AppleMusicSymbols.LyricsChromeFragment,
            AppleMusicSymbols.StackedNavigationMenu,
            AppleMusicSymbols.LyricsInstallMethod,
        ).forEach { symbol ->
            assertEquals(ProfilePolicy.EXACT_REQUIRED, symbol.profilePolicy)
        }
        listOf(
            AppleMusicSymbols.PlayerActivity,
            AppleMusicSymbols.EditorialVideoUrlSelector,
            AppleMusicSymbols.LyricsFragment,
            AppleMusicSymbols.LyricsLineVector,
            AppleMusicSymbols.LyricsSessionProcessor,
            AppleMusicSymbols.LyricsHighlightCallback,
            AppleMusicSymbols.LyricsViewModel,
            AppleMusicSymbols.SongInfoPtr,
            AppleMusicSymbols.SongInfoNative,
            AppleMusicSymbols.TtmlParserNative,
            AppleMusicSymbols.PlayerMetadataPublishMethod,
            AppleMusicSymbols.MetadataToPlaybackItemMethod,
            AppleMusicSymbols.LyricsAvailabilityPredicate,
            AppleMusicSymbols.TtmlSongInfoFromTtml,
            AppleMusicSymbols.LyricsCurrentItemField,
            AppleMusicSymbols.StackedNavigationMenuOnMeasure,
            AppleMusicSymbols.LyricsFragmentOnResume,
            AppleMusicSymbols.LyricsChromeAnimate,
            AppleMusicSymbols.LyricsFragmentUpdateMetrics,
            AppleMusicSymbols.PlayerControllerInitialize,
            AppleMusicSymbols.PlayerControllerCreateView,
            AppleMusicSymbols.PlayerControllerSelectPane,
            AppleMusicSymbols.PlayerActivityCreateStackedNavigationHolder,
            AppleMusicSymbols.PlayerActivityRoot,
            AppleMusicSymbols.PlayerActivityBehaviorField,
            AppleMusicSymbols.LyricsViewModelNotifyWordHighlight,
            AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine,
        ).forEach { symbol ->
            assertEquals(ProfilePolicy.EXACT_PREFERRED, symbol.profilePolicy)
        }
        assertEquals(ProfilePolicy.NO_PROFILE, AppleMusicSymbols.RecyclerView.profilePolicy)
    }

    @Test
    fun `a matched profile falls back to the verified current item hierarchy when its owner is stale`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val currentItemOwner = "com.apple.android.music.player.fragment.z"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName, currentItemOwner),
            classes = mapOf(
                fragmentName to CurrentLyricsFragmentFixture::class.java,
                currentItemOwner to RenamedCurrentItemOwnerFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        resolution as TargetResolution.Found
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
        assertEquals("renamedItem", resolution.value.name)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        assertEquals(1, source.classNameReads)
    }

    @Test
    fun `unknown minor version discovers the migrated lyrics identity funnel by contract`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val currentItemOwner = "com.apple.android.music.player.fragment.z"
        val metadataHub = "com.apple.android.music.player.y"
        val metadataConverter = "com.apple.android.music.player.X"
        val availabilityOwner = "com.apple.android.music.player.z1"
        val source = FakeTargetClassSource(
            names = listOf(
                fragmentName,
                currentItemOwner,
                metadataHub,
                metadataConverter,
                availabilityOwner,
                "com.apple.android.music.player.fragment.n",
            ),
            classes = mapOf(
                fragmentName to CurrentLyricsFragmentFixture::class.java,
                currentItemOwner to RenamedCurrentItemOwnerFixture::class.java,
                metadataHub to RenamedPlayerMetadataHubFixture::class.java,
                metadataConverter to RenamedMetadataConverterFixture::class.java,
                availabilityOwner to RenamedLyricsAvailabilityFixture::class.java,
                "com.apple.android.music.player.fragment.n" to n::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1590L),
            source = source,
        )

        val currentItem = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)
        val publish = resolver.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val converter = resolver.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)
        val availability = resolver.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)

        listOf(currentItem, publish, converter, availability).forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
            assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
            assertNull(resolution.profileId)
        }
        assertEquals("renamedItem", (currentItem as TargetResolution.Found).value.name)
        assertEquals("publish", (publish as TargetResolution.Found).value.name)
        assertEquals("convert", (converter as TargetResolution.Found).value.name)
        assertEquals("available", (availability as TargetResolution.Found).value.name)
        assertEquals(1, source.classNameReads)
    }

    @Test
    fun `matched profile reports ambiguous structural metadata converters`() {
        val source = FakeTargetClassSource(
            names = listOf(
                "com.apple.android.music.player.X",
                "com.apple.android.music.player.Y",
            ),
            classes = mapOf(
                "com.apple.android.music.player.X" to RenamedMetadataConverterFixture::class.java,
                "com.apple.android.music.player.Y" to SecondMetadataConverterFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)

        assertTrue(resolution is TargetResolution.Ambiguous)
        resolution as TargetResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
    }

    @Test
    fun `structural funnel rejects primary shapes without corroborating members`() {
        val validConverter = "com.apple.android.music.player.X"
        val weakConverter = "com.apple.android.music.player.Y"
        val weakHub = "com.apple.android.music.player.z"
        val weakAvailability = "com.apple.android.music.player.z1"
        val source = FakeTargetClassSource(
            names = listOf(validConverter, weakConverter, weakHub, weakAvailability),
            classes = mapOf(
                validConverter to RenamedMetadataConverterFixture::class.java,
                weakConverter to PrimaryOnlyMetadataConverterFixture::class.java,
                weakHub to PrimaryOnlyMetadataHubFixture::class.java,
                weakAvailability to PrimaryOnlyLyricsAvailabilityFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )

        val publish = resolver.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val availability = resolver.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)

        assertTrue(publish is TargetResolution.Missing)
        assertTrue(availability is TargetResolution.Missing)

        val weakConverterResolver = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            FakeTargetClassSource(
                names = listOf(weakConverter),
                classes = mapOf(weakConverter to PrimaryOnlyMetadataConverterFixture::class.java),
            ),
        )
        assertTrue(
            weakConverterResolver.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod) is
                TargetResolution.Missing,
        )
    }

    @Test
    fun `highlight callback structural fallback stays inside the session processor owner`() {
        val vectorName = "com.apple.android.music.ttml.javanative.model.LyricsLineVector"
        val callbackName = "com.apple.android.music.ttml.SongInfoTimeProcessor\$renamedCallback"
        val decoyName = "com.apple.android.music.unrelated.Callback"
        val source = FakeTargetClassSource(
            names = listOf(vectorName, callbackName, decoyName),
            classes = mapOf(
                vectorName to ProfileVector::class.java,
                callbackName to ProfileCallback::class.java,
                decoyName to DecoyProfileCallback::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsHighlightCallback)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertNull(source.loadCounts[decoyName])
    }

    @Test
    fun `structural ambiguity of the current item field is reported instead of a silent first match`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val ownerName = "com.apple.android.music.player.fragment.z"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName, ownerName),
            classes = mapOf(
                fragmentName to AmbiguousLyricsFragmentFixture::class.java,
                ownerName to AmbiguousCurrentItemOwnerFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Ambiguous)
        resolution as TargetResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        assertTrue(
            resolution.candidates.any { candidate ->
                candidate.contains(
                    "AmbiguousCurrentItemOwnerFixture#first:" +
                        "com.apple.android.music.model.BaseContentItem",
                )
            },
        )
    }

    @Test
    fun `stale profile rejects an old c field beside a renamed current item field`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val ownerName = "com.apple.android.music.player.fragment.z"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName, ownerName),
            classes = mapOf(
                fragmentName to MixedCurrentItemFragmentFixture::class.java,
                ownerName to MixedCurrentItemOwnerFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Ambiguous)
        resolution as TargetResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
    }

    @Test
    fun `structural fallback uniquely finds the current item field`() {
        val fragmentName = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        val ownerName = "com.apple.android.music.player.fragment.z"
        val source = FakeTargetClassSource(
            names = listOf(fragmentName, ownerName),
            classes = mapOf(
                fragmentName to CurrentLyricsFragmentFixture::class.java,
                ownerName to RenamedCurrentItemOwnerFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals("renamedItem", resolution.value.name)
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

private open class ItemUpdateFlagsBaseFixture650 {
    class c {
        @JvmField
        var a: Boolean = false

        @JvmField
        var b: Boolean = false

        @JvmField
        var c: Boolean = false
    }
}

private class ItemUpdateFragmentFixture650 : ItemUpdateFlagsBaseFixture650() {
    @Suppress("UNUSED_PARAMETER")
    fun o2(metadata: v3.v, item: BaseContentItem, flags: ItemUpdateFlagsBaseFixture650.c) = Unit
}

private open class ItemUpdateFlagsBaseFixture651 {
    class c {
        @JvmField
        var a: Boolean = false

        @JvmField
        var b: Boolean = false

        @JvmField
        var c: Boolean = false
    }
}

private class ItemUpdateFragmentFixture651 : ItemUpdateFlagsBaseFixture651() {
    @Suppress("UNUSED_PARAMETER")
    fun o2(metadata: v3.v, item: BaseContentItem, flags: ItemUpdateFlagsBaseFixture651.c) = Unit
}

/** Same binary fragment name and o2 name but a flags holder without the exact field set. */
private open class BrokenItemUpdateFlagsBaseFixture {
    class c {
        @JvmField
        var a: Boolean = false
    }
}

private class BrokenItemUpdateFlagsFragmentFixture : BrokenItemUpdateFlagsBaseFixture() {
    @Suppress("UNUSED_PARAMETER")
    fun o2(metadata: v3.v, item: BaseContentItem, flags: BrokenItemUpdateFlagsBaseFixture.c) = Unit
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

private class CurrentItem651Fixture {
    val c: BaseContentItem = BaseContentItem()
}

private open class RenamedCurrentItemOwnerFixture {
    val renamedItem: BaseContentItem = BaseContentItem()
}

private class CurrentLyricsFragmentFixture : RenamedCurrentItemOwnerFixture()

private open class AmbiguousCurrentItemOwnerFixture {
    val first: BaseContentItem = BaseContentItem()
    val second: BaseContentItem = BaseContentItem()
}

private class AmbiguousLyricsFragmentFixture : AmbiguousCurrentItemOwnerFixture()

private open class MixedCurrentItemOwnerFixture {
    val c: BaseContentItem = BaseContentItem()
    val renamedItem: BaseContentItem = BaseContentItem()
}

private class MixedCurrentItemFragmentFixture : MixedCurrentItemOwnerFixture()

private class RenamedPlayerMetadataHubFixture {
    @Suppress("UNUSED_PARAMETER")
    fun publish(metadata: v3.v) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onMediaMetadataChanged(metadata: v3.v) = Unit
}

private class RenamedMetadataConverterFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun convert(metadata: v3.v): com.apple.android.music.model.PlaybackItem =
            com.apple.android.music.model.PlaybackItem()

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun extract(metadata: v3.v): BaseContentItem = BaseContentItem()
    }
}

private class SecondMetadataConverterFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun transform(metadata: OtherMetadata): com.apple.android.music.model.PlaybackItem =
            com.apple.android.music.model.PlaybackItem()

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun extract(metadata: OtherMetadata): BaseContentItem = BaseContentItem()
    }
}

private class OtherMetadata

private class PrimaryOnlyMetadataConverterFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun convert(metadata: v3.v): com.apple.android.music.model.PlaybackItem =
            com.apple.android.music.model.PlaybackItem()
    }
}

private class PrimaryOnlyMetadataHubFixture {
    @Suppress("UNUSED_PARAMETER")
    fun publish(metadata: v3.v) = Unit
}

private class PrimaryOnlyLyricsAvailabilityFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun available(item: com.apple.android.music.model.PlaybackItem): Boolean = false
    }
}

private class RenamedLyricsAvailabilityFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun available(item: com.apple.android.music.model.PlaybackItem): Boolean = false

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun changed(
            first: com.apple.android.music.model.PlaybackItem,
            second: com.apple.android.music.model.PlaybackItem,
        ): Boolean = false
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

private class ProfileFixture {
    @Suppress("UNUSED_PARAMETER")
    fun onMeasure(width: Int, height: Int) = Unit
}

private class StaticCollapsedBehaviorFixture {
    @Suppress("UNUSED_PARAMETER")
    fun h(
        parent: androidx.coordinatorlayout.widget.CoordinatorLayout,
        child: View,
        event: MotionEvent,
    ): Boolean = false
}

private class StaticCollapsedWrongSignatureFixture {
    @Suppress("UNUSED_PARAMETER")
    fun h(parent: View, child: View, event: MotionEvent): Boolean = false
}

private class BagConfig

private enum class PlayerPane

private class DualPaneControllerFixture {
    @Suppress("UNUSED_PARAMETER")
    fun w1(config: BagConfig) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View =
        throw UnsupportedOperationException()

    @Suppress("UNUSED_PARAMETER")
    fun F1(pane: PlayerPane, state: Bundle) = Unit
}

private class ControllerInitializeOnlyFixture {
    @Suppress("UNUSED_PARAMETER")
    fun w1(config: BagConfig) = Unit
}

private open class BaseDualPaneActivityFixture {
    @Suppress("unused")
    private val c1 = com.apple.android.music.player.PlayerBottomSheetBehavior()

    fun n0(): View = throw UnsupportedOperationException()
}

private class DualPaneActivityFixture : BaseDualPaneActivityFixture() {
    fun k1(): com.apple.android.music.common.activity.PlayerActivity.m =
        com.apple.android.music.common.activity.PlayerActivity.m()
}

private class DualPaneActivity651Fixture : BaseDualPaneActivityFixture() {
    fun j1(): com.apple.android.music.common.activity.PlayerActivity.m =
        com.apple.android.music.common.activity.PlayerActivity.m()

    fun l1(): View = throw UnsupportedOperationException()
}

private open class ObfuscatedBaseDualPaneActivityFixture {
    @Suppress("unused")
    private val z7 = com.apple.android.music.player.PlayerBottomSheetBehavior()

    fun x0(): View = throw UnsupportedOperationException()
}

private class ObfuscatedDualPaneActivityFixture : ObfuscatedBaseDualPaneActivityFixture()

private class DualPaneLyricsFragmentFixture {
    fun onResume() = Unit
    fun j2(): Boolean = false
}

private class DualPaneLyricsChromeFixture {
    @Suppress("UNUSED_PARAMETER")
    fun a2(mode: Int, values: IntArray) = Unit
}

private class LyricsViewModelHookFixture {
    @Suppress("UNUSED_PARAMETER")
    fun notifyWordHighlight(lineId: Int, word: Int, character: Int, isBackground: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setCurrentHighlightedLine(lineId: Int) = Unit
}

private class SecondLyricsViewModelHookFixture {
    @Suppress("UNUSED_PARAMETER")
    fun notifyWordHighlight(lineId: Int, word: Int, character: Int, isBackground: Boolean) = Unit
}

private class BrokenLyricsViewModelHookFixture

private class ProfileVector
private class ProfileCallback {
    @Suppress("UNUSED_PARAMETER")
    fun call(time: Long, lines: ProfileVector, position: Long) = Unit
}
private class DecoyProfileCallback {
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

private interface MediaLibraryFixture {
    fun isReady(): Boolean
}

private enum class RefreshReasonFixture {
    UserInitiatedPoll,
    PeriodicPoll,
}

private class MediaLibraryImplementationFixture : MediaLibraryFixture {
    companion object {
        @JvmStatic
        fun W(): MediaLibraryFixture = MediaLibraryImplementationFixture()
    }

    @Suppress("UNUSED_PARAMETER")
    fun r0(reason: RefreshReasonFixture): Any = Unit

    override fun isReady(): Boolean = true
}
