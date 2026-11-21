package dev.amenhancer.module.share

import dev.amenhancer.module.MomentsSongMetadata
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

/** Public Apple lookup only; does not access the account or change the destination link. */
internal class AppleSongMetadataLookup(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun lookup(sharedUrl: String): MomentsSongMetadata? {
        val id = MomentsSongMetadata.songId(sharedUrl) ?: return null
        val region = URI(sharedUrl).path.split('/').getOrNull(1)?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
        var first: MomentsSongMetadata? = null
        // Some Apple Music regions (including CN) have no iTunes lookup results. The ID must
        // still match exactly; this changes only public metadata, never the shared URL/storefront.
        for (country in listOf(region, "us").distinct()) {
            if (Thread.currentThread().isInterrupted) return first
            val candidate = runCatching { lookupCountry(id, country) }.getOrNull() ?: continue
            val selected = first ?: candidate
            first = selected
            if (candidate.artworkUrl != null) return selected.copy(artworkUrl = candidate.artworkUrl)
        }
        return first
    }

    private fun lookupCountry(id: Long, region: String): MomentsSongMetadata? {
        val connection = connect(URL("https://itunes.apple.com/lookup?id=$id&country=$region&entity=song"))
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 256 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 256 * 1024) return null
            val results = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("results") ?: return null
            (0 until results.length()).asSequence().mapNotNull { results.optJSONObject(it) }
                .firstOrNull { it.optLong("trackId") == id && it.optString("kind") == "song" }
                ?.let { row -> MomentsSongMetadata.title(row.optString("trackName"))?.let {
                    MomentsSongMetadata(it, MomentsSongMetadata.clean(row.optString("artistName")),
                        MomentsSongMetadata.artwork(row.optString("artworkUrl100")))
                } }
        } finally { connection.disconnect() }
    }
}
