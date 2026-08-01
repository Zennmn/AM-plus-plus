package dev.amenhancer.module.font

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal sealed interface FontInspection {
    data class Accepted(
        val sizeBytes: Long,
        val sha256: String,
    ) : FontInspection

    data class Rejected(val message: String) : FontInspection
}

/** Validates only portable file facts; Android Typeface parsing stays at the Android seams. */
internal object FontFilePolicy {
    const val MAX_FONT_SIZE_BYTES = 16L * 1024L * 1024L

    private val ttfMagic = byteArrayOf(0, 1, 0, 0)
    private val ottoMagic = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte())
    private val ttcMagic = byteArrayOf('t'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte())

    fun inspect(bytes: ByteArray): FontInspection {
        if (bytes.size.toLong() > MAX_FONT_SIZE_BYTES) {
            return FontInspection.Rejected("Font exceeds 16 MiB")
        }
        if (!hasSupportedSfntMagic(bytes)) {
            return if (hasMagic(bytes, ttcMagic)) {
                FontInspection.Rejected("TTC font collections are not supported")
            } else {
                FontInspection.Rejected("Unsupported SFNT font signature")
            }
        }

        return FontInspection.Accepted(
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FONT_SIZE_BYTES) {
                throw FontSizeLimitExceeded()
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun hasSupportedSfntMagic(header: ByteArray): Boolean =
        hasMagic(header, ttfMagic) || hasMagic(header, ottoMagic)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { index -> bytes[index] == magic[index] }

    class FontSizeLimitExceeded : Exception()
}
