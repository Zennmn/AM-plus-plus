package dev.amenhancer.module.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal sealed interface CustomLyricsInspection {
    data class Accepted(
        val ttml: String,
        val bytes: ByteArray,
        val sha256: String,
    ) : CustomLyricsInspection

    data class Rejected(val message: String) : CustomLyricsInspection
}

/** Applies the same input bounds to pasted, imported, and target-side TTML. */
internal object CustomLyricsFilePolicy {
    fun inspect(ttml: String): CustomLyricsInspection {
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        if (!TtmlInputPolicy.isAcceptable(ttml)) {
            return CustomLyricsInspection.Rejected("TTML 必须是有效且不超过 512 KiB 的歌词文档")
        }
        return CustomLyricsInspection.Accepted(
            ttml = ttml,
            bytes = bytes,
            sha256 = sha256(bytes),
        )
    }

    fun readBounded(input: InputStream): ByteArray =
        readBounded(input, TtmlInputPolicy.MAX_TTML_BYTES)

    fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                throw SizeLimitExceeded()
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    class SizeLimitExceeded : Exception()
}
