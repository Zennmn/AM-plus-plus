package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleInternalCatalogResolverPriorityTest {
    @Test
    fun `fallback selector favors visible requests over FIFO order`() {
        assertEquals(
            1,
            AppleInternalCatalogResolver.selectNextRequestIndex(
                listOf(
                    AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
                    AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
                ),
            ),
        )
    }

    @Test
    fun `fallback selector keeps FIFO order for equal priorities`() {
        assertEquals(
            0,
            AppleInternalCatalogResolver.selectNextRequestIndex(
                listOf(
                    AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
                    AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
                ),
            ),
        )
    }
}
