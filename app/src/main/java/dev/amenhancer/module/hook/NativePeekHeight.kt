package dev.amenhancer.module.hook

/** Separates host requests from our own reentrant calls through the hooked setter. */
internal class NativePeekHeight {
    var latest: Int? = null
        private set
    private var moduleWrites = 0

    fun observe(height: Int) {
        if (moduleWrites == 0) latest = height
    }

    fun initialize(fallback: Int) {
        if (latest == null) latest = fallback
    }

    fun <T> writeByModule(write: () -> T): T {
        moduleWrites++
        return try { write() } finally { moduleWrites-- }
    }
}
