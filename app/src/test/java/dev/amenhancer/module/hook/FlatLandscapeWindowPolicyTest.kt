package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatLandscapeWindowPolicyTest {
    @Test
    fun `ordinary sixteen to ten tablet stays outside boundary sync`() {
        assertFalse(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 1600,
                windowHeightDp = 1000,
                physicalWidthPx = 1600,
                physicalHeightPx = 1000,
            ),
        )
    }

    @Test
    fun `ordinary four to three tablet stays outside boundary sync`() {
        assertFalse(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 1600,
                windowHeightDp = 1200,
                physicalWidthPx = 1600,
                physicalHeightPx = 1200,
            ),
        )
    }

    @Test
    fun `physical wide display keeps boundary sync when system rail narrows window`() {
        assertTrue(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 1630,
                windowHeightDp = 1000,
                physicalWidthPx = 1700,
                physicalHeightPx = 1000,
            ),
        )
    }

    @Test
    fun `wide effective window keeps boundary sync without physical metrics`() {
        assertTrue(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 1800,
                windowHeightDp = 1000,
                physicalWidthPx = 0,
                physicalHeightPx = 0,
            ),
        )
    }

    @Test
    fun `rotated physical dimensions use the same aspect ratio`() {
        assertTrue(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 1000,
                windowHeightDp = 600,
                physicalWidthPx = 1000,
                physicalHeightPx = 1700,
            ),
        )
    }

    @Test
    fun `unavailable physical metrics do not enable ordinary tablet sync`() {
        assertFalse(
            FlatLandscapeWindowPolicy.shouldInstallBoundarySync(
                windowWidthDp = 0,
                windowHeightDp = 0,
                physicalWidthPx = 0,
                physicalHeightPx = 0,
            ),
        )
    }
}
