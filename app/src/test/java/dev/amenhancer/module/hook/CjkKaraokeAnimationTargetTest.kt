package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkKaraokeAnimationTargetTest {
    @Test
    fun `only a newly created special animator commits a CJK glow baseline`() {
        val existing = Any()

        assertFalse(hasNewCjkGlowAnimator(listOf(existing), listOf(existing)))
        assertTrue(hasNewCjkGlowAnimator(listOf(existing), listOf(existing, Any())))
    }

    @Test
    fun `CJK classifier scope is gated by complete hook installation`() {
        val source = targetSource()
        val scope = source.substringAfter("private fun isSingleWordScope")
            .substringBefore("private fun a0Stack")

        assertTrue(source.contains("@Volatile\n    private var hooksReady = false"))
        assertTrue(scope.contains("hooksReady"))
        assertTrue(source.contains("val hooksInstalled = a0Installed && helperInstalled"))
        assertTrue(source.contains("hooksReady = hooksInstalled"))
    }

    @Test
    fun `candidate view is committed only after a0 confirms a new special animator`() {
        val source = targetSource()
        val enter = source.substringAfter("private fun enterA0Scope")
            .substringBefore("private fun completeA0Scope")

        assertFalse(enter.contains("trackedGlowViews"))
        assertTrue(source.contains("completeA0Scope"))
        assertTrue(source.contains("hasNewCjkGlowAnimator"))
    }

    @Test
    fun `glow cleanup leaves host-owned vertical position untouched`() {
        val source = targetSource()
        val cleanup = source.substringAfter("private fun resetCjkGlowView")
            .substringBefore("private fun captureCjkGlowBaseline")

        assertFalse(cleanup.contains("setTranslationY"))
        assertFalse(cleanup.contains("baseline.translationY"))
        assertFalse(source.contains("val translationY: Float"))
    }

    private fun targetSource(): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt"),
        File("app/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt"),
    ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
        ?: error("AppleMusicCjkKaraokeAnimationTarget.kt was not found")

    @Test
    fun `predicate recognizes host CJK script blocks but not latin text`() {
        assertTrue(containsCjkKaraokeScript("漢"))
        assertTrue(containsCjkKaraokeScript("ひ"))
        assertTrue(containsCjkKaraokeScript("カ"))
        assertTrue(containsCjkKaraokeScript("한"))
        assertTrue(!containsCjkKaraokeScript("lyrics"))
    }

    @Test
    fun `single unmerged CJK word is allowed without duplicating AM trigger gates`() {
        assertTrue(
            isSingleUnmergedCjkWord(
                CjkKaraokeWordTiming(
                    text = "漢",
                    nativeDurationMs = 200,
                    cumulativeDurationMs = 200,
                    cumulativeTextLength = 1,
                    splitBindingCount = 0,
                    isBackground = false,
                ),
            ),
        )
    }

    @Test
    fun `merged or split CJK chunks stay on Apple's original classifier`() {
        val merged = CjkKaraokeWordTiming(
            text = "漢字",
            nativeDurationMs = 600,
            cumulativeDurationMs = 1_200,
            cumulativeTextLength = 2,
            splitBindingCount = 0,
            isBackground = false,
        )
        val split = merged.copy(
            text = "漢",
            cumulativeDurationMs = 600,
            cumulativeTextLength = 1,
            splitBindingCount = 2,
        )

        assertFalse(isSingleUnmergedCjkWord(merged))
        assertFalse(isSingleUnmergedCjkWord(split))
    }

    @Test
    fun `background and multi-code-point text are fail closed`() {
        val background = CjkKaraokeWordTiming(
            text = "한",
            nativeDurationMs = 1_200,
            cumulativeDurationMs = 1_200,
            cumulativeTextLength = 1,
            splitBindingCount = 0,
            isBackground = true,
        )
        val combining = background.copy(
            text = "が",
            isBackground = false,
        )

        assertFalse(isSingleUnmergedCjkWord(background))
        assertFalse(isSingleUnmergedCjkWord(combining))
    }

    @Test
    fun `karaoke symbols are exact profile only and reject 650 and unknown builds`() {
        val source = FixtureTargetClassSource(
            classes = mapOf(
                "com.apple.android.music.player.z" to CjkAnimationFixture::class.java,
                "com.apple.android.music.utils.I0\$a" to CjkHelperFixture::class.java,
            ),
        )

        val exact652 = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L),
            source,
        )
        val exactMethods = listOf(
            exact652.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod),
            exact652.resolve(AppleMusicSymbols.CjkUnicodeBlockPredicateMethod),
        )
        exactMethods.forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found<*>)
            assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found<*>).match)
        }

        val old651 = IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source,
        )
        assertTrue(old651.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod) is TargetResolution.Missing)
        assertTrue(old651.resolve(AppleMusicSymbols.CjkUnicodeBlockPredicateMethod) is TargetResolution.Missing)

        val unknown = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)
        assertTrue(unknown.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod) is TargetResolution.Missing)
        assertTrue(unknown.resolve(AppleMusicSymbols.CjkUnicodeBlockPredicateMethod) is TargetResolution.Missing)
    }
}

private class FixtureTargetClassSource(
    private val classes: Map<String, Class<*>>,
) : TargetClassSource {
    override fun classNames(): List<String> = classes.keys.toList()

    override fun loadClass(name: String): Class<*>? = classes[name]
}

private class CjkAnimationFixture {
    class a

    @Suppress("UNUSED_PARAMETER")
    fun a0(holder: a, first: Int, second: Int, third: Int, background: Boolean) {
        // Signature-only fixture for the profile resolver.
    }
}

private class CjkHelperFixture {
    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun a(text: CharSequence, blocks: Set<*>): Boolean = true
    }
}
