package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the tablet-only typography seam without constraining blur internals. */
class FutureLyricBlurStructuralRegressionTest {
    private val portSource: String by lazy {
        sourceFile("OpenSourceLyricBlurPort.kt").readText()
    }
    private val featureSource: String by lazy {
        sourceFile("FutureLyricBlurFeature.kt").readText()
    }
    private val targetSource: String by lazy {
        sourceFile("AppleMusicBidirectionalLyricBlurTarget.kt").readText()
    }
    private val typographySource: String by lazy {
        sourceFile("TabletLyricTypography.kt").readText()
    }
    private val dualPaneSource: String by lazy {
        sourceFile("AppleMusicDualPaneTarget.kt").readText()
    }
    private val rendererSource: String by lazy {
        sourceFile("LyricBlurRenderer.kt").readText()
    }

    @Test
    fun `tablet lyric typography remains independent of blur`() {
        assertTrue(typographySource.contains("TabletLyricVisualPolicy.textSizeSp"))
        assertTrue(typographySource.contains("\"song_lyrics_line\""))
        assertTrue(typographySource.contains("\"song_lyrics_word\""))
        assertTrue(typographySource.contains("TabletModeQualifier.isEligible"))
        assertTrue(dualPaneSource.contains("TabletLyricTypography::applyToInflatedLayout"))
        assertTrue(
            dualPaneSource.contains("TabletLyricTypography::attach") ||
                dualPaneSource.contains("TabletLyricTypography.attach"),
        )
    }

    @Test
    fun `programmatic lyric scrolling refreshes recycled rows`() {
        assertTrue(portSource.contains("if (!isUserScrolling) {"))
        assertTrue(portSource.contains("scheduleBlurUpdate()\n            return"))
    }

    @Test
    fun `edge fading is continuous at the viewport instead of sliced per lyric row`() {
        assertFalse(portSource.contains("child.alpha = visual.alpha"))
        assertFalse(portSource.contains("child.alpha = 1f"))
        assertTrue(dualPaneSource.contains("configureVerticalGradientEdges"))
    }

    @Test
    fun `blur samples transparent pixels beyond each lyric row instead of mirroring a seam`() {
        assertTrue(rendererSource.contains("Shader.TileMode.DECAL"))
        assertFalse(rendererSource.contains("Shader.TileMode.MIRROR"))
    }

    @Test
    fun `highlighted rows clear immediately while nonzero blur still animates`() {
        val compactRenderer = rendererSource.replace(Regex("\\s+"), " ")

        assertTrue(compactRenderer.contains("if (target <= 0f) { clear(view) return@forEach }"))
        assertTrue(rendererSource.contains("Transition("))
        assertTrue(rendererSource.contains("scheduleFrame()"))
    }

    @Test
    fun `manual lyric scrolling restores blur after one second`() {
        assertTrue(portSource.contains("SCROLL_RESTORE_DELAY_MS = 1_000L"))
        assertTrue(portSource.contains("postDelayed(restoreBlurRunnable, SCROLL_RESTORE_DELAY_MS)"))
    }

    @Test
    fun `fragment view destruction releases only the matching lyric view session`() {
        assertTrue(targetSource.contains("findLifecycleDeclaringClass(fragmentClass, \"onDestroyView\")"))
        assertTrue(targetSource.contains("runtime.onLyricsViewDestroyed(owner)"))
        assertTrue(portSource.contains("if (owner !== lyricsFragmentOwner) return"))
        assertTrue(portSource.contains("scrollHandler.removeCallbacks(restoreBlurRunnable)"))
        assertTrue(portSource.contains("blurRenderer.clearAll()"))
        assertFalse(portSource.contains("addOnAttachStateChangeListener"))
    }

    @Test
    fun `recycler discovery cannot rebind a destroyed lyric root`() {
        assertTrue(portSource.contains("recyclerDiscoveryRunnable"))
        assertTrue(portSource.contains("if (root === lyricsRootView) findRecyclerView(root)"))
        assertTrue(portSource.contains("scrollHandler.removeCallbacks(discovery)"))
    }

    @Test
    fun `recycler discovery stops after a bounded number of attempts`() {
        assertTrue(portSource.contains("MAX_RECYCLER_DISCOVERY_ATTEMPTS = 10"))
        assertTrue(
            portSource.contains(
                "if (recyclerDiscoveryAttempts >= MAX_RECYCLER_DISCOVERY_ATTEMPTS)",
            ),
        )
        assertTrue(portSource.contains("recyclerDiscoveryAttempts += 1"))
        assertTrue(portSource.contains("recyclerDiscoveryAttempts = 0"))
        assertTrue(portSource.contains("RV discovery stopped after"))
    }

    @Test
    fun `feature and runtime stay behind the semantic target seam`() {
        assertTrue(featureSource.contains("context.target.bidirectionalLyricBlur.install()"))
        listOf("Class<", "Method", "Field", "TargetResolution", "context.symbols").forEach { forbidden ->
            assertFalse("feature leaked $forbidden", featureSource.contains(forbidden))
        }
        listOf("Class<", "java.lang.reflect", "ModernXposedRuntime", "TargetResolution").forEach { forbidden ->
            assertFalse("runtime leaked $forbidden", portSource.contains(forbidden))
        }
        assertFalse(portSource.contains("DexFile("))
        assertFalse(portSource.contains("sourceDir"))
        assertTrue(targetSource.contains("view.javaClass == recyclerViewClass"))
    }

    @Test
    fun `resolves both view model highlight entries through target symbols`() {
        assertTrue(targetSource.contains("AppleMusicSymbols.LyricsViewModelNotifyWordHighlight"))
        assertTrue(targetSource.contains("AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine"))
        val hookViewModel = targetSource.substringAfter("private fun hookViewModel(")
            .substringBefore("private companion object")
        assertFalse(hookViewModel.contains("declaredMethods"))
    }

    private fun sourceFile(name: String): File = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/$name"),
        File("app/src/main/java/dev/amenhancer/module/hook/$name"),
    ).firstOrNull(File::isFile) ?: error("$name was not found from the unit-test working directory")
}
