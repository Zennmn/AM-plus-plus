package dev.amenhancer.module.share

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File

/** One private draft. A different song never consumes or silently replaces it. */
internal class ShareDraftStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "amplusplus-share-draft-v1.json"))
    fun save(draft: ShareDraft) {
        synchronized(lock) {
            val bytes = ShareDraftCodec.encode(draft).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32 * 1024)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
    }
    fun load(url: String): ShareDraft? {
        synchronized(lock) {
            val raw = try {
                file.openRead().use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (output.size() <= 32 * 1024) {
                        val n = input.read(buffer, 0, minOf(buffer.size, 32 * 1024 + 1 - output.size()))
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    require(output.size() <= 32 * 1024)
                    output.toString("UTF-8")
                }
            } catch (_: java.io.FileNotFoundException) { return null }
              catch (_: Exception) { clear(); return null }
            val draft = ShareDraftCodec.decode(raw, clock()) ?: run { clear(); return null }
            return draft.takeIf { it.url == url }
        }
    }
    fun clear() { synchronized(lock) { file.delete() } }
    companion object {
        // Activity replacement can leave an older controller's queued write in flight.
        private val lock = Any()
        internal val io = java.util.concurrent.Executors.newSingleThreadExecutor {
            Thread(it, "AMPlusPlus-share-draft").apply { isDaemon = true }
        }
    }
}
