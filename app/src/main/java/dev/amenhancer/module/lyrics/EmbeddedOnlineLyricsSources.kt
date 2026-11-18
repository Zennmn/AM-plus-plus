package dev.amenhancer.module.lyrics

import android.content.Context
import dev.amenhancer.module.config.EmbeddedLyricsSourceStore
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.FileLunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCacheSnapshot
import dev.amenhancer.module.lyrics.source.LunabeatClient
import java.io.File

internal fun createEmbeddedOnlineLyricsImporter(
    catalogCache: LunabeatCatalogCache,
): CustomLyricsOnlineImporter {
    return CustomLyricsOnlineImporter(
        fetchAmll = AmllTtmlClient(HttpLyricTransport())::fetch,
        fetchAmLyrics = AmLyricsClient(HttpLyricTransport())::fetch,
        fetchLunabeat = LunabeatClient(
            indexTransport = HttpLyricTransport(maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES),
            lyricsTransport = HttpLyricTransport(),
            cache = catalogCache,
        )::fetch,
    )
}

/** All catalog consumers share the same file and respect the lyric cache clear generation. */
internal fun embeddedLunabeatCatalogCache(
    context: Context,
    store: EmbeddedLyricsSourceStore,
    writable: Boolean,
): LunabeatCatalogCache {
    val files = FileLunabeatCatalogCache(File(context.filesDir, "ampp-auto-lyrics/lunabeat"))
    val legacyFiles = FileLunabeatCatalogCache(File(context.filesDir, "ampp-lunabeat-cache"))
    val requestGeneration = ThreadLocal<Long>()
    return object : LunabeatCatalogCache {
        override fun read(): LunabeatCatalogCacheSnapshot? {
            requestGeneration.set(store.generation())
            return files.read() ?: legacyFiles.read()
        }

        override fun write(manifestJson: String, indexJson: String, etag: String?): Boolean {
            if (!writable) return false
            val generation = requestGeneration.get() ?: return false
            requestGeneration.remove()
            return store.writeIfCurrent(generation) { files.write(manifestJson, indexJson, etag) }
        }
    }
}

