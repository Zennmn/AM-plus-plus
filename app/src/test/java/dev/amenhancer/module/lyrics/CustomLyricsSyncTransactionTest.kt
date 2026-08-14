package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsSyncTransactionTest {
    private val oldTtml = "<tt><body><p><span>old</span></p></body></tt>"
    private val newTtml = "<tt><body><p><span>new</span></p></body></tt>"

    @Test
    fun `sync expands alternate ids overwrites matching ids and preserves local-only ids`() {
        val events = mutableListOf<String>()
        var published = CustomLyricsManifest.empty()
        val transaction = transaction(events) { manifest ->
            published = manifest
            events += "publish"
            true
        }

        val result = transaction.sync(
            oldManifest = CustomLyricsManifest(
                listOf(
                    entry(1L, "lyrics_old", "Local old"),
                    entry(99L, "lyrics_local", "Local only"),
                ),
            ),
            plan = listOf(
                CustomLyricsSyncPlanEntry(
                    key = "am-lyrics/song.ttml",
                    appleMusicIds = listOf(1L, 2L, 7_335_408_332_109_193_189L),
                    displayName = "GitHub song",
                ),
            ),
            loadTtml = { CustomLyricsSyncLoadResult.Loaded(newTtml) },
        )

        assertTrue(result is CustomLyricsSyncResult.Synced)
        val synced = result as CustomLyricsSyncResult.Synced
        assertEquals(2, synced.importedIds)
        assertEquals(1, synced.overwrittenIds)
        assertEquals(1, synced.preservedIds)
        assertEquals(listOf(99L, 1L, 2L, 7_335_408_332_109_193_189L), published.entries.map(CustomLyricsEntry::appleMusicId))
        assertEquals(listOf("lyrics_local", "lyrics_new1", "lyrics_new2", "lyrics_new3"), published.entries.map(CustomLyricsEntry::fileId))
        assertEquals(listOf("write:lyrics_new1", "write:lyrics_new2", "write:lyrics_new3", "publish", "delete:lyrics_old"), events)
    }

    @Test
    fun `download failure rolls back files already written and does not publish`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.sync(
            oldManifest = CustomLyricsManifest.empty(),
            plan = listOf(
                planEntry("song-a.ttml", 1L),
                planEntry("song-b.ttml", 2L),
            ),
            loadTtml = { source ->
                if (source.appleMusicIds.single() == 1L) {
                    CustomLyricsSyncLoadResult.Loaded(newTtml)
                } else {
                    CustomLyricsSyncLoadResult.Failed("network")
                }
            },
        )

        assertEquals(CustomLyricsSyncResult.Failed("network"), result)
        assertEquals(listOf("write:lyrics_new1", "delete:lyrics_new1"), events)
    }

    @Test
    fun `cancellation after a progress callback rolls back the current batch`() {
        val events = mutableListOf<String>()
        val cancelled = AtomicBoolean(false)
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.sync(
            oldManifest = CustomLyricsManifest.empty(),
            plan = listOf(planEntry("song-a.ttml", 1L), planEntry("song-b.ttml", 2L)),
            loadTtml = { CustomLyricsSyncLoadResult.Loaded(newTtml) },
            isCancelled = cancelled::get,
            onProgress = { cancelled.set(true) },
        )

        assertEquals(CustomLyricsSyncResult.Cancelled, result)
        assertEquals(listOf("write:lyrics_new1", "delete:lyrics_new1"), events)
    }

    @Test
    fun `cancellation after the final progress callback does not publish`() {
        val events = mutableListOf<String>()
        val cancelled = AtomicBoolean(false)
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.sync(
            oldManifest = CustomLyricsManifest.empty(),
            plan = listOf(planEntry("song-a.ttml", 1L)),
            loadTtml = { CustomLyricsSyncLoadResult.Loaded(newTtml) },
            isCancelled = cancelled::get,
            onProgress = { cancelled.set(true) },
        )

        assertEquals(CustomLyricsSyncResult.Cancelled, result)
        assertEquals(listOf("write:lyrics_new1", "delete:lyrics_new1"), events)
    }

    @Test
    fun `duplicate source ids are rejected before downloading or writing`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events) { events += "publish"; true }

        val result = transaction.sync(
            oldManifest = CustomLyricsManifest.empty(),
            plan = listOf(planEntry("song-a.ttml", 1L), planEntry("song-b.ttml", 1L)),
            loadTtml = { error("must not load") },
        )

        assertEquals(CustomLyricsSyncResult.Failed("GitHub 索引包含重复 Apple Music ID"), result)
        assertTrue(events.isEmpty())
    }

    private fun transaction(
        events: MutableList<String>,
        publish: (CustomLyricsManifest) -> Boolean,
    ): CustomLyricsSyncTransaction = CustomLyricsSyncTransaction(
        fileIdFactory = sequence {
            var index = 0
            while (true) yield("lyrics_new${++index}")
        }.iterator()::next,
        writeRemoteFile = { fileId, _ ->
            events += "write:$fileId"
            true
        },
        publishManifest = publish,
        deleteRemoteFile = { fileId -> events += "delete:$fileId" },
    )

    private fun planEntry(key: String, appleMusicId: Long) = CustomLyricsSyncPlanEntry(
        key = key,
        appleMusicIds = listOf(appleMusicId),
        displayName = key,
    )

    private fun entry(appleMusicId: Long, fileId: String, displayName: String) = CustomLyricsEntry(
        appleMusicId = appleMusicId,
        displayName = displayName,
        fileId = fileId,
        sizeBytes = oldTtml.toByteArray(Charsets.UTF_8).size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(oldTtml.toByteArray(Charsets.UTF_8)),
        source = CustomLyricsSources.MANUAL,
        enabled = true,
    )
}
