package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsRestoreTransactionTest {
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"

    @Test
    fun `merging A plus B with backup B plus C yields A B prime C`() {
        val events = mutableListOf<String>()
        var published = CustomLyricsManifest.empty()
        val current = CustomLyricsManifest(
            listOf(
                existingEntry(appleMusicId = 1L, fileId = "lyrics_a"),
                existingEntry(appleMusicId = 2L, fileId = "lyrics_b"),
            ),
        )
        val backup = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb", displayName = "B new") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(events) { manifest ->
            events += "publish"
            published = manifest
            true
        }.merge(current, backup)

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(
            listOf(1L, 2L, 3L),
            (result as CustomLyricsRestoreResult.Restored).manifest.entries.map(CustomLyricsEntry::appleMusicId),
        )
        assertEquals(
            listOf("lyrics_a", "lyrics_new1", "lyrics_new2"),
            published.entries.map(CustomLyricsEntry::fileId),
        )
        assertEquals("B new", published.entries[1].displayName)
        assertEquals(listOf("write:lyrics_new1", "write:lyrics_new2", "publish", "delete:lyrics_b"), events)
    }

    @Test
    fun `a write failure deletes only the new files written so far`() {
        val events = mutableListOf<String>()
        val current = CustomLyricsManifest(
            listOf(
                existingEntry(appleMusicId = 1L, fileId = "lyrics_a"),
                existingEntry(appleMusicId = 2L, fileId = "lyrics_b"),
            ),
        )
        val backup = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(
            events,
            write = { fileId, _ ->
                events += "write:$fileId"
                fileId != "lyrics_new2"
            },
        ) { events += "publish"; true }.merge(current, backup)

        assertEquals(CustomLyricsRestoreResult.Failed("无法写入共享歌词文件"), result)
        assertEquals(
            listOf(
                "write:lyrics_new1",
                "write:lyrics_new2",
                "delete:lyrics_new2",
                "delete:lyrics_new1",
            ),
            events,
        )
    }

    @Test
    fun `a publish failure deletes all new files and keeps old files`() {
        val events = mutableListOf<String>()
        val current = CustomLyricsManifest(
            listOf(
                existingEntry(appleMusicId = 1L, fileId = "lyrics_a"),
                existingEntry(appleMusicId = 2L, fileId = "lyrics_b"),
            ),
        )
        val backup = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(events) { events += "publish"; false }.merge(current, backup)

        assertEquals(CustomLyricsRestoreResult.Failed("无法发布歌词映射"), result)
        assertEquals(
            listOf("write:lyrics_new1", "write:lyrics_new2", "publish", "delete:lyrics_new1", "delete:lyrics_new2"),
            events,
        )
    }

    @Test
    fun `an empty backup succeeds and leaves the current manifest untouched`() {
        val events = mutableListOf<String>()
        val current = CustomLyricsManifest(listOf(existingEntry(appleMusicId = 1L, fileId = "lyrics_a")))

        val result = transaction(events) { events += "publish"; true }
            .merge(current, backup())

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(current, (result as CustomLyricsRestoreResult.Restored).manifest)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a merge exceeding the entry limit fails before any write`() {
        val events = mutableListOf<String>()
        val current = CustomLyricsManifest(
            (1L..CustomLyricsManifestPolicy.MAX_ENTRIES.toLong()).map { existingEntry(it, "lyrics_$it") },
        )
        val backup = backup(backupEntry(appleMusicId = 100L, fileId = "lyrics_new") to ttmlBytes())

        val result = transaction(events) { events += "publish"; true }.merge(current, backup)

        assertEquals(CustomLyricsRestoreResult.Failed("合并后歌词映射数量超过上限"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an invalid generated file id fails before any write`() {
        val events = mutableListOf<String>()
        val backup = backup(backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes())

        val result = transaction(events, fileIdFactory = { "bad file id!" }) { events += "publish"; true }
            .merge(CustomLyricsManifest.empty(), backup)

        assertEquals(CustomLyricsRestoreResult.Failed("无法生成歌词文件 ID"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a generated file id collision fails before any write`() {
        val events = mutableListOf<String>()
        val current = CustomLyricsManifest(listOf(existingEntry(1L, "lyrics_taken")))
        val backup = backup(backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes())

        val result = transaction(events, fileIdFactory = { "lyrics_taken" }) { events += "publish"; true }
            .merge(current, backup)

        assertEquals(CustomLyricsRestoreResult.Failed("无法生成唯一歌词文件 ID"), result)
        assertTrue(events.isEmpty())
    }

    private fun transaction(
        events: MutableList<String>,
        fileIdFactory: () -> String = defaultFileIdFactory(),
        write: (String, ByteArray) -> Boolean = { fileId, _ ->
            events += "write:$fileId"
            true
        },
        publish: (CustomLyricsManifest) -> Boolean,
    ): CustomLyricsRestoreTransaction = CustomLyricsRestoreTransaction(
        fileIdFactory = fileIdFactory,
        writeRemoteFile = write,
        publishManifest = publish,
        deleteRemoteFile = { fileId -> events += "delete:$fileId" },
    )

    private fun defaultFileIdFactory(): () -> String {
        var next = 0
        return { next += 1; "lyrics_new$next" }
    }

    private fun backup(vararg entries: Pair<CustomLyricsEntry, ByteArray>): CustomLyricsBackup =
        CustomLyricsBackup(
            manifest = CustomLyricsManifest(entries.map { it.first }),
            files = entries.associate { it.first.fileId to it.second },
        )

    private fun backupEntry(
        appleMusicId: Long,
        fileId: String,
        displayName: String = "Song $appleMusicId",
    ) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = displayName,
        fileId = fileId,
        sizeBytes = ttmlBytes().size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(ttmlBytes()),
        source = CustomLyricsSources.MANUAL,
    )

    private fun existingEntry(appleMusicId: Long, fileId: String) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = "Old song $appleMusicId",
        fileId = fileId,
        sizeBytes = ttmlBytes().size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(ttmlBytes()),
        source = CustomLyricsSources.MANUAL,
    )

    private fun ttmlBytes(): ByteArray = ttml.toByteArray(Charsets.UTF_8)
}
