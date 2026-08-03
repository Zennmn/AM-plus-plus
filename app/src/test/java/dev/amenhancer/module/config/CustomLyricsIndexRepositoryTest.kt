package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsIndexRepositoryTest {
    private val sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"
    private val legacyEntry = entry(42L, "lyrics_legacy")
    private val legacyJson = v1Json(listOf(legacyEntry))

    @Test
    fun `legacy v1 preferences resolve without any remote index file`() {
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
        )

        val state = CustomLyricsIndexRepository.state(prefs) { null }

        assertNull(state.pointer)
        assertEquals(listOf(42L), state.manifest.entries.map(CustomLyricsEntry::appleMusicId))
    }

    @Test
    fun `a published v2 index file wins over the legacy v1 preference string`() {
        val files = mutableMapOf<String, ByteArray>()
        val large = largeManifest(1100)
        val indexBytes = CustomLyricsManifestCodec.encode(large).toByteArray(Charsets.UTF_8)
        files["index_42"] = indexBytes
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_42",
            "custom_lyrics_index_generation" to 5L,
            "custom_lyrics_index_sha256" to sha256(indexBytes),
            "custom_lyrics_index_size_bytes" to indexBytes.size.toLong(),
        )

        val state = CustomLyricsIndexRepository.state(prefs) { fileId -> files[fileId]?.let(::ByteArrayInputStream) }

        assertEquals(1100, state.manifest.entries.size)
        assertEquals(listOf(100001L), state.manifest.entries.map(CustomLyricsEntry::appleMusicId).take(1))
        assertEquals("index_42", state.pointer?.fileId)
    }

    @Test
    fun `a corrupt published index fails closed instead of using stale legacy data`() {
        val files = mutableMapOf<String, ByteArray>()
        val indexBytes = CustomLyricsManifestCodec.encode(largeManifest(5)).toByteArray(Charsets.UTF_8)
        files["index_42"] = indexBytes
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_42",
            "custom_lyrics_index_generation" to 5L,
            "custom_lyrics_index_sha256" to sha256Of("tampered"),
            "custom_lyrics_index_size_bytes" to indexBytes.size.toLong(),
        )

        val state = CustomLyricsIndexRepository.state(prefs) { fileId -> files[fileId]?.let(::ByteArrayInputStream) }

        assertTrue(state.manifest.entries.isEmpty())
        assertEquals("index_42", state.pointer?.fileId)
        assertEquals(false, state.canCommit)
    }

    @Test
    fun `a missing published index fails closed instead of using stale legacy data`() {
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_missing",
            "custom_lyrics_index_generation" to 5L,
            "custom_lyrics_index_sha256" to sha256,
            "custom_lyrics_index_size_bytes" to 42L,
        )

        val state = CustomLyricsIndexRepository.state(prefs) { null }

        assertTrue(state.manifest.entries.isEmpty())
        assertEquals(false, state.canCommit)
    }

    @Test
    fun `partial pointer values fail closed and cannot overwrite the index`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_partial",
        )
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_new"))

        val state = repository.state(prefs)
        val result = repository.commit(state, largeManifest(2))

        assertTrue(state.manifest.entries.isEmpty())
        assertEquals(false, state.canCommit)
        assertEquals(
            CustomLyricsIndexCommitResult.Failed("歌词索引文件不可读，无法修改"),
            result,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `explicit recovery can replace an unreadable index from a complete backup`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_index_file_id" to "index_missing",
            "custom_lyrics_index_generation" to 5L,
            "custom_lyrics_index_sha256" to sha256,
            "custom_lyrics_index_size_bytes" to 42L,
        )
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_recovered"))
        val state = repository.state(prefs)

        val result = repository.commit(state, largeManifest(40), allowRecovery = true)

        assertTrue(result is CustomLyricsIndexCommitResult.Committed)
        assertEquals(6L, (result as CustomLyricsIndexCommitResult.Committed).pointer.generation)
        assertEquals("index_recovered", prefs["custom_lyrics_index_file_id"])
        assertEquals(40, repository.state(prefs).manifest.entries.size)
    }

    @Test
    fun `an unreadable published index cannot be overwritten from a stale legacy fallback`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_missing",
            "custom_lyrics_index_generation" to 5L,
            "custom_lyrics_index_sha256" to sha256,
            "custom_lyrics_index_size_bytes" to 42L,
        )
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_new"))

        val result = repository.commit(repository.state(prefs), largeManifest(2))

        assertEquals(
            CustomLyricsIndexCommitResult.Failed("歌词索引文件不可读，无法修改"),
            result,
        )
        assertTrue(events.isEmpty())
        assertEquals("index_missing", prefs["custom_lyrics_index_file_id"])
    }

    @Test
    fun `an oversized index is rejected before writing or publishing`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val events = mutableListOf<String>()
        val repository = repository(
            files,
            prefs,
            events,
            nextFileIds = listOf("index_new"),
            maxIndexBytes = 100,
        )

        val result = repository.commit(
            CustomLyricsIndexState(null, CustomLyricsManifest.empty()),
            CustomLyricsManifest(listOf(entry(42L, "lyrics_a"))),
        )

        assertEquals(
            CustomLyricsIndexCommitResult.Failed("歌词索引超出大小上限"),
            result,
        )
        assertTrue(events.isEmpty())
        assertTrue(files.isEmpty())
    }

    @Test
    fun `neither pointer nor legacy yields an empty manifest`() {
        val state = CustomLyricsIndexRepository.state(emptyMap<String, Any>()) { null }

        assertNull(state.pointer)
        assertTrue(state.manifest.entries.isEmpty())
    }

    @Test
    fun `an empty published index is authoritative over the legacy string`() {
        val files = mutableMapOf<String, ByteArray>()
        val indexBytes = CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()).toByteArray(Charsets.UTF_8)
        files["index_42"] = indexBytes
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_manifest" to legacyJson,
            "custom_lyrics_index_file_id" to "index_42",
            "custom_lyrics_index_generation" to 3L,
            "custom_lyrics_index_sha256" to sha256(indexBytes),
            "custom_lyrics_index_size_bytes" to indexBytes.size.toLong(),
        )

        val state = CustomLyricsIndexRepository.state(prefs) { fileId -> files[fileId]?.let(::ByteArrayInputStream) }

        assertTrue(state.manifest.entries.isEmpty())
    }

    @Test
    fun `first commit migrates legacy v1 into an index file and publishes generation one`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>("custom_lyrics_manifest" to legacyJson)
        val repository = repository(files, prefs, nextFileIds = listOf("index_new"))

        val state = CustomLyricsIndexRepository.state(prefs) { null }
        val next = CustomLyricsManifest(listOf(legacyEntry, entry(84L, "lyrics_new")))
        val result = repository.commit(state, next)

        assertTrue(result is CustomLyricsIndexCommitResult.Committed)
        val committed = result as CustomLyricsIndexCommitResult.Committed
        assertEquals("index_new", committed.pointer.fileId)
        assertEquals(1L, committed.pointer.generation)
        assertEquals("index_new", prefs["custom_lyrics_index_file_id"])
        assertEquals(1L, prefs["custom_lyrics_index_generation"])
        assertTrue(files.containsKey("index_new"))
        assertTrue(prefs.containsKey("custom_lyrics_manifest"))
        val resolved = CustomLyricsIndexRepository.resolve(prefs) { fileId -> files[fileId]?.let(::ByteArrayInputStream) }
        assertEquals(listOf(42L, 84L), resolved.entries.map(CustomLyricsEntry::appleMusicId))
    }

    @Test
    fun `commit writes the new index file, publishes the pointer, then retires the old index file`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_new"))
        val oldPointer = CustomLyricsIndexPointer("index_old", 3L, sha256, 10L)
        val state = CustomLyricsIndexState(
            pointer = oldPointer,
            manifest = CustomLyricsManifest(listOf(entry(42L, "lyrics_a"))),
        )

        val result = repository.commit(state, CustomLyricsManifest(listOf(entry(42L, "lyrics_a"), entry(84L, "lyrics_b"))))

        assertTrue(result is CustomLyricsIndexCommitResult.Committed)
        val committed = result as CustomLyricsIndexCommitResult.Committed
        assertEquals("index_new", committed.pointer.fileId)
        assertEquals(4L, committed.pointer.generation)
        assertEquals(listOf("write:index_new", "publish", "delete:index_old"), events)
        assertEquals("index_new", prefs["custom_lyrics_index_file_id"])
        assertTrue(!files.containsKey("index_old"))
    }

    @Test
    fun `a failed pointer publication keeps the old pointer and deletes the new index file`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>(
            "custom_lyrics_index_file_id" to "index_old",
            "custom_lyrics_index_generation" to 3L,
            "custom_lyrics_index_sha256" to sha256,
            "custom_lyrics_index_size_bytes" to 10L,
        )
        files["index_old"] = byteArrayOf(1, 2, 3)
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_new")) {
            events += "publish"
            false
        }
        val state = CustomLyricsIndexState(
            pointer = CustomLyricsIndexPointer("index_old", 3L, sha256, 10L),
            manifest = CustomLyricsManifest(listOf(entry(42L, "lyrics_a"))),
        )

        val result = repository.commit(state, CustomLyricsManifest(listOf(entry(42L, "lyrics_a"), entry(84L, "lyrics_b"))))

        assertTrue(result is CustomLyricsIndexCommitResult.Failed)
        assertEquals(listOf("write:index_new", "publish", "delete:index_new"), events)
        assertEquals("index_old", prefs["custom_lyrics_index_file_id"])
        assertEquals(3L, prefs["custom_lyrics_index_generation"])
        assertTrue(!files.containsKey("index_new"))
        assertTrue(files.containsKey("index_old"))
    }

    @Test
    fun `a failed index file write publishes nothing and deletes the new file`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val repository = repository(
            files,
            prefs,
            nextFileIds = listOf("index_new"),
            write = { _, _ -> false },
        )

        val result = repository.commit(
            CustomLyricsIndexState(null, CustomLyricsManifest.empty()),
            CustomLyricsManifest(listOf(entry(42L, "lyrics_a"))),
        )

        assertTrue(result is CustomLyricsIndexCommitResult.Failed)
        assertTrue(prefs.isEmpty())
        assertTrue(files.isEmpty())
    }

    @Test
    fun `a commit with no prior pointer never deletes a file`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val events = mutableListOf<String>()
        val repository = repository(files, prefs, events, nextFileIds = listOf("index_new"))

        repository.commit(
            CustomLyricsIndexState(null, CustomLyricsManifest.empty()),
            CustomLyricsManifest(listOf(entry(42L, "lyrics_a"))),
        )

        assertEquals(listOf("write:index_new", "publish"), events)
    }

    @Test
    fun `generation increments across commits`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val repository = repository(files, prefs, nextFileIds = listOf("index_1", "index_2"))
        val manifest = CustomLyricsManifest(listOf(entry(42L, "lyrics_a")))

        val first = repository.commit(
            CustomLyricsIndexState(null, CustomLyricsManifest.empty()),
            manifest,
        ) as CustomLyricsIndexCommitResult.Committed
        val second = repository.commit(
            CustomLyricsIndexState(first.pointer, first.manifest),
            manifest,
        ) as CustomLyricsIndexCommitResult.Committed

        assertEquals(1L, first.pointer.generation)
        assertEquals(2L, second.pointer.generation)
        assertEquals(2L, prefs["custom_lyrics_index_generation"])
    }

    @Test
    fun `over a thousand entries survive a commit and resolve round trip`() {
        val files = mutableMapOf<String, ByteArray>()
        val prefs = mutableMapOf<String, Any>()
        val repository = repository(files, prefs, nextFileIds = listOf("index_big"))
        val large = largeManifest(1100)

        val committed = repository.commit(
            CustomLyricsIndexState(null, CustomLyricsManifest.empty()),
            large,
        ) as CustomLyricsIndexCommitResult.Committed

        assertEquals(1100, committed.manifest.entries.size)
        val resolved = CustomLyricsIndexRepository.resolve(prefs) { fileId -> files[fileId]?.let(::ByteArrayInputStream) }
        assertEquals(1100, resolved.entries.size)
        assertEquals(large, resolved)
    }

    private fun repository(
        files: MutableMap<String, ByteArray>,
        prefs: MutableMap<String, Any>,
        events: MutableList<String> = mutableListOf(),
        nextFileIds: List<String>,
        write: (String, ByteArray) -> Boolean = { fileId, bytes ->
            events += "write:$fileId"
            files[fileId] = bytes
            true
        },
        maxIndexBytes: Int = CustomLyricsManifestPolicy.MAX_INDEX_BYTES,
        publish: (CustomLyricsIndexPointer) -> Boolean = { pointer ->
            events += "publish"
            prefs.putAll(ModuleSettingsSchema.encodeIndexPointer(pointer))
            true
        },
    ): CustomLyricsIndexRepository {
        val ids = ArrayDeque(nextFileIds)
        return CustomLyricsIndexRepository(
            newIndexFileId = { ids.removeFirst() },
            openFile = { fileId -> files[fileId]?.let(::ByteArrayInputStream) },
            writeRemoteFile = write,
            publishPointer = publish,
            deleteRemoteFile = { fileId ->
                events += "delete:$fileId"
                files.remove(fileId)
            },
            maxIndexBytes = maxIndexBytes,
        )
    }

    private fun entry(appleMusicId: Long, fileId: String) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = "Song $appleMusicId",
        fileId = fileId,
        sizeBytes = 42L,
        sha256 = sha256,
        source = CustomLyricsSources.MANUAL,
        enabled = true,
    )

    private fun largeManifest(count: Int): CustomLyricsManifest = CustomLyricsManifest(
        (1..count).map { index ->
            CustomLyricsEntry(
                appleMusicId = 100000L + index,
                displayName = "Song $index",
                fileId = "lyrics_%06d".format(index),
                sizeBytes = 42L,
                sha256 = sha256,
                source = CustomLyricsSources.MANUAL,
                enabled = true,
            )
        },
    )

    private fun v1Json(entries: List<CustomLyricsEntry>): String = JSONObject().apply {
        put("version", 1)
        put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("appleMusicId", entry.appleMusicId)
                    put("displayName", entry.displayName)
                    put("fileId", entry.fileId)
                    put("sizeBytes", entry.sizeBytes)
                    put("sha256", entry.sha256)
                    put("source", entry.source)
                    put("enabled", entry.enabled)
                })
            }
        })
    }.toString()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256Of(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}
