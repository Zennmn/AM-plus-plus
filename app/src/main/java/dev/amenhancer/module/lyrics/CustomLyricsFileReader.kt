package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.CustomLyricsEntry

/** Verifies a remote TTML file against its manifest before native parsing. */
internal class CustomLyricsFileReader(
    private val readBytes: (String) -> ByteArray?,
) {
    fun read(entry: CustomLyricsEntry): String? {
        val bytes = runCatching { readBytes(entry.fileId) }.getOrNull() ?: return null
        if (bytes.size.toLong() != entry.sizeBytes) return null
        if (!CustomLyricsFilePolicy.sha256(bytes).equals(entry.sha256, ignoreCase = true)) return null
        val ttml = bytes.toString(Charsets.UTF_8)
        return ttml.takeIf { CustomLyricsFilePolicy.inspect(it) is CustomLyricsInspection.Accepted }
    }
}
