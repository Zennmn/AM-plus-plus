package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleCollaborationArtistCacheTest {
    @Test fun repeatedFalseAndTrueResultsAreReusedWithoutMixingCredits() {
        var calls = 0
        val cache = AppleCollaborationArtistCache { calls++; it.contains(" & ") }
        repeat(1000) {
            assertFalse(cache.isCollaboration(" Solo "))
            assertTrue(cache.isCollaboration("Solo & Guest"))
        }
        assertEquals(2, calls)
    }

    @Test fun leastRecentlyUsedEntriesAreEvicted() {
        val calls = mutableListOf<String>()
        val cache = AppleCollaborationArtistCache(capacity = 2) { calls.add(it); false }
        listOf("a", "b", "a", "c", "a", "b").forEach(cache::isCollaboration)
        assertEquals(listOf("a", "b", "c", "b"), calls)
    }

    @Test fun failuresRetryAndLargeInputsAreNotRetained() {
        var calls = 0
        val cache = AppleCollaborationArtistCache {
            if (++calls == 1) error("transient")
            true
        }
        assertThrows(IllegalStateException::class.java) { cache.isCollaboration("a") }
        assertTrue(cache.isCollaboration("a"))
        repeat(2) { assertTrue(cache.isCollaboration("x".repeat(513))) }
        assertFalse(cache.isCollaboration("  "))
        assertEquals(4, calls)
    }

    @Test fun productionPolicyKeepsCollaborationAndSoloCases() {
        repeat(2) {
            listOf("Solo feat. Guest", "Solo & Guest", "Solo × Guest", "Solo X Guest", "甲、乙", "a/b")
                .forEach { assertTrue(it, AppleInternalCatalogResolver.isCollaborationArtistName(it)) }
            listOf("", "Solo", "EarthWindAndFire", "ACDC", "五月天")
                .forEach { assertFalse(it, AppleInternalCatalogResolver.isCollaborationArtistName(it)) }
        }
    }
}
