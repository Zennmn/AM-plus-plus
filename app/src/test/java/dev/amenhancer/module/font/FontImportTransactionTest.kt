package dev.amenhancer.module.font

import dev.amenhancer.module.model.LyricsFontManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontImportTransactionTest {
    private val validBytes = byteArrayOf(0, 1, 0, 0, 1, 2, 3)

    @Test
    fun `writes the remote file before publishing its manifest`() {
        val events = mutableListOf<String>()
        var published: LyricsFontManifest? = null
        val transaction = transaction(
            events = events,
            publish = { manifest ->
                events += "publish"
                published = manifest
                true
            },
        )

        val result = transaction.import("Noto Sans.ttf", validBytes)

        assertTrue(result is FontImportResult.Imported)
        assertEquals(listOf("write", "publish"), events)
        assertEquals(published, (result as FontImportResult.Imported).manifest)
        assertEquals(7L, published?.sizeBytes)
        assertEquals(
            "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53",
            published?.sha256,
        )
    }

    @Test
    fun `validation failure does not write or replace the old manifest`() {
        val events = mutableListOf<String>()
        val transaction = transaction(events = events)

        val result = transaction.import("broken.ttf", byteArrayOf(1, 2, 3, 4))

        assertEquals(FontImportResult.Failed("Unsupported SFNT font signature"), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a failed manifest publication removes only the new orphan file`() {
        val events = mutableListOf<String>()
        val transaction = transaction(
            events = events,
            publish = {
                events += "publish"
                false
            },
        )

        val result = transaction.import("Noto Sans.ttf", validBytes)

        assertEquals(FontImportResult.Failed("Unable to publish font configuration"), result)
        assertEquals(listOf("write", "publish", "delete"), events)
    }

    @Test
    fun `a typeface parser failure leaves the old remote manifest untouched`() {
        val events = mutableListOf<String>()
        val transaction = FontImportTransaction(
            fileIdFactory = { "font_abc123" },
            writeRemoteFile = { _, _ -> events += "write"; true },
            publishManifest = { events += "publish"; true },
            deleteRemoteFile = { events += "delete" },
            validateTypeface = { false },
        )

        val result = transaction.import("Noto Sans.ttf", validBytes)

        assertEquals(
            FontImportResult.Failed("Android Typeface.Builder could not parse the font"),
            result,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an invalid generated file id fails closed`() {
        val transaction = FontImportTransaction(
            fileIdFactory = { "font.with.dot" },
            writeRemoteFile = { _, _ -> error("must not write") },
            publishManifest = { error("must not publish") },
            deleteRemoteFile = { error("must not delete") },
        )

        val result = transaction.import("Noto Sans.ttf", validBytes)

        assertEquals(FontImportResult.Failed("Generated remote file id was invalid"), result)
    }

    private fun transaction(
        events: MutableList<String>,
        publish: (LyricsFontManifest) -> Boolean = { true },
    ): FontImportTransaction = FontImportTransaction(
        fileIdFactory = { "font_abc123" },
        writeRemoteFile = { _, _ -> events += "write"; true },
        publishManifest = publish,
        deleteRemoteFile = { events += "delete" },
    )
}
