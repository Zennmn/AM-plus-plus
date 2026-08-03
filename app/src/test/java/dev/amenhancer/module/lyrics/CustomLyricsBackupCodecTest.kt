package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestCodec
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
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

        val delivered = linkedMapOf<String, ByteArray>()
        val decoded = CustomLyricsBackupCodec.decode(ByteArrayInputStream(out.toByteArray())) { fileId, bytes ->
            delivered[fileId] = bytes
        }
        assertTrue(decoded is CustomLyricsBackupDecodeResult.Decoded)
        val backup = (decoded as CustomLyricsBackupDecodeResult.Decoded).backup
        assertEquals(manifest, backup.manifest)
        assertEquals(files.keys, delivered.keys)
        files.forEach { (fileId, bytes) -> assertTrue(bytes.contentEquals(delivered[fileId])) }
    }

    @Test
    fun `a failed backup never decodes as valid`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
                entry(appleMusicId = 2L, fileId = "lyrics_b", ttml = ttml2),
            ),
        )
        val out = ByteArrayOutputStream()

        val result = CustomLyricsBackupCodec.encode(manifest, { null }, out)

        assertTrue(result is CustomLyricsBackupEncodeResult.Failed)
        assertTrue(
            "a partial backup must never decode as valid",
            decode(out.toByteArray()) is CustomLyricsBackupDecodeResult.Rejected,
        )
    }

    @Test
    fun `encode rejects a manifest that policy would alter`() {
        val manifest = CustomLyricsManifest(
            listOf(
                entry(1L, "lyrics_a", ttml1),
                entry(1L, "lyrics_b", ttml1),
            ),
        )

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
    fun `decode accepts a manifest with more than 32 entries when every file is present`() {
        val entries = (1L..40L).map { index -> entry(index, "lyrics_$index", ttml1) }
        val manifest = CustomLyricsManifest(entries)
        val files = entries.associate { it.fileId to ttml1.toByteArray(Charsets.UTF_8) }
        val out = ByteArrayOutputStream()
        val encoded = CustomLyricsBackupCodec.encode(manifest, { files[it] }, out)
        assertTrue(encoded is CustomLyricsBackupEncodeResult.Encoded)
        assertEquals(40, (encoded as CustomLyricsBackupEncodeResult.Encoded).entryCount)

        val delivered = linkedMapOf<String, ByteArray>()
        val decoded = CustomLyricsBackupCodec.decode(ByteArrayInputStream(out.toByteArray())) { fileId, bytes ->
            delivered[fileId] = bytes
        }
        assertTrue(decoded is CustomLyricsBackupDecodeResult.Decoded)
        assertEquals(40, (decoded as CustomLyricsBackupDecodeResult.Decoded).backup.manifest.entries.size)
        assertEquals(40, delivered.size)
    }

    @Test
    fun `a backup of over a thousand small entries roundtrips`() {
        val entries = (1L..1000L).map { index ->
            entry(index, "lyrics_$index", smallTtml(index))
        }
        val manifest = CustomLyricsManifest(entries)
        val files = entries.associate { it.fileId to smallTtml(it.appleMusicId).toByteArray(Charsets.UTF_8) }
        val out = ByteArrayOutputStream()
        val encoded = CustomLyricsBackupCodec.encode(manifest, { files[it] }, out)
        assertTrue(encoded is CustomLyricsBackupEncodeResult.Encoded)
        assertEquals(1000, (encoded as CustomLyricsBackupEncodeResult.Encoded).entryCount)

        val delivered = linkedMapOf<String, ByteArray>()
        val decoded = CustomLyricsBackupCodec.decode(ByteArrayInputStream(out.toByteArray())) { fileId, bytes ->
            delivered[fileId] = bytes
        }
        assertTrue(decoded is CustomLyricsBackupDecodeResult.Decoded)
        val backup = (decoded as CustomLyricsBackupDecodeResult.Decoded).backup
        assertEquals(1000, backup.manifest.entries.size)
        assertEquals(manifest, backup.manifest)
        assertEquals(1000, delivered.size)
        files.forEach { (fileId, bytes) -> assertTrue(bytes.contentEquals(delivered[fileId])) }
    }

    @Test
    fun `decode rejects duplicate apple music ids in the manifest`() {
        val entries = JSONObject().apply {
            put("version", 1)
            put(
                "entries",
                org.json.JSONArray().apply {
                    listOf(
                        entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1),
                        entry(appleMusicId = 1L, fileId = "lyrics_b", ttml = ttml2),
                    ).forEach { e ->
                        put(
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
                },
            )
        }
        val result = decode(zipBytes(listOf("manifest.json" to entries.toString().toByteArray())))

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
        val manifest = CustomLyricsManifest(listOf(entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)))

        val result = decode(
            zipRaw(
                listOf(
                    "manifest.json" to manifestJson(manifest),
                    "lyrics_a" to bytes,
                    "lyrics_a" to bytes,
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects duplicate manifest json`() {
        val json = CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()).toByteArray()

        val result = decode(zipWithDuplicateName("manifest.json", json, json))

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `decode rejects files that precede the manifest`() {
        val manifest = CustomLyricsManifest(listOf(entry(appleMusicId = 1L, fileId = "lyrics_a", ttml = ttml1)))

        val result = decode(
            zipRaw(
                listOf(
                    "lyrics_a" to ttml1.toByteArray(Charsets.UTF_8),
                    "manifest.json" to manifestJson(manifest),
                ),
            ),
        )

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
        val bytes = ByteArray(512 * 1024 + 1)
        val oversized = CustomLyricsEntry(
            appleMusicId = 1L,
            displayName = "Song",
            fileId = "lyrics_big",
            sizeBytes = 512 * 1024L,
            sha256 = CustomLyricsFilePolicy.sha256(bytes),
            source = CustomLyricsSources.MANUAL,
        )
        val result = decode(
            zipBytes(
                listOf(
                    "manifest.json" to manifestJson(CustomLyricsManifest(listOf(oversized))),
                    "lyrics_big" to bytes,
                ),
            ),
        )

        assertTrue(result is CustomLyricsBackupDecodeResult.Rejected)
    }

    @Test
    fun `encode rejects a backup whose total ttml exceeds the restore limit`() {
        val bigTtml = "<tt><body><p><span>" + " ".repeat(TtmlInputPolicy.MAX_TTML_BYTES - 42) + "</span></p></body></tt>"
        val bigBytes = bigTtml.toByteArray(Charsets.UTF_8)
        assertEquals(TtmlInputPolicy.MAX_TTML_BYTES, bigBytes.size)
        val base = entry(appleMusicId = 1L, fileId = "lyrics_1", ttml = bigTtml)
        val entries = (1..513).map { index -> base.copy(appleMusicId = index.toLong(), fileId = "lyrics_$index") }
        val manifest = CustomLyricsManifest(entries)
        val files = entries.associate { it.fileId to bigBytes }
        val out = ByteArrayOutputStream()
        val encoded = CustomLyricsBackupCodec.encode(manifest, { files[it] }, out)
        assertEquals(
            CustomLyricsBackupEncodeResult.Failed("歌词备份总量超过上限"),
            encoded,
        )
        assertEquals(0, out.size())
    }

    private fun decode(zip: ByteArray): CustomLyricsBackupDecodeResult =
        CustomLyricsBackupCodec.decode(ByteArrayInputStream(zip)) { _, _ -> }

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
     * Builds a zip with arbitrary entries including duplicate names.
     * ZipOutputStream refuses duplicates, so the ZIP is assembled by hand
     * (little-endian layout, STORED method, real CRC32s).
     */
    private fun zipRaw(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        val centralHeaders = ArrayList<ByteArray>()
        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
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
        eocd.putShort(0); eocd.putShort(0); eocd.putShort(entries.size.toShort()); eocd.putShort(entries.size.toShort())
        eocd.putInt(centralHeaders.sumOf { it.size })
        eocd.putInt(centralStart)
        eocd.putShort(0)
        out.write(eocd.array())
        return out.toByteArray()
    }

    private fun zipWithDuplicateName(name: String, vararg datas: ByteArray): ByteArray =
        zipRaw(datas.map { name to it })

    private fun manifestJson(manifest: CustomLyricsManifest): ByteArray =
        CustomLyricsManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)

    private fun smallTtml(index: Long): String = "<tt><body><p><span>$index</span></p></body></tt>"

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
