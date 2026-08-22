package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LunabeatClientTest {
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"
    private val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        .digest(ttml.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class FakeCache(
        var snapshot: LunabeatCatalogCacheSnapshot? = null,
    ) : LunabeatCatalogCache {
        var writes = 0

        override fun read(): LunabeatCatalogCacheSnapshot? = snapshot

        override fun write(manifestJson: String, indexJson: String, etag: String?): Boolean {
            writes += 1
            snapshot = LunabeatCatalogCacheSnapshot(manifestJson, indexJson, etag)
            return true
        }
    }

    private class FakeTransport(
        private val responses: MutableList<String?>,
        private val responseOverrides: MutableList<LyricHttpResponse>? = null,
    ) : LyricHttpTransport {
        val urls = mutableListOf<String>()

        override fun get(url: String): String? {
            urls += url
            return responses.removeFirstOrNull()
        }

        override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? {
            urls += url
            responseOverrides?.removeFirstOrNull()?.let { return it }
            return responses.removeFirstOrNull()?.toByteArray(Charsets.UTF_8)?.let {
                LyricHttpResponse(200, it)
            }
        }

    }

    private fun manifest(revision: String) =
        """{"schemaVersion":2,"revision":"$revision","index":"songs.json","indexSha256":"0000000000000000000000000000000000000000000000000000000000000000"}"""

    private fun index(revision: String, path: String = "lyrics/42/42.ttml") =
        """{"schemaVersion":2,"revision":"$revision","songs":[{"title":"Song","artists":["Artist"],"album":"Album","sourceIds":{"appleMusicId":["42"]},"path":"$path","sha256":"$sha256"}]}"""

    @Test
    fun `fetch uses exact apple id and accepts mismatched catalog index hash`() {
        val cache = FakeCache()
        val transport = FakeTransport(
            mutableListOf(manifest("r1"), index("r1"), ttml),
        )
        val client = LunabeatClient(transport, cache = cache)

        assertEquals(ttml, client.fetch(42L))
        assertEquals(1, cache.writes)
        assertEquals(
            listOf(
                LunabeatClient.MANIFEST_URL,
                "${LunabeatClient.API_BASE}/songs.json",
                "${LunabeatClient.LYRICS_BASE}/lyrics/42/42.ttml",
            ),
            transport.urls,
        )
    }

    @Test
    fun `unchanged revision reuses cached songs index`() {
        val cache = FakeCache(
            LunabeatCatalogCacheSnapshot(manifest("r1"), index("r1")),
        )
        val transport = FakeTransport(mutableListOf(manifest("r1"), ttml))
        val client = LunabeatClient(transport, cache = cache)

        assertEquals(ttml, client.fetch(42L))
        assertEquals(
            listOf(
                LunabeatClient.MANIFEST_URL,
                "${LunabeatClient.LYRICS_BASE}/lyrics/42/42.ttml",
            ),
            transport.urls,
        )
        assertEquals(0, cache.writes)
    }

    @Test
    fun `changed revision downloads and replaces cached songs index`() {
        val cache = FakeCache(
            LunabeatCatalogCacheSnapshot(manifest("r1"), index("r1")),
        )
        val transport = FakeTransport(mutableListOf(manifest("r2"), index("r2"), ttml))
        val client = LunabeatClient(transport, cache = cache)

        assertEquals(ttml, client.fetch(42L))
        assertEquals(1, cache.writes)
        assertTrue(cache.snapshot!!.manifestJson.contains("r2"))
    }

    @Test
    fun `not modified manifest keeps the cached catalog`() {
        val cache = FakeCache(
            LunabeatCatalogCacheSnapshot(manifest("r1"), index("r1"), etag = "\"v1\""),
        )
        val indexTransport = FakeTransport(
            responses = mutableListOf(),
            responseOverrides = mutableListOf(LyricHttpResponse(304, null, "\"v1\"")),
        )
        val lyricsTransport = FakeTransport(mutableListOf(ttml))
        val client = LunabeatClient(indexTransport, lyricsTransport, cache)

        assertEquals(ttml, client.fetch(42L))
        assertEquals(listOf(LunabeatClient.MANIFEST_URL), indexTransport.urls)
        assertEquals(listOf("${LunabeatClient.LYRICS_BASE}/lyrics/42/42.ttml"), lyricsTransport.urls)
        assertEquals(0, cache.writes)
    }

    @Test
    fun `malformed or duplicate catalog entries fail open to the old cache`() {
        val cache = FakeCache(
            LunabeatCatalogCacheSnapshot(manifest("r1"), index("r1")),
        )
        val duplicate =
            """{"schemaVersion":2,"revision":"r2","songs":[{"title":"A","artists":[],"sourceIds":{"appleMusicId":["42"]},"path":"lyrics/a.ttml","sha256":"$sha256"},{"title":"B","artists":[],"sourceIds":{"appleMusicId":["42"]},"path":"lyrics/b.ttml","sha256":"$sha256"}]}"""
        val transport = FakeTransport(mutableListOf(manifest("r2"), duplicate, ttml))
        val client = LunabeatClient(transport, cache = cache)

        assertEquals(ttml, client.fetch(42L))
        assertEquals(0, cache.writes)
        assertFalse(transport.urls.contains("${LunabeatClient.LYRICS_BASE}/lyrics/a.ttml"))
    }

    @Test
    fun `catalog keeps exact id songs when unrelated entries omit apple music ids`() {
        val catalogIndex =
            """{"schemaVersion":2,"revision":"r1","songs":[
                {"title":"No Apple ID","artists":["Artist"],"path":"lyrics/no-id.ttml","sha256":"$sha256"},
                {"title":"Target","artists":["Artist"],"sourceIds":{"appleMusicId":["42"]},"path":"lyrics/42.ttml","sha256":"$sha256"}
            ]}"""

        val catalog = LunabeatClient.parseCatalog(manifest("r1"), catalogIndex)

        assertNotNull(catalog)
        assertEquals("Target", catalog?.entryFor(42L)?.title)
    }

    @Test
    fun `invalid id and unsafe path fail without network lyric fetch`() {
        val cache = FakeCache(
            LunabeatCatalogCacheSnapshot(manifest("r1"), index("r1", "lyrics/../outside.ttml")),
        )
        val transport = FakeTransport(mutableListOf(manifest("r1"), ttml))
        val client = LunabeatClient(transport, cache = cache)

        assertNull(client.fetch(0L))
        assertNull(client.fetch(42L))
        assertNotNull(transport.urls.firstOrNull())
        assertFalse(transport.urls.any { it.contains("outside.ttml") })
    }
}
