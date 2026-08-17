package dev.amenhancer.module.config

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedConfigurationMigrationTest {
    @Test
    fun `migrates ordinary settings font index and lyric files exactly once`() {
        val fontBytes = byteArrayOf(0, 1, 2, 3)
        val lyricBytes = "<ttml>歌词</ttml>".toByteArray()
        val entry = CustomLyricsEntry(
            appleMusicId = 42L,
            displayName = "Migrated song",
            fileId = "lyrics_42",
            sizeBytes = lyricBytes.size.toLong(),
            sha256 = sha256(lyricBytes),
            source = CustomLyricsSources.MANUAL,
        )
        val manifest = CustomLyricsManifest(listOf(entry))
        val indexBytes = CustomLyricsManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_1",
            generation = 3L,
            sha256 = sha256(indexBytes),
            sizeBytes = indexBytes.size.toLong(),
        )
        val font = LyricsFontManifest(
            enabled = true,
            fileId = "font_1",
            displayName = "Migrated.ttf",
            sizeBytes = fontBytes.size.toLong(),
            sha256 = sha256(fontBytes),
        )
        val sourceValues = buildMap<String, Any> {
            putAll(ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings(dualPaneEnabled = false)))
            putAll(ModuleSettingsSchema.encodeFontManifest(font))
            putAll(ModuleSettingsSchema.encodeIndexPointer(pointer))
        }
        val sourceFiles = mapOf(
            pointer.fileId to indexBytes,
            font.fileId to fontBytes,
            entry.fileId to lyricBytes,
        )
        val destination = MemoryStorage()

        val result = EmbeddedConfigurationMigration.migrate(
            remoteValues = sourceValues,
            openRemoteFile = { sourceFiles[it]?.let(::ByteArrayInputStream) },
            destination = destination,
        )

        assertEquals(
            EmbeddedConfigurationMigrationResult.Migrated(
                copiedFileIds = listOf("index_1", "font_1", "lyrics_42"),
            ),
            result,
        )
        assertEquals(false, ModuleSettingsSchema.decode(destination.values()).dualPaneEnabled)
        assertEquals(font, ModuleSettingsSchema.decode(destination.values()).fontManifest)
        assertEquals(pointer, ModuleSettingsSchema.decodeIndexPointer(destination.values()))
        assertEquals(
            EmbeddedConfigurationMigration.MIGRATION_COMPLETE,
            destination.values()[EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY],
        )
        assertArrayEquals(indexBytes, destination.openFile(pointer.fileId)?.readBytes())
        assertArrayEquals(fontBytes, destination.openFile(font.fileId)?.readBytes())
        assertArrayEquals(lyricBytes, destination.openFile(entry.fileId)?.readBytes())

        destination.writeValues(
            ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings(dualPaneEnabled = true)),
            synchronous = true,
        )
        val second = EmbeddedConfigurationMigration.migrate(
            remoteValues = emptyMap<String, Any>(),
            openRemoteFile = { error("completed storage must not read remote files") },
            destination = destination,
        )
        assertEquals(EmbeddedConfigurationMigrationResult.SkippedAlreadyComplete, second)
        assertTrue(ModuleSettingsSchema.decode(destination.values()).dualPaneEnabled)
    }

    @Test
    fun `does not overwrite settings or file-only host state`() {
        val source = ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings(dualPaneEnabled = false))
        val configured = MemoryStorage()
        configured.writeValues(
            ModuleSettingsSchema.encodeOrdinarySettings(ModuleSettings(dualPaneEnabled = true)),
            synchronous = true,
        )

        val configuredResult = EmbeddedConfigurationMigration.migrate(
            remoteValues = source,
            openRemoteFile = { error("occupied storage must not read remote files") },
            destination = configured,
        )
        assertEquals(EmbeddedConfigurationMigrationResult.SkippedDestinationOccupied, configuredResult)
        assertTrue(ModuleSettingsSchema.decode(configured.values()).dualPaneEnabled)

        val fileOnly = MemoryStorage(initialFiles = mapOf("user_file" to byteArrayOf(9)))
        val fileOnlyResult = EmbeddedConfigurationMigration.migrate(
            remoteValues = source,
            openRemoteFile = { error("file-only storage must not read remote files") },
            destination = fileOnly,
        )
        assertEquals(EmbeddedConfigurationMigrationResult.SkippedDestinationOccupied, fileOnlyResult)
        assertArrayEquals(byteArrayOf(9), fileOnly.openFile("user_file")?.readBytes())
    }

    @Test
    fun `failed file copy leaves in-progress marker and can be retried`() {
        val body = "<ttml/>".toByteArray()
        val entry = CustomLyricsEntry(
            appleMusicId = 1L,
            displayName = "Retry",
            fileId = "lyrics_retry",
            sizeBytes = body.size.toLong(),
            sha256 = sha256(body),
            source = CustomLyricsSources.MANUAL,
        )
        val manifest = CustomLyricsManifest(listOf(entry))
        val index = CustomLyricsManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_retry",
            generation = 1L,
            sha256 = sha256(index),
            sizeBytes = index.size.toLong(),
        )
        val values = ModuleSettingsSchema.encodeIndexPointer(pointer)
        val files = mapOf(pointer.fileId to index, entry.fileId to body)
        val destination = MemoryStorage(failingFileIds = mutableSetOf(pointer.fileId))

        val failed = EmbeddedConfigurationMigration.migrate(
            remoteValues = values,
            openRemoteFile = { files[it]?.let(::ByteArrayInputStream) },
            destination = destination,
        )
        assertTrue(failed is EmbeddedConfigurationMigrationResult.Failed)
        assertEquals(
            EmbeddedConfigurationMigration.MIGRATION_IN_PROGRESS,
            destination.values()[EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY],
        )

        destination.failingFileIds.clear()
        val retried = EmbeddedConfigurationMigration.migrate(
            remoteValues = values,
            openRemoteFile = { files[it]?.let(::ByteArrayInputStream) },
            destination = destination,
        )
        assertTrue(retried is EmbeddedConfigurationMigrationResult.Migrated)
        assertEquals(
            EmbeddedConfigurationMigration.MIGRATION_COMPLETE,
            destination.values()[EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY],
        )
        assertArrayEquals(index, destination.openFile(pointer.fileId)?.readBytes())
        assertArrayEquals(body, destination.openFile(entry.fileId)?.readBytes())
    }

    @Test
    fun `corrupt remote index fails before touching an empty destination`() {
        val pointer = CustomLyricsIndexPointer(
            fileId = "index_bad",
            generation = 1L,
            sha256 = sha256("expected".toByteArray()),
            sizeBytes = 8L,
        )
        val destination = MemoryStorage()
        val result = EmbeddedConfigurationMigration.migrate(
            remoteValues = ModuleSettingsSchema.encodeIndexPointer(pointer),
            openRemoteFile = { ByteArrayInputStream("tampered".toByteArray()) },
            destination = destination,
        )

        assertTrue(result is EmbeddedConfigurationMigrationResult.Failed)
        assertTrue(destination.values().isEmpty())
        assertTrue(!destination.hasAnyFiles())
    }

    @Test
    fun `legacy manifest and lyric payloads migrate without an index pointer`() {
        val body = "<ttml>legacy</ttml>".toByteArray()
        val entry = CustomLyricsEntry(
            appleMusicId = 7L,
            displayName = "Legacy",
            fileId = "lyrics_legacy",
            sizeBytes = body.size.toLong(),
            sha256 = sha256(body),
            source = CustomLyricsSources.MANUAL,
        )
        val legacy = CustomLyricsManifestCodec.encode(CustomLyricsManifest(listOf(entry)))
        val destination = MemoryStorage()

        val result = EmbeddedConfigurationMigration.migrate(
            remoteValues = mapOf("custom_lyrics_manifest" to legacy),
            openRemoteFile = { name ->
                if (name == "lyrics_legacy") ByteArrayInputStream(body) else null
            },
            destination = destination,
        )

        assertTrue(result is EmbeddedConfigurationMigrationResult.Migrated)
        assertTrue(ModuleSettingsSchema.decodeIndexPointer(destination.values()) == null)
        assertEquals(
            listOf(7L),
            CustomLyricsIndexRepository.resolve(
                destination.values(),
            ) { destination.openFile(it) }.entries.map(CustomLyricsEntry::appleMusicId),
        )
        assertArrayEquals(body, destination.openFile(entry.fileId)?.readBytes())
    }

    @Test
    fun `unknown remote values do not seal an empty host migration`() {
        val destination = MemoryStorage()
        val result = EmbeddedConfigurationMigration.migrate(
            remoteValues = mapOf("unrelated_framework_key" to true),
            openRemoteFile = { error("unknown-only configuration must not open files") },
            destination = destination,
        )

        assertEquals(EmbeddedConfigurationMigrationResult.SkippedNoRemoteConfiguration, result)
        assertTrue(destination.values().isEmpty())
        assertTrue(!destination.hasAnyFiles())
    }

    private class MemoryStorage(
        initialValues: Map<String, Any> = emptyMap(),
        initialFiles: Map<String, ByteArray> = emptyMap(),
        val failingFileIds: MutableSet<String> = mutableSetOf(),
    ) : EmbeddedConfigurationStorage {
        private val storedValues = initialValues.toMutableMap()
        private val files = initialFiles.mapValues { it.value.copyOf() }.toMutableMap()

        override fun values(): Map<String, *> = storedValues.toMap()

        override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
            storedValues.putAll(values)
            return true
        }

        override fun openFile(name: String): InputStream? =
            files[name]?.let { ByteArrayInputStream(it.copyOf()) }

        override fun writeFile(name: String, bytes: ByteArray): Boolean {
            if (name in failingFileIds) return false
            files[name] = bytes.copyOf()
            return true
        }

        override fun deleteFile(name: String): Boolean = files.remove(name) != null

        override fun hasAnyFiles(): Boolean = files.isNotEmpty()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
