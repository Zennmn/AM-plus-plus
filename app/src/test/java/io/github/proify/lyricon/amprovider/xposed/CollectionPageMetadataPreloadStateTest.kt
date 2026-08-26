package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPageMetadataPreloadStateTest {
    @Test
    fun onePostedPageDispatchDrainsEveryModelBuiltBeforeTheMainLoopRuns() {
        val state = CollectionPageMetadataPreloadState()

        assertTrue(state.enqueue(listOf("101", "102", "103")))
        assertFalse(state.enqueue(listOf("102", "104")))

        assertEquals(listOf("101", "102", "103", "104"), state.drain())
    }

    @Test
    fun submittedRowsAreNotRequeuedDuringAControllerRebuild() {
        val state = CollectionPageMetadataPreloadState()
        state.enqueue(listOf("101", "102"))
        state.drain()

        assertFalse(state.enqueue(listOf("101", "102")))
        assertTrue(state.enqueue(listOf("103")))
        assertEquals(listOf("103"), state.drain())
    }
}
