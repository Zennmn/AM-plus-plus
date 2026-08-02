package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestCodec
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CustomLyricsBackupCodecTest {
    private val ttml1 = "<tt><body><p><span>hello</span></p></body></tt>"
    private val ttml2 = "<tt><body><p><span>world</span><span>!</span></p></body></tt>"

    @Test
    fun `roundtrip preserves manifest and every ttml file`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
                entry(appleMusicId = 2L, fileId = "lyrics_b", ttml = ttml2),
            ),
        )
        val files = mapOf(
            "lyrics_a" to ttml1.toByteArray(Charsets.UTF_8),
            "lyrics_b" to ttml2.toByteArray(Charsets.UTF_8),
        )
        val out = ByteArrayOutputStream()
        val encoded = CustomLyricsBackupCodec.encode(manifest, { files[it] }, out)
        assertTrue(encoded is CustomLyricsBackupEncodeResult.Encoded)
        assertEquals(2, (encoded as CustomLyricsBackupEncodeResult.Encoded).entryCount)

        val decoded = CustomLyricsBackupCodec.decode(ByteArrayInputStream(out.toByteArray()))
        assertTrue(decoded is CustomLyricsBackupDecodeResult.Decoded)
        val backup = (decoded as CustomLyricsBackupDecodeResult.Decoded).backup
        assertEquals(manifest, backup.manifest)
        assertEquals(ttml1, backup.files.getValue("lyrics_a").toString(Charsets.UTF_8))
        assertEquals(ttml2, backup.files.getValue("lyrics_b").toString(Charsets.UTF_8))
    }

    @Test
    fun `backup fails entirely when any remote file cannot be read`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
                entry(appleMusicId = 2L, fileId = "lyrics_b", ttml = ttml2),
            ),
        )
        val out = ByteArrayOutputStream()

        val result = CustomLyricsBackupCodec.encode(manifest, { null }, out)

        assertTrue(result is CustomLyricsBackupEncodeResult.Failed)
        assertEquals(0, out.size())
    }

    @Test
    fun `encode rejects a manifest that policy would alter`() {
        val manifest = CustomLyricsManifest((1L..33L).map { entry(it, "lyrics_$it", ttml1) })

        val result = CustomLyricsBackupCodec.encode(manifest, { ttml1.toByteArray() }, ByteArrayOutputStream())

        assertTrue(result is CustomLyricsBackupEncodeResult.Failed)
    }

    @Test
    fun `decode rejects a zip without manifest json`() {
        val result = decode(zipBytes(listOf("lyrics_a" to ttml1.toByteArray(Charsets.UTF_8))))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects a corrupt manifest json instead of treating it as empty backup`() {
        val result = decode(zipBytes(listOf("manifest.json" to "not json".toByteArray())))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects an unsupported version`() {
        val json = JSONObject(CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()))
            .put("version", 99)
            .toString()
        val result = decode(zipBytes(listOf("manifest.json" to json.toByteArray())))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects a manifest with more than 32 entries`() {
        val entries = JSONArray()
        repeat(33) { index ->
            entries.put(
                JSONObject().apply {
                    put("appleMusicId", index + 1L)
                    put("displayName", "s")
                    put("fileId", "lyrics_$index")
                    put("sizeBytes", 5L)
                    put("sha256", "0".repeat(64))
                    put("source", CustomLyricsSources.MANUAL)
                    put("enabled", true)
                },
            )
        }
        val json = JSONObject().apply { put("version", 1); put("entries", entries) }.toString()
        val result = decode(zipBytes(listOf("manifest.json" to json.toByteArray())))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects duplicate apple music ids in the manifest`() {
        val entries = JSONArray()
        listOf(
            entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
            entry(appleMusicId = 1L, fileId = "lyrics_b", ttml = ttml2),
        ).forEach { e ->
            entries.put(
                JSONObject().apply {
                    put("appleMusicId", e.appleMusicId)
                    put("displayName", e.displayName)
                    put("fileId", e.fileId)
                    put("sizeBytes", e.sizeBytes)
                    put("sha256", e.sha256)
                    put("source", e.source)
                    put("enabled", e.enabled)
                },
            )
        }
        val json = JSONObject().apply { put("version", 1); put("entries", entries) }.toString()
        val result = decode(zipBytes(listOf("manifest.json" to json.toByteArray())))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects directory entries`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("lyrics_dir/"))
            zip.closeEntry()
        }

        val result = decode(out.toByteArray())

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects path traversal file names`() {
        listOf(
            "../evil.txt",
            "sub/dir/file",
            "lyrics_ok/../../evil",
        ).forEach { name ->
            val result = decode(zipBytes(listOf(name to ttml1.toByteArray(Charsets.UTF_8))))
            assertTrue("name $name should be rejected", result is CustomLyricsBackupDecodeResult.Rejected)
        }
    }

    @Test
    fun `decode rejects duplicate file names`() {
        val bytes = ttml1.toByteArray(Charsets.UTF_8)

        val result = decode(zipWithDuplicateName("lyrics_a", bytes, bytes))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects duplicate manifest json`() {
        val json = CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()).toByteArray()

        val result = decode(zipWithDuplicateName("manifest.json", json, json))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects a file missing from the zip`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
                entry(appleMusicId = 2L, fileId = "lyrics_b", ttml = ttml2),
            ),
        )
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(manifest),
                    "lyrics_a" to ttml1.toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects a file extra to the manifest`() {
        val manifest = CustomLyricsManifest(listOf(entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)))
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(manifest),
                    "lyrics_a" to ttml1.toByteArray(Charsets.UTF_8),
                    "lyrics_c" to ttml2.toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects shared manifest file ids even when zip file count matches`() {
        val sharedA = entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)
        val sharedB = entry(appleMusicId = 2L, fileId = "lyrics_a", ttml = ttml1)
        val manifest = CustomLyricsManifest(listOf(sharedA, sharedB))

        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(manifest),
                    "lyrics_a" to ttml1.toByteArray(Charsets.UTF_8),
                    "lyrics_extra" to ttml2.toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects an entry whose size does not match the manifest`() {
        val bytes = ttml1.toByteArray(Charsets.UTF_8)
        val mismatched = entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)
            .copy(sizeBytes = bytes.size.toLong() + 1)
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(CustomLyricsManifest(listOf(mismatched))),
                    "lyrics_a" to bytes,
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects an entry whose hash does not match the manifest`() {
        val bytes = ttml1.toByteArray(Charsets.UTF_8)
        val mismatched = entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)
            .copy(sha256 = "0".repeat(64))
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(CustomLyricsManifest(listOf(mismatched))),
                    "lyrics_a" to bytes,
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects an entry that fails the ttml policy`() {
        val bytes = "not ttml".toByteArray(Charsets.UTF_8)
        val rejected = CustomLyricsEntry(
            appleMusicId = 1L,
            displayName = "Song",
            fileId = "lyrics_a",
            sizeBytes = bytes.size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256(bytes),
            source = CustomLyricsSources.MANUAL,
        )
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(CustomLyricsManifest(listOf(rejected))),
                    "lyrics_a" to bytes,
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects an oversize entry (zip bomb per entry bound)`() {
        val result = decode(zipBytes(listOf("lyrics_big" to ByteArray(512 * 1024 + 1))))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects too many zip entries`() {
        val bytes = ttml1.toByteArray(Charsets.UTF_8)
        val result = decode(zipBytes((1..34).map { "lyrics_$it" to bytes }))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    private fun decode(zip: ByteArray): CustomLyricsBackupDecodeResult =
        CustomLyricsBackupCodec.decode(ByteArrayInputStream(zip))

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Builds a zip with several entries sharing [name]. ZipOutputStream
     * refuses duplicate names, so the ZIP is assembled by hand (little-endian
     * layout, STORED method, real CRC32s).
     */
    private fun zipWithDuplicateName(name: String, vararg datas: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var offset = 0
        val centralHeaders = ArrayList<ByteArray>()
        datas.forEach { data ->
            val crc = CRC32().apply { update(data) }.value.toInt()
            val local = ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            local.putInt(0x04034b50)
            local.putShort(20.toShort()); local.putShort(0); local.putShort(0); local.putShort(0); local.putShort(0)
            local.putInt(crc); local.putInt(data.size); local.putInt(data.size)
            local.putShort(nameBytes.size.toShort()); local.putShort(0)
            local.put(nameBytes)
            out.write(local.array())
            out.write(data)
            val central = ByteBuffer.allocate(46 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            central.putInt(0x02014b50)
            central.putShort(20.toShort()); central.putShort(20.toShort()); central.putShort(0); central.putShort(0)
            central.putShort(0); central.putShort(0)
            central.putInt(crc); central.putInt(data.size); central.putInt(data.size)
            central.putShort(nameBytes.size.toShort()); central.putShort(0); central.putShort(0)
            central.putShort(0); central.putShort(0); central.putInt(0)
            central.putInt(offset)
            central.put(nameBytes)
            centralHeaders += central.array()
            offset += local.array().size + data.size
        }
        val centralStart = out.size()
        centralHeaders.forEach { out.write(it) }
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054b50)
        eocd.putShort(0); eocd.putShort(0); eocd.putShort(datas.size.toShort()); eocd.putShort(datas.size.toShort())
        eocd.putInt(centralHeaders.sumOf { it.size })
        eocd.putInt(centralStart)
        eocd.putShort(0)
        out.write(eocd.array())
        return out.toByteArray()
    }

    private fun manifestJson(manifest: CustomLyricsManifest): ByteArray =
        CustomLyricsManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)

    private fun entry(appleMusicId: Long, fileId: String, ttml: String): CustomLyricsEntry {
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        return CustomLyricsEntry(
            appleMusicId = appleMusicId,
            displayName = "Song $appleMusicId",
            fileId = fileId,
            sizeBytes = bytes.size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256(bytes),
            source = CustomLyricsSources.MANUAL,
        )
    }
}
