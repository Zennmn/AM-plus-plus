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
    private val typographySource: String by lazy {
        sourceFile("TabletLyricTypography.kt").readText()
    }
    private val dualPaneSource: String by lazy {
        sourceFile("DualPaneFeature.kt").readText()
    }

    @Test
    fun `tablet lyric typography remains independent of blur`() {
        assertTrue(typographySource.contains("TABLET_LANDSCAPE_LYRICS_TEXT_SIZE_SP = 35f"))
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
    fun `manual lyric scrolling restores blur after one second`() {
        assertTrue(portSource.contains("SCROLL_RESTORE_DELAY_MS = 1_000L"))
        assertTrue(portSource.contains("postDelayed(restoreBlurRunnable, SCROLL_RESTORE_DELAY_MS)"))
    }

    @Test
    fun `scroll restore keeps only the current lyric line clear`() {
        assertFalse(portSource.contains("previousHighlightIds"))
        assertFalse(portSource.contains("highlightedLineIds +"))
    }

    @Test
    fun `fragment view destruction releases only the matching lyric view session`() {
        assertTrue(portSource.contains("findLifecycleDeclaringClass(cls, \"onDestroyView\")"))
        assertTrue(portSource.contains("hookAllMethods(destroyDeclaringClass, \"onDestroyView\""))
        assertTrue(portSource.contains("takeIf(cls::isInstance)"))
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
    fun `lyric port consumes shared target symbols instead of rescanning the base apk`() {
        assertTrue(portSource.contains("fun install(targets: LyricBlurTargets)"))
        assertTrue(portSource.contains("targets.highlightCallback"))
        assertFalse(portSource.contains("DexFile("))
        assertFalse(portSource.contains("sourceDir"))
    }

    private fun sourceFile(name: String): File = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/$name"),
        File("app/src/main/java/dev/amenhancer/module/hook/$name"),
    ).firstOrNull(File::isFile) ?: error("$name was not found from the unit-test working directory")
}
