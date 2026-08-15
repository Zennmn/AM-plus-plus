package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hot-path contract tests for the object-level display correction seam.
 *
 * These tests deliberately use an in-memory accessor and resolver so a host
 * object can count every getter, lookup and setter without involving Android
 * or reflection.
 */
class DisplayCorrectionModuleTest {

    @Test
    fun `one bind snapshots all fields once and only writes changed nonblank values`() {
        val item = DisplayFixture(
            title = "English title",
            artist = "Artist",
            album = "English album",
        )
        val accessor = CountingDisplayAccessor()
        val resolver = CountingDisplayLookup(
            DisplayCorrectionCandidates(
                title = "中文标题",
                artist = "Artist", // Equal to the raw value: no setter.
                album = "  ", // Blank candidate: no setter.
            ),
        )

        val result = DisplayCorrectionModule(accessor, resolver).bind(item)

        assertEquals("中文标题", item.title)
        assertEquals("Artist", item.artist)
        assertEquals("English album", item.album)
        assertEquals(1, accessor.getterCount(DisplayCorrectionField.TITLE))
        assertEquals(1, accessor.getterCount(DisplayCorrectionField.ARTIST))
        assertEquals(1, accessor.getterCount(DisplayCorrectionField.ALBUM))
        assertEquals(1, resolver.lookupCount)
        assertEquals(1, accessor.setterCount(DisplayCorrectionField.TITLE))
        assertEquals(0, accessor.setterCount(DisplayCorrectionField.ARTIST))
        assertEquals(0, accessor.setterCount(DisplayCorrectionField.ALBUM))
        assertEquals(setOf(DisplayCorrectionField.TITLE), result.changedFields)
        assertFalse(result.failed)
    }

    @Test
    fun `getter failure is fail open and skips lookup and setters`() {
        val item = DisplayFixture("English title", "English artist", "English album")
        val accessor = CountingDisplayAccessor(failOnRead = DisplayCorrectionField.ARTIST)
        val resolver = CountingDisplayLookup(
            DisplayCorrectionCandidates("中文标题", "中文艺人", "中文专辑"),
        )

        val result = DisplayCorrectionModule(accessor, resolver).bind(item)

        assertEquals(DisplayFixture("English title", "English artist", "English album"), item)
        assertTrue(result.failed)
        assertEquals(0, resolver.lookupCount)
        assertEquals(0, accessor.totalSetterCount)
    }

    @Test
    fun `lookup failure is fail open after one bounded snapshot`() {
        val item = DisplayFixture("English title", "English artist", "English album")
        val accessor = CountingDisplayAccessor()
        val resolver = CountingDisplayLookup(
            result = DisplayCorrectionCandidates("中文标题", "中文艺人", "中文专辑"),
            fail = true,
        )

        val result = DisplayCorrectionModule(accessor, resolver).bind(item)

        assertEquals(DisplayFixture("English title", "English artist", "English album"), item)
        assertTrue(result.failed)
        assertEquals(1, resolver.lookupCount)
        assertEquals(0, accessor.totalSetterCount)
    }

    @Test
    fun `setter failure rolls back fields already applied`() {
        val item = DisplayFixture("English title", "English artist", "English album")
        val accessor = CountingDisplayAccessor(failOnWrite = DisplayCorrectionField.ARTIST)
        val resolver = CountingDisplayLookup(
            DisplayCorrectionCandidates("中文标题", "中文艺人", "中文专辑"),
        )

        val result = DisplayCorrectionModule(accessor, resolver).bind(item)

        assertEquals(DisplayFixture("English title", "English artist", "English album"), item)
        assertTrue(result.failed)
        // The title write is attempted once and then restored after artist fails.
        assertEquals(2, accessor.setterCount(DisplayCorrectionField.TITLE))
        assertEquals(2, accessor.setterCount(DisplayCorrectionField.ARTIST))
        assertEquals(0, accessor.setterCount(DisplayCorrectionField.ALBUM))
    }

    private data class DisplayFixture(
        var title: String,
        var artist: String,
        var album: String,
    )

    private class CountingDisplayAccessor(
        private val failOnRead: DisplayCorrectionField? = null,
        private val failOnWrite: DisplayCorrectionField? = null,
    ) : DisplayCorrectionAdapter<DisplayFixture> {
        private val getters = linkedMapOf<DisplayCorrectionField, Int>()
        private val setters = linkedMapOf<DisplayCorrectionField, Int>()

        override fun read(target: DisplayFixture, field: DisplayCorrectionField): String? {
            getters[field] = (getters[field] ?: 0) + 1
            if (field == failOnRead) error("getter failed for $field")
            return when (field) {
                DisplayCorrectionField.TITLE -> target.title
                DisplayCorrectionField.ARTIST -> target.artist
                DisplayCorrectionField.ALBUM -> target.album
            }
        }

        override fun write(target: DisplayFixture, field: DisplayCorrectionField, value: String) {
            setters[field] = (setters[field] ?: 0) + 1
            if (field == failOnWrite) error("setter failed for $field")
            when (field) {
                DisplayCorrectionField.TITLE -> target.title = value
                DisplayCorrectionField.ARTIST -> target.artist = value
                DisplayCorrectionField.ALBUM -> target.album = value
            }
        }

        fun getterCount(field: DisplayCorrectionField): Int = getters[field] ?: 0

        fun setterCount(field: DisplayCorrectionField): Int = setters[field] ?: 0

        val totalSetterCount: Int
            get() = setters.values.sum()
    }

    private class CountingDisplayLookup(
        private val result: DisplayCorrectionCandidates,
        private val fail: Boolean = false,
    ) : DisplayCorrectionLookup<DisplayFixture> {
        var lookupCount: Int = 0
            private set

        override fun lookup(
            target: DisplayFixture,
            original: DisplayCorrectionSnapshot,
        ): DisplayCorrectionCandidates {
            lookupCount += 1
            if (fail) error("lookup failed")
            return result
        }
    }
}
