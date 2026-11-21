package dev.amenhancer.module

import org.junit.Assert.*
import org.junit.Test

class MomentsSongMetadataTest {
    @Test fun `album links use the selected track id and never the album slug`() {
        assertEquals(456L, MomentsSongMetadata.songId("https://music.apple.com/cn/album/album-name/123?i=456"))
        assertEquals(456L, MomentsSongMetadata.songId("https://music.apple.com/us/song/song-name/456"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/album/album-name/123"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/playlist/name/123?i=456"))
        assertNull(MomentsSongMetadata.songId("https://evil.test/cn/song/456"))
        assertNull(MomentsSongMetadata.songId("https://music.apple.com/cn/album/name/123?i=456&i=789"))
    }
    @Test fun `current metadata must belong to the shared track`() {
        val url = "https://music.apple.com/cn/album/album-name/123?i=456"
        assertNull(MomentsSongMetadata.fromCurrent(url, CurrentSongDetails(789, "Wrong song", "Wrong artist")))
        assertEquals(MomentsSongMetadata("Despacito", "Luis Fonsi"), MomentsSongMetadata.fromCurrent(url,
            CurrentSongDetails(456, "Despacito", "Luis Fonsi")))
        assertNull(MomentsSongMetadata.title("Apple Music"))
        assertNull(MomentsSongMetadata.title("  "))
    }
    @Test fun `artwork permits only public Apple CDN HTTPS URLs`() {
        val url = "https://is1-ssl.mzstatic.com/image/thumb/a/100x100bb.jpg"
        assertEquals(url, MomentsSongMetadata.artwork(url))
        for (invalid in listOf("http://is1.mzstatic.com/a", "https://mzstatic.com.evil.com/a",
            "https://127.0.0.1/a", "https://user:pass@is1.mzstatic.com/a", "https://is1.mzstatic.com:8080/a")) {
            assertNull(MomentsSongMetadata.artwork(invalid))
        }
    }
}
