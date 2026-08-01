package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the right-only landscape resource overlay extracted from the modified
 * Apple Music 6.5.0 APK. The device test verifies geometry; this source test
 * makes the intended mutation boundary explicit without needing target APK
 * classes on the JVM classpath.
 */
class RightLyricsPaneStructuralRegressionTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("AppleMusicDualPaneTarget.kt was not found from the unit-test working directory")
    }
    private val compactSource: String by lazy { source.replace(Regex("\\s+"), " ") }

    @Test
    fun `mirrors the modified right lyrics sheet resource at its inflation boundary`() {
        assertTrue(source.contains("\"fragment_player_lyrics_sheet\""))
        assertTrue(source.contains("RightLyricsPaneLayout::apply"))
        assertTrue(source.contains("\"current_player_item\""))
        assertTrue(source.contains("\"recycler_view_gradients\""))
        assertTrue(source.contains("\"controls\""))
        assertTrue(source.contains("\"controls_tap_target\""))
        assertTrue(source.contains("rootParams.topMargin = 0"))
        assertTrue(source.contains("anchorTopToParent"))
        assertTrue(source.contains("configureVerticalGradientEdges"))
        assertTrue(source.contains("TOP_EDGE_FRACTION = 0.30f"))
        assertTrue(source.contains("TOP_CLEAR_FRACTION = 0.075f"))
        assertTrue(source.contains("TOP_CLEAR_WITHIN_FADE_FRACTION = 0.25f"))
        assertTrue(source.contains("BOTTOM_EDGE_FRACTION = 0.15f"))
        assertTrue(source.contains("topFadeColorsField.set(gradients, topFadeColors)"))
        assertTrue(source.contains("topFadePositionsField.set(gradients, topFadePositions)"))
        assertTrue(source.contains("setVerticalFadeSizes.invoke(gradients, topEdgeSize, bottomEdgeSize)"))
        assertTrue(source.contains("AlphaGradientEdgeFieldProfiles.resolve(gradients.javaClass)"))
        assertTrue(source.contains("setGradientEdge(gradients, fieldName, enabled = true)"))
        assertTrue(source.contains("setGradientEdge(gradients, fieldName, enabled = false)"))
        assertTrue(compactSource.contains("getDeclaredMethod( \"d\", Int::class.javaPrimitiveType"))
        assertFalse(source.contains("clearGradientEdges"))
    }

    @Test
    fun `limits the resource overlay to the official tablet landscape predicate`() {
        assertTrue(source.contains("TabletModeQualifier.isEligible(root.context)"))
        assertTrue(source.contains("right lyrics pane landscape resource installed"))
    }

    @Test
    fun `keeps vertical gradient masks through variant-aware field profiles`() {
        assertTrue(source.contains("AlphaGradientEdgeFieldProfiles.resolve(gradients.javaClass)"))
        assertTrue(source.contains("profile.vertical.forEach"))
        assertTrue(source.contains("profile.horizontal.forEach"))
        assertTrue(source.contains("field.setBoolean(gradients, enabled)"))
        assertFalse(source.contains("disableGradientEdges(gradients)"))
        assertTrue(source.contains("setVerticalFadeSizes.invoke(gradients, topEdgeSize, bottomEdgeSize)"))
    }

    @Test
    fun `reapplies the tablet highlight anchor after delayed sheet expansion`() {
        assertTrue(source.contains("installHighlightAnchorResizeSync(fragment)"))
        assertTrue(source.contains("container.addOnLayoutChangeListener"))
        assertTrue(source.contains("bottom - top == oldBottom - oldTop"))
        assertTrue(source.contains("refreshHighlightAnchor(container, fragmentReference)"))
        assertTrue(compactSource.contains(
            "installHighlightAnchorResizeSync(fragment) TabletLyricTypography.attach(fragment)",
        ))
        assertTrue(source.contains("ModernXposedRuntime.callMethod(currentFragment, \"j2\")"))
        assertTrue(source.contains("WeakReference(fragment)"))
        assertTrue(source.contains("LyricsLayoutFieldProfiles.resolve(fragment.javaClass)"))
        assertTrue(source.contains("profile.synchronizedMetrics.first()"))
        assertTrue(source.contains("RightLyricsPaneLayout.reapplyVerticalGradientEdges(gradients)"))
    }
}
