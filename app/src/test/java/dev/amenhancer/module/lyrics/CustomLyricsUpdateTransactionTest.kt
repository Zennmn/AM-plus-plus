package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsUpdateTransactionTest {

    @Test
    fun `only changed entries are staged and local metadata survives`() {
        val oldBytes = ttml("old").toByteArray()
        val newBytes = ttml("new").toByteArray()
        val old = manifest(
            entry(
                id = 42L,
                fileId = "lyrics_old",
                displayName = "保留名称",
                enabled = false,
                bytes = oldBytes,
            ),
        )
        val files = linkedMapOf("lyrics_old" to oldBytes)
        var published: CustomLyricsManifest? = null
        val result = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { id, bytes -> files[id] = bytes; true },
            publishManifest = { next -> published = next; true },
            deleteRemoteFile = { id -> files.remove(id) },
        ).apply(
            oldManifest = old,
            items = listOf(CustomLyricsUpdateItem.Changed(42L, CustomLyricsSources.AMLL, newBytes)),
        )

        assertTrue(result is CustomLyricsUpdateResult.Updated)
        val updated = result as CustomLyricsUpdateResult.Updated
        assertEquals(1, updated.updated)
        assertEquals("保留名称", updated.manifest.entries.single().displayName)
        assertFalse(updated.manifest.entries.single().enabled)
        assertEquals(CustomLyricsSources.AMLL, updated.manifest.entries.single().source)
        assertEquals("lyrics_new", updated.manifest.entries.single().fileId)
        assertEquals(newBytes.toList(), files.getValue("lyrics_new").toList())
        assertFalse(files.containsKey("lyrics_old"))
        assertEquals(updated.manifest, published)
    }

    @Test
    fun `changed entry can promote a metadata source while retaining local fields`() {
        val bytes = ttml("same").toByteArray()
        val old = manifest(
            CustomLyricsEntry(
                appleMusicId = 42L,
                displayName = "本地名称",
                fileId = "lyrics_old",
                sizeBytes = bytes.size.toLong(),
                sha256 = CustomLyricsFilePolicy.sha256(bytes),
                source = CustomLyricsSources.QQ_MUSIC,
                enabled = false,
            ),
        )
        val result = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> true },
            publishManifest = { true },
            deleteRemoteFile = {},
        ).apply(
            oldManifest = old,
            items = listOf(
                CustomLyricsUpdateItem.Changed(
                    appleMusicId = 42L,
                    source = CustomLyricsSources.QQ_MUSIC,
                    bytes = bytes,
                    replacementSource = CustomLyricsSources.LUNABEAT,
                ),
            ),
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(CustomLyricsSources.LUNABEAT, result.manifest.entries.single().source)
        assertEquals("本地名称", result.manifest.entries.single().displayName)
        assertFalse(result.manifest.entries.single().enabled)
    }

    @Test
    fun `unchanged skipped and failed entries do not rotate manifest`() {
        val bytes = ttml("same").toByteArray()
        val old = manifest(
            entry(1L, "lyrics_1", "one", true, bytes),
            entry(2L, "lyrics_2", "two", false, bytes),
            entry(3L, "lyrics_3", "three", true, bytes),
        )
        var writes = 0
        var publishes = 0
        val result = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> writes += 1; true },
            publishManifest = { publishes += 1; true },
            deleteRemoteFile = {},
        ).apply(
            oldManifest = old,
            items = listOf(
                CustomLyricsUpdateItem.Unchanged(1L, CustomLyricsSources.AMLL),
                CustomLyricsUpdateItem.Skipped(2L, CustomLyricsSources.MANUAL, "manual"),
                CustomLyricsUpdateItem.Failed(
                    3L,
                    CustomLyricsSources.LUNABEAT,
                    CustomLyricsUpdateFailureKind.NETWORK,
                    "offline",
                ),
            ),
        ) as CustomLyricsUpdateResult.Updated

        assertEquals(old, result.manifest)
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failed)
        assertEquals(0, writes)
        assertEquals(0, publishes)
    }

    @Test
    fun `baseline conflict and cancellation clean up staged files`() {
        val oldBytes = ttml("old").toByteArray()
        val newBytes = ttml("new").toByteArray()
        val old = manifest(entry(9L, "lyrics_old", "old", true, oldBytes))
        val files = linkedSetOf("lyrics_old")
        val conflict = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { id, _ -> files += id; true },
            publishManifest = { true },
            deleteRemoteFile = { files -= it },
            isBaselineCurrent = { false },
        ).apply(
            old,
            listOf(CustomLyricsUpdateItem.Changed(9L, CustomLyricsSources.AMLL, newBytes)),
        )
        assertTrue(conflict is CustomLyricsUpdateResult.Failed)
        assertEquals(setOf("lyrics_old"), files)

        val cancelled = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { id, _ -> files += id; true },
            publishManifest = { true },
            deleteRemoteFile = { files -= it },
        ).apply(
            old,
            listOf(CustomLyricsUpdateItem.Changed(9L, CustomLyricsSources.AMLL, newBytes)),
            isCancelled = { true },
        )
        assertEquals(CustomLyricsUpdateResult.Cancelled, cancelled)
        assertEquals(setOf("lyrics_old"), files)
    }

    @Test
    fun `requires exactly one decision for every entry`() {
        val bytes = ttml("same").toByteArray()
        val old = manifest(
            entry(1L, "lyrics_one", "one", true, bytes),
            entry(2L, "lyrics_two", "two", true, bytes),
        )
        val transaction = CustomLyricsUpdateTransaction(
            fileIdFactory = { "lyrics_new" },
            writeRemoteFile = { _, _ -> error("must not write") },
            publishManifest = { error("must not publish") },
            deleteRemoteFile = {},
        )

        val missing = transaction.apply(
            oldManifest = old,
            items = listOf(CustomLyricsUpdateItem.Unchanged(1L, CustomLyricsSources.AMLL)),
        )
        assertTrue(missing is CustomLyricsUpdateResult.Failed)

        val duplicate = transaction.apply(
            oldManifest = old,
            items = listOf(
                CustomLyricsUpdateItem.Unchanged(1L, CustomLyricsSources.AMLL),
                CustomLyricsUpdateItem.Unchanged(1L, CustomLyricsSources.AMLL),
            ),
        )
        assertTrue(duplicate is CustomLyricsUpdateResult.Failed)
    }

    private fun manifest(vararg entries: CustomLyricsEntry) = CustomLyricsManifest(entries.toList())

    private fun entry(
        id: Long,
        fileId: String,
        displayName: String,
        enabled: Boolean,
        bytes: ByteArray,
    ) = CustomLyricsEntry(
        appleMusicId = id,
        displayName = displayName,
        fileId = fileId,
        sizeBytes = bytes.size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(bytes),
        source = CustomLyricsSources.AMLL,
        enabled = enabled,
    )

    private fun ttml(text: String): String =
        "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p>$text</p></div></body></tt>"
}
