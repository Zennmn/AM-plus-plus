package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsImportTransactionTest {
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"

    @Test
    fun `writes the new ttml before publishing its manifest then retires the old file`() {
        val events = mutableListOf<String>()
        val old = CustomLyricsManifest(listOf(existingEntry()))
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.upsert(old, draft(), replacingAppleMusicId = 42L)

        assertTrue(result is CustomLyricsSaveResult.Saved)
        assertEquals(listOf("write", "publish", "delete:lyrics_old"), events)
        val saved = result as CustomLyricsSaveResult.Saved
        assertEquals(42L, saved.entry.appleMusicId)
        assertEquals("lyrics_new", saved.entry.fileId)
    }

    @Test
    fun `a failed manifest publication deletes only the new file`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; false }

        val result = transaction.upsert(CustomLyricsManifest.empty(), draft())

        assertEquals(CustomLyricsSaveResult.Failed("无法发布歌词映射"), result)
        assertEquals(listOf("write", "publish", "delete:lyrics_new"), events)
    }

    @Test
    fun `invalid ttml never writes or publishes a mapping`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.upsert(CustomLyricsManifest.empty(), draft(ttml = "not ttml"))

        assertTrue(result is CustomLyricsSaveResult.Failed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `editing an entry to a new id replaces its original identity and file`() {
        val events = mutableListOf<String>()
        var published = CustomLyricsManifest.empty()
        val transaction = transaction(events) { manifest ->
            events += "publish"
            published = manifest
            true
        }

        val result = transaction.upsert(
            oldManifest = CustomLyricsManifest(listOf(existingEntry())),
            draft = draft(appleMusicId = 84L),
            replacingAppleMusicId = 42L,
        )

        assertTrue(result is CustomLyricsSaveResult.Saved)
        assertEquals(listOf(84L), published.entries.map(CustomLyricsEntry::appleMusicId))
        assertEquals(listOf("write", "publish", "delete:lyrics_old"), events)
    }

    @Test
    fun `editing to an id owned by another entry fails before writing`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; true }
        val old = CustomLyricsManifest(
            listOf(
                existingEntry(),
                existingEntry(appleMusicId = 84L, fileId = "lyrics_other"),
            ),
        )

        val result = transaction.upsert(
            oldManifest = old,
            draft = draft(appleMusicId = 84L),
            replacingAppleMusicId = 42L,
        )

        assertEquals(CustomLyricsSaveResult.Failed("目标 Apple Music ID 已存在"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `adding an id that already exists fails without overwriting its file`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.upsert(
            oldManifest = CustomLyricsManifest(listOf(existingEntry())),
            draft = draft(),
        )

        assertEquals(CustomLyricsSaveResult.Failed("目标 Apple Music ID 已存在"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `batch edit writes every id then publishes once and retires replaced file`() {
        val events = mutableListOf<String>()
        var nextFile = 0
        var published = CustomLyricsManifest.empty()
        val transaction = CustomLyricsImportTransaction(
            fileIdFactory = { "lyrics_${++nextFile}" },
            writeRemoteFile = { fileId, _ -> events += "write:$fileId"; true },
            publishManifest = { manifest -> events += "publish"; published = manifest; true },
            deleteRemoteFile = { fileId -> events += "delete:$fileId" },
        )

        val result = transaction.upsertMany(
            oldManifest = CustomLyricsManifest(listOf(existingEntry())),
            draft = multiDraft(listOf(42L, 84L)),
            replacingAppleMusicIds = listOf(42L),
        )

        assertTrue(result is CustomLyricsBatchSaveResult.Saved)
        assertEquals(listOf(42L, 84L), published.entries.map(CustomLyricsEntry::appleMusicId))
        assertEquals(
            listOf("write:lyrics_1", "write:lyrics_2", "publish", "delete:lyrics_old"),
            events,
        )
    }

    @Test
    fun `batch write failure rolls back all generated files without publishing`() {
        val events = mutableListOf<String>()
        var nextFile = 0
        var writes = 0
        val transaction = CustomLyricsImportTransaction(
            fileIdFactory = { "lyrics_${++nextFile}" },
            writeRemoteFile = { fileId, _ ->
                events += "write:$fileId"
                writes++ == 0
            },
            publishManifest = { events += "publish"; true },
            deleteRemoteFile = { fileId -> events += "delete:$fileId" },
        )

        val result = transaction.upsertMany(
            oldManifest = CustomLyricsManifest.empty(),
            draft = multiDraft(listOf(42L, 84L)),
        )

        assertEquals(CustomLyricsBatchSaveResult.Failed("无法写入共享歌词文件"), result)
        assertEquals(
            listOf(
                "write:lyrics_1",
                "write:lyrics_2",
                "delete:lyrics_1",
                "delete:lyrics_2",
            ),
            events,
        )
    }

    private fun transaction(
        events: MutableList<String>,
        publish: (CustomLyricsManifest) -> Boolean,
    ): CustomLyricsImportTransaction = CustomLyricsImportTransaction(
        fileIdFactory = { "lyrics_new" },
        writeRemoteFile = { _, _ -> events += "write"; true },
        publishManifest = publish,
        deleteRemoteFile = { fileId -> events += "delete:$fileId" },
    )

    private fun draft(
        ttml: String = this.ttml,
        appleMusicId: Long = 42L,
    ) = CustomLyricsDraft(
        appleMusicId = appleMusicId,
        displayName = "Song",
        ttml = ttml,
        source = CustomLyricsSources.MANUAL,
    )

    private fun multiDraft(appleMusicIds: List<Long>) = CustomLyricsMultiIdDraft(
        appleMusicIds = appleMusicIds,
        displayName = "Song",
        ttml = ttml,
        source = CustomLyricsSources.MANUAL,
    )

    private fun existingEntry(
        appleMusicId: Long = 42L,
        fileId: String = "lyrics_old",
    ) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = "Old song",
        fileId = fileId,
        sizeBytes = 42L,
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
        source = CustomLyricsSources.MANUAL,
    )
}
