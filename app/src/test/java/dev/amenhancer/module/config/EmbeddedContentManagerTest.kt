package dev.amenhancer.module.config

import dev.amenhancer.module.font.FontFilePolicy
import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.lyrics.CustomLyricsBackupEncodeResult
import dev.amenhancer.module.lyrics.CustomLyricsBatchSaveResult
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsRestoreResult
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.LyricsFontManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedContentManagerTest {

    @Test
    fun `font replacement publishes the new manifest before retiring the old file`() {
        val storage = MemoryStorage()
        val session = EmbeddedConfigurationSession(storage)
        val oldBytes = validFontBytes(1)
        storage.files["font_old"] = oldBytes
        assertTrue(
            session.saveFontManifest(
                LyricsFontManifest(
                    enabled = true,
                    fileId = "font_old",
                    displayName = "Old",
                    sizeBytes = oldBytes.size.toLong(),
                    sha256 = FontFilePolicy.sha256(oldBytes),
                ),
            ),
        )
        storage.events.clear()

        val manager = EmbeddedContentManager(
            session = session,
            fileIdFactory = sequenceFileIds("font_new"),
        )
        val replacement = validFontBytes(2)

        val result = manager.importFont("New", replacement)

        assertTrue(result is FontImportResult.Imported)
        val manifest = (result as FontImportResult.Imported).manifest
        assertNotEquals("font_old", manifest.fileId)
        assertEquals("font_new_1", manifest.fileId)
        assertArrayEquals(replacement, storage.files[manifest.fileId])
        assertFalse(storage.files.containsKey("font_old"))
        assertEquals(
            listOf("write:font_new_1", "publish-font", "delete:font_old"),
            storage.events,
        )
    }

    @Test
    fun `font replacement rolls back the new file when the manifest cannot be published`() {
        val storage = MemoryStorage()
        val session = EmbeddedConfigurationSession(storage)
        val oldBytes = validFontBytes(3)
        storage.files["font_old"] = oldBytes
        session.saveFontManifest(
            LyricsFontManifest(
                enabled = true,
                fileId = "font_old",
                displayName = "Old",
                sizeBytes = oldBytes.size.toLong(),
                sha256 = FontFilePolicy.sha256(oldBytes),
            ),
        )
        storage.events.clear()
        storage.failFontPublish = true

        val result = EmbeddedContentManager(
            session = session,
            fileIdFactory = sequenceFileIds("font_new"),
        ).importFont("New", validFontBytes(4))

        assertTrue(result is FontImportResult.Failed)
        assertFalse(storage.files.containsKey("font_new_1"))
        assertArrayEquals(oldBytes, storage.files["font_old"])
        assertEquals("font_old", session.settings().fontManifest.fileId)
        assertEquals(
            listOf("write:font_new_1", "publish-font", "delete:font_new_1"),
            storage.events,
        )
    }

    @Test
    fun `lyrics can be created read updated enabled and deleted through the facade`() {
        val storage = MemoryStorage()
        val manager = EmbeddedContentManager(
            session = EmbeddedConfigurationSession(storage),
            fileIdFactory = sequenceFileIds("lyrics_file"),
        )

        val first = manager.addLyrics(101L, "First", ttml("first"))
        val second = manager.addLyrics(202L, "Second", ttml("second"))

        assertEquals(listOf(101L, 202L), manager.listLyrics().map(CustomLyricsEntry::appleMusicId))
        assertEquals(ttml("first"), manager.readLyrics(101L))
        assertTrue(first is CustomLyricsSaveResult.Saved)
        assertTrue(second is CustomLyricsSaveResult.Saved)

        val oldFirstFileId = (first as CustomLyricsSaveResult.Saved).entry.fileId
        val updated = manager.updateLyrics(101L, "First updated", ttml("updated"), enabled = false)

        assertTrue(updated is CustomLyricsSaveResult.Saved)
        val updatedEntry = (updated as CustomLyricsSaveResult.Saved).entry
        assertNotEquals(oldFirstFileId, updatedEntry.fileId)
        assertFalse(storage.files.containsKey(oldFirstFileId))
        assertEquals(ttml("updated"), manager.readLyrics(101L))
        assertFalse(manager.listLyrics().single { it.appleMusicId == 101L }.enabled)

        val enabled = manager.setLyricsEnabled(101L, true)
        assertTrue(enabled is EmbeddedLyricsMutationResult.Updated)
        assertTrue(manager.listLyrics().single { it.appleMusicId == 101L }.enabled)

        val deleted = manager.deleteLyrics(202L)
        assertTrue(deleted is EmbeddedLyricsMutationResult.Updated)
        assertEquals(listOf(101L), manager.listLyrics().map(CustomLyricsEntry::appleMusicId))
        assertEquals(null, manager.readLyrics(202L))
    }

    @Test
    fun `multi id lyric save enable and delete are one host transaction`() {
        val storage = MemoryStorage()
        val manager = EmbeddedContentManager(
            session = EmbeddedConfigurationSession(storage),
            fileIdFactory = sequenceFileIds("lyrics_multi"),
        )

        val saved = manager.saveLyrics(
            CustomLyricsMultiIdDraft(
                appleMusicIds = listOf(601L, 602L),
                displayName = "Grouped",
                ttml = ttml("grouped"),
                source = CustomLyricsSources.AM_LYRICS,
            ),
        )

        assertTrue(saved is CustomLyricsBatchSaveResult.Saved)
        assertEquals(listOf(601L, 602L), manager.listLyrics().map(CustomLyricsEntry::appleMusicId))
        assertEquals(ttml("grouped"), manager.readLyrics(601L))
        assertEquals(ttml("grouped"), manager.readLyrics(602L))

        val disabled = manager.setLyricsEnabled(listOf(601L, 602L), false)
        assertTrue(disabled is EmbeddedLyricsMutationResult.Updated)
        assertTrue(manager.listLyrics().all { !it.enabled })

        val deleted = manager.deleteLyrics(listOf(601L, 602L))
        assertTrue(deleted is EmbeddedLyricsMutationResult.Updated)
        assertTrue(manager.listLyrics().isEmpty())
        assertTrue(storage.files.keys.none { it.startsWith("lyrics_multi_") })
    }

    @Test
    fun `online import result is persisted with its source`() {
        val manager = EmbeddedContentManager(
            session = EmbeddedConfigurationSession(MemoryStorage()),
            fileIdFactory = sequenceFileIds("lyrics_online"),
        )

        val result = manager.importOnlineLyrics(
            appleMusicId = 303L,
            displayName = "Online",
            imported = CustomLyricsOnlineImportResult.Imported(
                ttml = ttml("online"),
                source = CustomLyricsSources.AMLL,
            ),
        )

        assertTrue(result is CustomLyricsSaveResult.Saved)
        val entry = (result as CustomLyricsSaveResult.Saved).entry
        assertEquals(CustomLyricsSources.AMLL, entry.source)
        assertEquals(ttml("online"), manager.readLyrics(303L))
    }

    @Test
    fun `backup and restore round trip lyric files with fresh file ids`() {
        val sourceStorage = MemoryStorage()
        val source = EmbeddedContentManager(
            session = EmbeddedConfigurationSession(sourceStorage),
            fileIdFactory = sequenceFileIds("lyrics_source"),
        )
        source.addLyrics(401L, "One", ttml("one"))
        source.addLyrics(402L, "Two", ttml("two"))

        val backup = ByteArrayOutputStream()
        val backupResult = source.backupLyrics(backup)

        assertEquals(CustomLyricsBackupEncodeResult.Encoded(2), backupResult)

        val restoredStorage = MemoryStorage()
        val restored = EmbeddedContentManager(
            session = EmbeddedConfigurationSession(restoredStorage),
            fileIdFactory = sequenceFileIds("lyrics_restored"),
        )
        val restoreResult = restored.restoreLyrics(ByteArrayInputStream(backup.toByteArray()))

        assertTrue(restoreResult is CustomLyricsRestoreResult.Restored)
        assertEquals(listOf(401L, 402L), restored.listLyrics().map(CustomLyricsEntry::appleMusicId))
        assertEquals(ttml("one"), restored.readLyrics(401L))
        assertEquals(ttml("two"), restored.readLyrics(402L))
        assertTrue(restored.listLyrics().all { it.fileId.startsWith("lyrics_restored_") })
        assertTrue(source.listLyrics().none { it.fileId in restoredStorage.files.keys })
        assertTrue(restoredStorage.events.indexOf("publish-index") > restoredStorage.events.indexOf("write:lyrics_restored_1"))
    }

    @Test
    fun `keep existing restore preserves a conflicting current lyric`() {
        val backupSource = EmbeddedContentManager(
            EmbeddedConfigurationSession(MemoryStorage()),
            fileIdFactory = sequenceFileIds("backup"),
        )
        backupSource.addLyrics(501L, "Backup", ttml("backup"))
        val backup = ByteArrayOutputStream()
        backupSource.backupLyrics(backup)

        val target = EmbeddedContentManager(
            EmbeddedConfigurationSession(MemoryStorage()),
            fileIdFactory = sequenceFileIds("target"),
        )
        target.addLyrics(501L, "Current", ttml("current"))

        val result = target.restoreLyrics(
            ByteArrayInputStream(backup.toByteArray()),
            CustomLyricsRestorePolicy.KEEP_EXISTING,
        )

        assertTrue(result is CustomLyricsRestoreResult.Restored)
        assertEquals(ttml("current"), target.readLyrics(501L))
        assertEquals("Current", target.listLyrics().single().displayName)
    }

    private fun validFontBytes(marker: Int): ByteArray = byteArrayOf(0, 1, 0, 0, marker.toByte())

    private fun ttml(text: String): String =
        "<tt><body><p><span>$text</span></p></body></tt>"

    private fun sequenceFileIds(prefix: String): (String) -> String {
        var sequence = 0
        return {
            sequence += 1
            "${prefix}_$sequence"
        }
    }

    private class MemoryStorage : EmbeddedConfigurationStorage {
        val storedValues = linkedMapOf<String, Any>()
        val files = linkedMapOf<String, ByteArray>()
        val events = mutableListOf<String>()
        var failFontPublish = false

        override fun values(): Map<String, *> = storedValues.toMap()

        override fun openFile(name: String): InputStream? =
            files[name]?.copyOf()?.let(::ByteArrayInputStream)

        override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
            val isFontPublish = values.containsKey("lyrics_font_file_id")
            val isIndexPublish = values.containsKey("custom_lyrics_index_file_id")
            events += when {
                isFontPublish -> "publish-font"
                isIndexPublish -> "publish-index"
                else -> "values"
            }
            if (isFontPublish && failFontPublish) return false
            storedValues.putAll(values)
            return true
        }

        override fun writeFile(name: String, bytes: ByteArray): Boolean {
            events += "write:$name"
            files[name] = bytes.copyOf()
            return true
        }

        override fun deleteFile(name: String): Boolean {
            events += "delete:$name"
            return files.remove(name) != null
        }
    }
}
