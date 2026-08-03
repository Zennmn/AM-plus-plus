package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        val (backup, files) = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb", displayName = "B new") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(events) { manifest ->
            events += "publish"
            published = manifest
            true
        }.merge(current, streamBackup(backup, files))

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
        val (backup, files) = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(
            events,
            write = { fileId, _ ->
                events += "write:$fileId"
                fileId != "lyrics_new2"
            },
        ) { events += "publish"; true }.merge(current, streamBackup(backup, files))

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
        val (backup, files) = backup(
            backupEntry(appleMusicId = 2L, fileId = "lyrics_bb") to ttmlBytes(),
            backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes(),
        )

        val result = transaction(events) { events += "publish"; false }.merge(current, streamBackup(backup, files))

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
            .merge(current, streamBackup(CustomLyricsBackup(CustomLyricsManifest.empty()), emptyMap()))

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(current, (result as CustomLyricsRestoreResult.Restored).manifest)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `merging a backup with more than 32 entries publishes every entry`() {
        val events = mutableListOf<String>()
        var published = CustomLyricsManifest.empty()
        val current = CustomLyricsManifest(
            (1L..5L).map { existingEntry(it, "lyrics_old$it") },
        )
        val backupEntries = (2L..41L).map { backupEntry(it, "lyrics_bb$it") }
        val (backup, files) = backup(*backupEntries.map { it to ttmlBytes() }.toTypedArray())

        val result = transaction(events) { manifest ->
            events += "publish"
            published = manifest
            true
        }.merge(current, streamBackup(backup, files))

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(41, (result as CustomLyricsRestoreResult.Restored).manifest.entries.size)
        assertEquals(41, published.entries.size)
        assertEquals((1L..41L).toList(), published.entries.map(CustomLyricsEntry::appleMusicId))
        assertEquals((1..40).map { "write:lyrics_new$it" }, events.take(40))
        assertEquals("publish", events[40])
        assertEquals(
            listOf("delete:lyrics_old2", "delete:lyrics_old3", "delete:lyrics_old4", "delete:lyrics_old5"),
            events.drop(41),
        )
    }

    @Test
    fun `restoring a 40 entry backup through the real codec rebuilds every file with a fresh id`() {
        val events = mutableListOf<String>()
        var published = CustomLyricsManifest.empty()
        val current = CustomLyricsManifest(
            listOf(
                existingEntry(4L, "lyrics_old4"),
                existingEntry(5L, "lyrics_old5"),
                existingEntry(6L, "lyrics_old6"),
            ),
        )
        val backupEntries = (1L..40L).map { backupEntry(it, "lyrics_$it") }
        val files = backupEntries.associate { it.fileId to ttmlBytes() }
        val out = ByteArrayOutputStream()
        val encoded = CustomLyricsBackupCodec.encode(
            CustomLyricsManifest(backupEntries),
            { fileId -> files[fileId] },
            out,
        )
        assertTrue(encoded is CustomLyricsBackupEncodeResult.Encoded)

        val result = transaction(events) { manifest ->
            events += "publish"
            published = manifest
            true
        }.merge(current) { onFile ->
            CustomLyricsBackupCodec.decode(ByteArrayInputStream(out.toByteArray()), onFile)
        }

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(40, (result as CustomLyricsRestoreResult.Restored).manifest.entries.size)
        assertEquals(40, published.entries.size)
        assertEquals(
            listOf(4L, 5L, 6L) + (1L..3L) + (7L..40L),
            published.entries.map(CustomLyricsEntry::appleMusicId),
        )
        assertTrue(published.entries.all { it.fileId.startsWith("lyrics_new") })
        assertEquals(40, published.entries.map(CustomLyricsEntry::fileId).toSet().size)
        assertEquals(40, events.count { it.startsWith("write:") })
        assertEquals(listOf("publish"), events.filter { it.startsWith("publish") })
        assertEquals(
            listOf("delete:lyrics_old4", "delete:lyrics_old5", "delete:lyrics_old6"),
            events.filter { it.startsWith("delete:") },
        )
    }

    @Test
    fun `an invalid generated file id fails before any write`() {
        val events = mutableListOf<String>()
        val (backup, files) = backup(backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes())

        val result = transaction(events, fileIdFactory = { "bad file id!" }) { events += "publish"; true }
            .merge(CustomLyricsManifest.empty(), streamBackup(backup, files))

        assertEquals(CustomLyricsRestoreResult.Failed("无法生成歌词文件 ID"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a generated file id collision fails before any write`() {
        val events = mutableListOf<String>()
        val (backup, files) = backup(backupEntry(appleMusicId = 3L, fileId = "lyrics_cc") to ttmlBytes())

        val result = transaction(events, fileIdFactory = { "lyrics_taken" }) { events += "publish"; true }
            .merge(CustomLyricsManifest(listOf(existingEntry(1L, "lyrics_taken"))), streamBackup(backup, files))

        assertEquals(CustomLyricsRestoreResult.Failed("无法生成唯一歌词文件 ID"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a rejected backup rolls back files written before the rejection`() {
        val events = mutableListOf<String>()

        val result = transaction(events) { events += "publish"; true }.merge(CustomLyricsManifest.empty()) { onFile ->
            onFile("lyrics_bb", ttmlBytes())
            CustomLyricsBackupDecodeResult.Rejected("备份条目过多")
        }

        assertEquals(CustomLyricsRestoreResult.Failed("备份条目过多"), result)
        assertEquals(listOf("write:lyrics_new1", "delete:lyrics_new1"), events)
    }

    @Test
    fun `a backup missing a declared file fails and rolls back`() {
        val events = mutableListOf<String>()
        val declared = CustomLyricsBackup(
            CustomLyricsManifest(
                listOf(
                    backupEntry(1L, "lyrics_bb"),
                    backupEntry(2L, "lyrics_cc"),
                ),
            ),
        )

        val result = transaction(events) { events += "publish"; true }.merge(CustomLyricsManifest.empty()) { onFile ->
            onFile("lyrics_bb", ttmlBytes())
            CustomLyricsBackupDecodeResult.Decoded(declared)
        }

        assertEquals(CustomLyricsRestoreResult.Failed("备份内容缺失"), result)
        assertEquals(listOf("write:lyrics_new1", "delete:lyrics_new1"), events)
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

    private fun streamBackup(
        backup: CustomLyricsBackup,
        files: Map<String, ByteArray>,
    ): (onFile: (String, ByteArray) -> Unit) -> CustomLyricsBackupDecodeResult = { onFile ->
        files.forEach { (fileId, bytes) -> onFile(fileId, bytes) }
        CustomLyricsBackupDecodeResult.Decoded(backup)
    }

    private fun backup(vararg entries: Pair<CustomLyricsEntry, ByteArray>): Pair<CustomLyricsBackup, Map<String, ByteArray>> =
        CustomLyricsBackup(CustomLyricsManifest(entries.map { it.first })) to
            entries.associate { it.first.fileId to it.second }

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
