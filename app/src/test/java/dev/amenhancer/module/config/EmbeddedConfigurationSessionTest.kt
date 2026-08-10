package dev.amenhancer.module.config

import dev.amenhancer.module.font.FontImportResult
import dev.amenhancer.module.font.FontImportTransaction
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedConfigurationSessionTest {
    @Test
    fun `ordinary settings round trip preserves file-backed metadata`() {
        val font = LyricsFontManifest(
            enabled = true,
            fileId = "font_existing",
            displayName = "Existing.ttf",
            sizeBytes = 4L,
            sha256 = SHA256,
        )
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_existing",
            generation = 7L,
            sha256 = SHA256,
            sizeBytes = 128L,
        )
        val storage = InMemoryEmbeddedStorage(
            initialValues = buildMap {
                putAll(ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings()))
                putAll(ModuleSettingsSchema.encodeFontManifest(font))
                putAll(ModuleSettingsSchema.encodeIndexPointer(pointer))
            },
        )
        val session = EmbeddedConfigurationSession(storage)

        assertTrue(session.saveSettings(session.settings().copy(dualPaneEnabled = false)))
        val target = TargetConfigClient(session)

        assertFalse(target.settings().dualPaneEnabled)
        assertEquals(font, target.settings().fontManifest)
        assertEquals(pointer, ModuleSettingsSchema.decodeIndexPointer(storage.values()))
    }

    @Test
    fun `font transaction publishes a file readable by target features`() {
        val storage = InMemoryEmbeddedStorage()
        val session = EmbeddedConfigurationSession(storage)
        val bytes = byteArrayOf(0, 1, 0, 0, 1, 2, 3)
        val result = FontImportTransaction(
            fileIdFactory = { "font_embedded" },
            writeRemoteFile = session::writeFile,
            publishManifest = session::saveFontManifest,
            deleteRemoteFile = { session.deleteFile(it) },
            validateTypeface = { true },
        ).import("Embedded.ttf", bytes)

        assertTrue(result is FontImportResult.Imported)
        val imported = result as FontImportResult.Imported
        val target = TargetConfigClient(session)
        assertEquals(imported.manifest, target.settings().fontManifest)
        assertArrayEquals(bytes, target.openFile(imported.manifest.fileId)?.readBytes())
    }

    @Test
    fun `custom lyrics index commit is readable through the target client`() {
        val storage = InMemoryEmbeddedStorage()
        val session = EmbeddedConfigurationSession(
            storage = storage,
            newIndexFileId = { "index_embedded" },
        )
        val manifest = CustomLyricsManifest(
            listOf(
                CustomLyricsEntry(
                    appleMusicId = 42L,
                    displayName = "Embedded song",
                    fileId = "lyrics_embedded",
                    sizeBytes = 7L,
                    sha256 = SHA256,
                    source = CustomLyricsSources.MANUAL,
                ),
            ),
        )

        val result = session.commitCustomLyrics(manifest)

        assertTrue(result is CustomLyricsIndexCommitResult.Committed)
        assertEquals(
            listOf(42L),
            TargetConfigClient(session).customLyricsManifest().entries.map { it.appleMusicId },
        )
    }

    private class InMemoryEmbeddedStorage(
        initialValues: Map<String, Any> = emptyMap(),
    ) : EmbeddedConfigurationStorage {
        private val storedValues = initialValues.toMutableMap()
        private val files = mutableMapOf<String, ByteArray>()

        override fun values(): Map<String, *> = storedValues.toMap()

        override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
            storedValues.putAll(values)
            return true
        }

        override fun openFile(name: String): InputStream? =
            files[name]?.let(::ByteArrayInputStream)

        override fun writeFile(name: String, bytes: ByteArray): Boolean {
            files[name] = bytes.copyOf()
            return true
        }

        override fun deleteFile(name: String): Boolean = files.remove(name) != null
    }

    private companion object {
        const val SHA256 = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"
    }
}
