package dev.amenhancer.module.ui

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsListStateTest {

    private fun entry(appleMusicId: Long, displayName: String): CustomLyricsEntry =
        CustomLyricsEntry(
            appleMusicId = appleMusicId,
            displayName = displayName,
            fileId = "file-$appleMusicId",
            sizeBytes = 1L,
            sha256 = "sha-$appleMusicId",
            source = CustomLyricsSources.MANUAL,
        )

    private fun numbered(count: Int, offset: Long = 1L): List<CustomLyricsEntry> =
        (0 until count).map { index -> entry(offset + index, "Song ${offset + index}") }

    @Test
    fun `first update reveals only the default page size of a large dataset`() {
        val state = CustomLyricsListState()
        state.update(numbered(1000))
        assertEquals(1000, state.totalCount)
        assertEquals(50, state.visibleCount)
        assertEquals(50, state.visibleEntries.size)
        assertTrue(state.hasMore)
    }

    @Test
    fun `reveals everything when the dataset fits one page`() {
        val state = CustomLyricsListState()
        state.update(numbered(3))
        assertEquals(3, state.visibleCount)
        assertFalse(state.hasMore)
    }

    @Test
    fun `loads more in page sized increments up to the total`() {
        val state = CustomLyricsListState()
        state.update(numbered(120))
        assertEquals(50, state.visibleCount)
        state.loadMore()
        assertEquals(100, state.visibleCount)
        state.loadMore()
        assertEquals(120, state.visibleCount)
        assertFalse(state.hasMore)
        state.loadMore()
        assertEquals(120, state.visibleCount)
    }

    @Test
    fun `filters by display name case insensitively before pagination`() {
        val state = CustomLyricsListState()
        state.update(
            listOf(
                entry(1, "Alice in Wonderland"),
                entry(2, "alice cooper"),
                entry(3, "Bob Marley"),
            ),
        )
        state.setQuery("aLiCe")
        assertEquals(2, state.totalCount)
        assertEquals(listOf(1L, 2L), state.visibleEntries.map { it.appleMusicId })
        assertFalse(state.hasMore)
    }

    @Test
    fun `filters by apple music id substring`() {
        val state = CustomLyricsListState()
        state.update(listOf(entry(123456789, "First"), entry(987654321, "Second")))
        state.setQuery("2345")
        assertEquals(listOf(123456789L), state.visibleEntries.map { it.appleMusicId })
    }

    @Test
    fun `groups adjacent github ids into one compact row and searches every id`() {
        val first = entry(100L, "Song").copy(
            fileId = "github-100",
            sha256 = "same-sha",
            source = CustomLyricsSources.AM_LYRICS,
        )
        val second = first.copy(appleMusicId = 200L, fileId = "github-200")
        val state = CustomLyricsListState()

        state.update(listOf(first, second))

        assertEquals(1, state.totalCount)
        assertEquals(1, state.visibleCount)
        assertEquals(listOf(100L, 200L), state.visibleGroups.single().appleMusicIds)
        state.setQuery("200")
        assertEquals(1, state.totalCount)
        assertEquals(100L, state.visibleEntries.single().appleMusicId)
    }

    @Test
    fun `does not merge github rows separated by another row`() {
        val first = entry(100L, "Song").copy(
            fileId = "github-100",
            sha256 = "same-sha",
            source = CustomLyricsSources.AM_LYRICS,
        )
        val separator = entry(101L, "Other")
        val second = first.copy(appleMusicId = 200L, fileId = "github-200")
        val state = CustomLyricsListState()

        state.update(listOf(first, separator, second))

        assertEquals(3, state.totalCount)
        assertEquals(listOf(100L, 101L, 200L), state.visibleEntries.map { it.appleMusicId })
    }

    @Test
    fun `search empties and then recovers the window when cleared`() {
        val state = CustomLyricsListState()
        state.update(numbered(1000))
        state.loadMore()
        assertEquals(100, state.visibleCount)
        state.setQuery("no such song")
        assertEquals(0, state.totalCount)
        assertEquals(0, state.visibleCount)
        assertFalse(state.hasMore)
        state.setQuery("")
        assertEquals(1000, state.totalCount)
        assertEquals(50, state.visibleCount)
        assertTrue(state.hasMore)
    }

    @Test
    fun `refresh after deletion never shows removed entries`() {
        val state = CustomLyricsListState()
        val before = numbered(1000)
        state.update(before)
        state.loadMore()
        state.loadMore()
        assertEquals(150, state.visibleCount)

        val after = before.filter { it.appleMusicId % 10L != 0L }
        state.update(after)

        assertEquals(900, state.totalCount)
        assertEquals(150, state.visibleCount)
        assertEquals(after.take(150), state.visibleEntries)
        assertTrue(state.visibleEntries.none { it.appleMusicId % 10L == 0L })
    }

    @Test
    fun `refresh converges the window when the dataset shrinks`() {
        val state = CustomLyricsListState()
        state.update(numbered(60))
        state.loadMore()
        assertEquals(60, state.visibleCount)

        val restored = listOf(entry(1, "Restored One"), entry(2, "Restored Two"))
        state.update(restored)

        assertEquals(2, state.totalCount)
        assertEquals(restored, state.visibleEntries)
        assertFalse(state.hasMore)
    }

    @Test
    fun `keeps the active query across dataset refresh`() {
        val state = CustomLyricsListState()
        val all = listOf(entry(1, "Alice"), entry(2, "Bob"), entry(3, "Alice in Chains"))
        state.update(all, "aliCe")
        assertEquals(listOf(1L, 3L), state.visibleEntries.map { it.appleMusicId })

        state.update(all + entry(4, "Alice Keys"), "aliCe")
        assertEquals(3, state.totalCount)
        assertEquals(listOf(1L, 3L), state.visibleEntries.map { it.appleMusicId })
        assertTrue(state.hasMore)
        state.loadMore()
        assertEquals(listOf(1L, 3L, 4L), state.visibleEntries.map { it.appleMusicId })
    }

    @Test
    fun `empty dataset stays empty without more pages`() {
        val state = CustomLyricsListState()
        state.update(emptyList())
        assertEquals(0, state.totalCount)
        assertEquals(0, state.visibleCount)
        assertFalse(state.hasMore)
        state.loadMore()
        assertEquals(0, state.visibleCount)
    }
}
