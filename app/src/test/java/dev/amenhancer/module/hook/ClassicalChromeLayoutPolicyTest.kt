package dev.amenhancer.module.hook

import dev.amenhancer.glass.GlassBarCollapsePolicy
import dev.amenhancer.glass.GlassPolicy
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicalChromeLayoutPolicyTest {
    private val density = 3f
    private val width = 1080
    private val height = 2400
    private val bottomInset = 48
    private val bottomGapPx = 12

    @Test
    fun expandedAndCollapsedMiniFrameMarginsRespectLtrAndRtl() {
        val hMargin = (GlassPolicy.HORIZONTAL_DP * density).roundToInt().toFloat()
        val navHeight = (GlassPolicy.NAV_HEIGHT_DP * density).roundToInt().toFloat()
        val miniHeight = (GlassPolicy.MINI_HEIGHT_DP * density).roundToInt().toFloat()
        val gap = (GlassBarCollapsePolicy.GAP_DP * density).roundToInt().toFloat()
        val capsule = (GlassBarCollapsePolicy.CAPSULE_DP * density).roundToInt().toFloat()
        val search = (GlassBarCollapsePolicy.SEARCH_DP * density).roundToInt().toFloat()
        val navBottom = height - bottomInset - bottomGapPx
        val expandedTop = (navBottom - navHeight) - gap - miniHeight

        val expandedLtr = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = density,
            bottomInset = bottomInset,
            bottomGapPx = bottomGapPx,
            rtl = false,
            hasDetachedSearch = true,
            miniVisible = true,
            collapseProgress = 0f,
            hasCollapsePresentation = true,
            miniReboundScale = 1f,
            miniSinkBounceDp = 4,
            playerExpansion = 0f,
        )
        assertEquals(hMargin, expandedLtr.left, 0.5f)
        assertEquals(width - 2f * hMargin, expandedLtr.width, 0.5f)
        assertEquals(expandedTop, expandedLtr.top, 0.5f)
        assertEquals(miniHeight, expandedLtr.height, 0.5f)

        val collapsedLtr = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = density,
            bottomInset = bottomInset,
            bottomGapPx = bottomGapPx,
            rtl = false,
            hasDetachedSearch = true,
            miniVisible = true,
            collapseProgress = 1f,
            hasCollapsePresentation = true,
            miniReboundScale = 1f,
            miniSinkBounceDp = 4,
            playerExpansion = 0f,
        )
        val expectedCollapsedLeft = hMargin + capsule + gap
        val expectedCollapsedRight = width - hMargin - (search + gap)
        assertEquals(expectedCollapsedLeft, collapsedLtr.left, 0.5f)
        assertEquals(expectedCollapsedRight - expectedCollapsedLeft, collapsedLtr.width, 0.5f)
        assertEquals(navBottom - miniHeight, collapsedLtr.top, 0.5f)

        val collapsedRtl = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = density,
            bottomInset = bottomInset,
            bottomGapPx = bottomGapPx,
            rtl = true,
            hasDetachedSearch = true,
            miniVisible = true,
            collapseProgress = 1f,
            hasCollapsePresentation = true,
            miniReboundScale = 1f,
            miniSinkBounceDp = 4,
            playerExpansion = 0f,
        )
        assertEquals(hMargin + search + gap, collapsedRtl.left, 0.5f)
        assertEquals(collapsedLtr.width, collapsedRtl.width, 0.5f)
    }

    @Test
    fun morphProgressionInterpolatesFromMiniToFullScreenAndFadesCleanly() {
        fun frameAt(
            expansion: Float,
            miniVisible: Boolean = true,
            sheetTopPx: Float = Float.NaN,
            sheetBottomPx: Float = Float.NaN,
        ) = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = density,
            bottomInset = bottomInset,
            bottomGapPx = bottomGapPx,
            rtl = false,
            hasDetachedSearch = true,
            miniVisible = miniVisible,
            collapseProgress = 1f,
            hasCollapsePresentation = true,
            miniReboundScale = 1f,
            miniSinkBounceDp = 0,
            playerExpansion = expansion,
            sheetTopPx = sheetTopPx,
            sheetBottomPx = sheetBottomPx,
        )

        val rest = frameAt(0f)
        assertEquals(0f, rest.materialProgress, 0.001f)
        assertEquals(1f, rest.contentAlpha, 0.001f)
        assertEquals(1f, rest.alpha, 0.001f)
        assertTrue(rest.left > 0f && rest.top > 0f)

        val contentHidden = frameAt(0.22f)
        assertEquals(0f, contentHidden.contentAlpha, 0.001f)
        assertEquals(1f, contentHidden.alpha, 0.001f)
        assertTrue(contentHidden.materialProgress in 0.1f..0.95f)

        val fullMaterial = frameAt(0.35f)
        assertEquals(1f, fullMaterial.materialProgress, 0.001f)
        assertEquals(1f, fullMaterial.alpha, 0.001f)
        assertEquals(0f, fullMaterial.left, 0.5f)
        assertEquals(rest.top * (1f - 0.35f), fullMaterial.top, 0.5f)
        val expectedBottom35 = rest.restTop + rest.restHeight + (height - (rest.restTop + rest.restHeight)) * 0.35f
        assertEquals(expectedBottom35, fullMaterial.top + fullMaterial.height, 0.5f)
        assertEquals(width.toFloat(), fullMaterial.width, 0.5f)

        val faded = frameAt(0.60f)
        assertEquals(0f, faded.alpha, 0.001f)
        assertEquals(rest.top * (1f - 0.60f), faded.top, 0.5f)
        val expectedBottom60 = rest.restTop + rest.restHeight + (height - (rest.restTop + rest.restHeight)) * 0.60f
        assertEquals(expectedBottom60, faded.top + faded.height, 0.5f)

        val complete = frameAt(1f)
        assertEquals(0f, complete.alpha, 0.001f)
        assertEquals(0f, complete.top, 0.5f)
        assertEquals(height.toFloat(), complete.height, 0.5f)

        val synced35 = frameAt(0.35f, sheetTopPx = 1020f, sheetBottomPx = 2360f)
        assertEquals(1020f, synced35.top, 0.01f)
        assertEquals(1340f, synced35.height, 0.01f)
        assertEquals(2360f, synced35.top + synced35.height, 0.01f)

        val synced50 = frameAt(0.50f, sheetTopPx = 740f, sheetBottomPx = 2380f)
        assertEquals(740f, synced50.top, 0.01f)
        assertEquals(2380f, synced50.top + synced50.height, 0.01f)

        val hiddenAtRest = frameAt(0f, miniVisible = false)
        assertEquals(0f, hiddenAtRest.alpha, 0.001f)
    }

    @Test
    fun navHitPassesCenterMiniPillThroughWhileCollapsedAndFlipsInRtl() {
        val rowWidth = 984f
        val rowHeight = GlassPolicy.NAV_HEIGHT_DP * density

        assertTrue(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = rowWidth * 0.5f,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = false,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 0f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )

        assertTrue(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = 24f * density,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = false,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 1f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )
        assertFalse(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = rowWidth * 0.5f,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = false,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 1f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )
        assertTrue(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = rowWidth - 12f * density,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = false,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 1f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )

        assertTrue(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = 12f * density,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = true,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 1f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )
        assertTrue(
            ClassicalChromeLayoutPolicy.isNavHit(
                localX = rowWidth - 24f * density,
                localY = rowHeight * 0.5f,
                rowWidth = rowWidth,
                rowHeight = rowHeight,
                density = density,
                rtl = true,
                tabsCount = 3,
                detachedSearch = true,
                collapseProgress = 1f,
                collapseReboundScale = 1f,
                expandReboundScale = 1f,
            ),
        )
    }

    @Test
    fun flyingArtworkHandoffsAtRestTracksLiveTargetAndFadesCleanly() {
        fun frameAt(expansion: Float, rtl: Boolean = false) = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = density,
            bottomInset = bottomInset,
            bottomGapPx = bottomGapPx,
            rtl = rtl,
            hasDetachedSearch = true,
            miniVisible = true,
            collapseProgress = 1f,
            hasCollapsePresentation = true,
            miniReboundScale = 1f,
            miniSinkBounceDp = 0,
            playerExpansion = expansion,
        )

        val atRest = ClassicalChromeLayoutPolicy.computeArtworkFrame(
            frame = frameAt(0f),
            containerWidth = width,
            containerHeight = height,
            density = density,
            rtl = false,
            collapseProgress = 1f,
            playerExpansion = 0f,
            miniVisible = true,
            artworkLeftPx = 180f,
            artworkTopPx = 420f,
            artworkSizePx = 720f,
        )
        assertEquals(0f, atRest.alpha, 0f)

        val fStart = frameAt(0.002f)
        val artStart = ClassicalChromeLayoutPolicy.computeArtworkFrame(
            frame = fStart,
            containerWidth = width,
            containerHeight = height,
            density = density,
            rtl = false,
            collapseProgress = 1f,
            playerExpansion = 0.002f,
            miniVisible = true,
            artworkLeftPx = 180f,
            artworkTopPx = 420f,
            artworkSizePx = 720f,
        )
        assertEquals(fStart.restLeft + 10f * density, artStart.left, 1f)
        assertEquals(fStart.restTop + (fStart.restHeight - 30f * density) * 0.5f, artStart.top, 1f)
        assertEquals(30f * density, artStart.size, 1f)
        assertEquals(6f, artStart.cornerDp, 0.1f)
        assertEquals(1f, artStart.alpha, 0.001f)

        val rtlStart = frameAt(0.002f, rtl = true)
        val rtlArtStart = ClassicalChromeLayoutPolicy.computeArtworkFrame(
            frame = rtlStart,
            containerWidth = width,
            containerHeight = height,
            density = density,
            rtl = true,
            collapseProgress = 1f,
            playerExpansion = 0.002f,
            miniVisible = true,
            artworkLeftPx = 180f,
            artworkTopPx = 420f,
            artworkSizePx = 720f,
        )
        assertEquals(rtlStart.restLeft + rtlStart.restWidth - 10f * density - 30f * density, rtlArtStart.left, 1f)

        val lockedMid = ClassicalChromeLayoutPolicy.computeArtworkFrame(
            frame = frameAt(0.50f),
            containerWidth = width,
            containerHeight = height,
            density = density,
            rtl = false,
            collapseProgress = 1f,
            playerExpansion = 0.50f,
            miniVisible = true,
            artworkLeftPx = 180f,
            artworkTopPx = 420f,
            artworkSizePx = 720f,
        )
        assertEquals(180f, lockedMid.left, 0.01f)
        assertEquals(420f, lockedMid.top, 0.01f)
        assertEquals(720f, lockedMid.size, 0.01f)
        assertEquals(10f, lockedMid.cornerDp, 0.01f)
        assertEquals(1f, lockedMid.alpha, 0.001f)

        val faded = ClassicalChromeLayoutPolicy.computeArtworkFrame(
            frame = frameAt(0.78f),
            containerWidth = width,
            containerHeight = height,
            density = density,
            rtl = false,
            collapseProgress = 1f,
            playerExpansion = 0.78f,
            miniVisible = true,
            artworkLeftPx = 180f,
            artworkTopPx = 420f,
            artworkSizePx = 720f,
        )
        assertEquals(0f, faded.alpha, 0.001f)
    }
}
