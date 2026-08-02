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

        val result = transaction.upsert(old, draft())

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

    private fun transaction(
        events: MutableList<String>,
        publish: (CustomLyricsManifest) -> Boolean,
    ): CustomLyricsImportTransaction = CustomLyricsImportTransaction(
        fileIdFactory = { "lyrics_new" },
        writeRemoteFile = { _, _ -> events += "write"; true },
        publishManifest = publish,
        deleteRemoteFile = { fileId -> events += "delete:$fileId" },
    )

    private fun draft(ttml: String = this.ttml) = CustomLyricsDraft(
        appleMusicId = 42L,
        displayName = "Song",
        ttml = ttml,
        source = CustomLyricsSources.MANUAL,
    )

    private fun existingEntry() = CustomLyricsEntry(
        appleMusicId = 42L,
        displayName = "Old song",
        fileId = "lyrics_old",
        sizeBytes = 42L,
        sha256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
        source = CustomLyricsSources.MANUAL,
    )
}
