package dev.amenhancer.module.hook

import android.util.Log
import java.io.File

/**
 * Diagnostics for the liquid-glass hooks and the shared hook guard. Every line is prefixed
 * `glass-diag:` and goes to the LSPosed module log, logcat (tag `AMPP-glass`) and, in the Apple
 * Music process, `files/ampp-glass-diag.log` (capped, newest lines kept).
 */
object GlassDiagLog {
    const val PREFIX = "glass-diag:"
    private const val TAG = "AMPP-glass"
    private const val MAX_BYTES = 192 * 1024

    @Volatile private var file: File? = null

    fun bindFile(dir: File?) {
        file = dir?.let { File(it, "ampp-glass-diag.log") }
    }

    fun i(message: String) = emit(message, null)

    fun e(message: String, error: Throwable) = emit(message, error)

    private fun emit(message: String, error: Throwable?) {
        val line = "$PREFIX $message"
        runCatching { ModernXposedRuntime.log(line, error) }
        runCatching { if (error == null) Log.i(TAG, line) else Log.e(TAG, line, error) }
        val target = file ?: return
        runCatching {
            synchronized(this) {
                if (target.length() > MAX_BYTES) {
                    target.writeText(target.readText().takeLast(MAX_BYTES / 2))
                }
                target.appendText(
                    "${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} " +
                        "pid=${android.os.Process.myPid()} $line" +
                        (error?.let { " :: ${Log.getStackTraceString(it)}" } ?: "") + "\n",
                )
            }
        }
    }
}
