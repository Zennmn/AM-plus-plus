package dev.amenhancer.module.share

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class AppleSongMetadataLookupTest {
    private val cover = "https://is1-ssl.mzstatic.com/image/thumb/album/100x100bb.jpg"
    private val calls = mutableListOf<String>()
    private var disconnected = 0
    private fun client(body: (String) -> String): AppleSongMetadataLookup = AppleSongMetadataLookup { url ->
        calls += url.toString()
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() { disconnected++ }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(body(url.toString()).toByteArray())
        }
    }
    private fun song(id: Long = 123, title: String = "Exact track", artwork: String = cover) =
        """{"results":[{"kind":"song","trackId":$id,"trackName":"$title","artistName":"Artist","artworkUrl100":"$artwork"}]}"""

    @Test fun `empty China catalog falls back to the same track ID in the US catalog`() {
        val lookup = client { if ("country=cn" in it) """{"results":[]}""" else song() }
        val result = lookup.lookup("https://music.apple.com/cn/album/name/999?i=123")
        assertEquals(cover, result?.artworkUrl)
        assertEquals("Exact track", result?.title)
        assertEquals(2, calls.size)
        assertTrue(calls.all { "id=123&" in it })
        assertTrue("country=cn" in calls[0]); assertTrue("country=us" in calls[1])
        assertEquals(2, disconnected)
    }
    @Test fun `regional title is preserved when only the fallback supplies artwork`() {
        val lookup = client { if ("country=cn" in it) song(title = "Regional title", artwork = "") else song(title = "US title") }
        val result = lookup.lookup("https://music.apple.com/cn/song/123")
        assertEquals("Regional title", result?.title)
        assertEquals(cover, result?.artworkUrl)
    }
    @Test fun `fallback never borrows another track and US is not queried twice`() {
        val lookup = client { if ("country=cn" in it) """{"results":[]}""" else song(id = 999) }
        assertNull(lookup.lookup("https://music.apple.com/cn/song/123"))
        calls.clear()
        assertNull(lookup.lookup("https://music.apple.com/us/song/123"))
        assertEquals(1, calls.size)
    }
    @Test fun `complete regional metadata needs no fallback request`() {
        assertEquals(cover, client { song() }.lookup("https://music.apple.com/jp/song/123")?.artworkUrl)
        assertEquals(1, calls.size)
    }
}
