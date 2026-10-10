package dev.amenhancer.module.hook

import org.junit.Assert.*
import org.junit.Test

class FragmentTabletLyricsAvailabilityTest {
    private data class Item(val lyrics: Boolean = false, val custom: Boolean = false, val offline: Boolean = false)

    @Test fun currentItemChangesImmediatelyReplacePreviousLyricAvailability() {
        var current: Item? = Item(lyrics = true)
        val seen = ArrayList<Item>()
        val availability = FragmentTabletLyricsAvailability({ current }, {
            (it as Item).also(seen::add).let { item -> item.lyrics || item.custom }
        }, { true }, { (it as Item).offline })
        assertEquals(true, availability.available())
        val noLyrics = Item()
        current = noLyrics
        assertEquals(false, availability.available())
        assertSame(noLyrics, seen.last())
        current = Item(custom = true)
        assertEquals(true, availability.available())
        current = null
        assertNull(availability.available())
    }

    @Test fun nativeNetworkPermissionAndOfflineAvailabilityBothRemainPartOfTheRule() {
        var online = false
        var current = Item(lyrics = true)
        val availability = FragmentTabletLyricsAvailability({ current }, {
            (it as Item).let { item -> item.lyrics || item.custom }
        }, { online }, { (it as Item).offline })
        assertEquals(false, availability.available())
        current = Item(lyrics = true, offline = true)
        assertEquals(true, availability.available())
        current = Item(offline = true)
        assertEquals(false, availability.available())
        online = true; current = Item(lyrics = true)
        assertEquals(true, availability.available())
    }
}
