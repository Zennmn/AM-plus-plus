package dev.amenhancer.module.hook

/** Only this adapter can turn an opaque lyric payload into an Apple Music pointer. */
internal class OpaqueTtmlParser(
    private val native: TtmlNativeParser,
    private val executor: java.util.concurrent.Executor,
    private val onSubmissionReady: (Long) -> Unit,
    private val logger: (String) -> Unit,
) {
    private class Handle(val pointer: Any, var document: String? = null) : NativeLyricsHandle {
        var logicalAdamId: Long? = null
        var boundNativeId: Long? = null
        var submittedNativeId: Long? = null
        var submissionPointer: Any? = null
        var requestedNativeId: Long? = null
        var submissionPending = false
        val submissionPointers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    }
    private val handles = mutableListOf<java.lang.ref.WeakReference<Handle>>()
    private val moduleParse = ThreadLocal<Boolean>()
    // Preparation and independent refresh workers share one native parser.
    @Synchronized fun parse(ttml: String): NativeLyricsHandle? {
        return (wrap(parseNative(ttml)) as? Handle)?.also { it.document = ttml }
    }

    private fun parseNative(ttml: String): Any? {
        moduleParse.set(true)
        return try {
            native.parse(ttml)
        } finally { moduleParse.remove() }
    }

    /** The parser hook must not treat a module-produced replacement as native input. */
    fun isModuleParse(): Boolean = moduleParse.get() == true

    /** Content identity for submission deduplication; never shares mutable pointers across IDs. */
    @Synchronized fun documentOf(payload: Any): String? =
        handleFor(payload)?.document

    @Synchronized fun wrap(pointer: Any?, logicalAdamId: Long? = null): NativeLyricsHandle? {
        val incoming = pointer ?: return null
        handles.removeAll { it.get() == null }
        if (incoming is Handle) {
            return incoming.takeIf { markLogicalId(it, logicalAdamId) }
        }
        if (!native.isAlive(incoming)) return null
        handleFor(incoming)?.let { handle ->
            return handle.takeIf { markLogicalId(it, logicalAdamId) }
        }
        val handle = Handle(incoming)
        if (!markLogicalId(handle, logicalAdamId)) return null
        handles += java.lang.ref.WeakReference(handle)
        return handle
    }

    @Synchronized fun unwrap(payload: Any?): Any? =
        (payload as? Handle)?.let(::currentPointer) ?: payload

    /** Distinguishes the pointer currently eligible for submission from retired documents. */
    @Synchronized fun isCurrentSubmission(pointer: Any): Boolean =
        handleFor(pointer)?.let { handle -> currentPointer(handle) === pointer } == true

    @Synchronized fun isAlive(payload: Any?): Boolean =
        native.isAlive(handleRawPointer(payload))

    @Synchronized fun isValid(payload: Any?): Boolean =
        native.isValid(handleRawPointer(payload))

    @Synchronized fun adamIdOf(payload: Any): Long? {
        if (!isAlive(payload)) return null
        val handle = handleFor(payload) ?: return native.adamIdOf(payload)
        val logical = handle.logicalAdamId ?: return native.adamIdOf(handle.pointer)
        val bound = handle.boundNativeId ?: return null
        val actual = native.adamIdOf(handle.pointer) ?: return null
        if (actual != bound) return null
        if (payload is Handle || payload === handle.pointer) return logical
        return logical.takeIf { payload === handle.submissionPointer &&
            native.adamIdOf(payload) == handle.submittedNativeId }
    }

    @Synchronized fun bindAdamId(payload: Any, id: Long): Boolean {
        if (id <= 0L) return false
        val handle = handleFor(payload)
            ?: (wrap(handleRawPointer(payload)) as? Handle)
            ?: return false
        if (handle.logicalAdamId?.let { it != id } == true) return false
        if (!native.bindAdamId(handle.pointer, id)) return false
        handle.logicalAdamId = id
        handle.boundNativeId = id
        return true
    }

    /**
     * Keeps the cached document's logical identity. A different host item ID
     * gets an independently parsed pointer on the existing preparation worker,
     * so the host installer sees a new object without parsing on the UI thread.
     */
    @Synchronized fun forSubmission(
        payload: Any?,
        logicalAdamId: Long,
        nativeAdamId: Long,
    ): Any? {
        if (logicalAdamId <= 0L || nativeAdamId <= 0L) return null
        val handle = handleFor(payload) ?: return null
        if (handle.logicalAdamId == null) {
            if (native.adamIdOf(handle.pointer) != logicalAdamId || !markLogicalId(handle, logicalAdamId)) return null
        }
        if (handle.logicalAdamId != logicalAdamId || adamIdOf(handle) != logicalAdamId) return null
        handle.requestedNativeId = nativeAdamId
        val current = currentPointer(handle)
        val bound = if (current === handle.pointer) handle.boundNativeId else handle.submittedNativeId
        if (bound == nativeAdamId && native.adamIdOf(current) == bound) return current
        if (handle.boundNativeId == nativeAdamId) {
            handle.submissionPointer = null
            handle.submittedNativeId = null
            return handle.pointer
        }
        val document = handle.document ?: return null
        requestSubmission(handle, logicalAdamId, nativeAdamId, document)
        return null
    }

    /** Called under this adapter's monitor; one worker per handle coalesces ID changes. */
    private fun requestSubmission(handle: Handle, logicalId: Long, nativeId: Long, document: String) {
        if (handle.submissionPending) return
        handle.submissionPending = true
        try {
            executor.execute {
                val replacement = runCatching {
                    synchronized(this) {
                        if (handle.requestedNativeId != nativeId || adamIdOf(handle) != logicalId) return@synchronized null
                        parseNative(document)?.takeIf { native.isValid(it) && native.bindAdamId(it, nativeId) }
                    }
                }.onFailure { logger("lyric native ID preparation failed id=$logicalId nativeId=$nativeId: $it") }.getOrNull()
                val changed = synchronized(this) {
                    handle.submissionPending = false
                    val stale = handle.requestedNativeId != nativeId
                    if (replacement != null && !stale && adamIdOf(handle) == logicalId) {
                        handle.submissionPointers.removeAll { it.get() == null }
                        handle.submissionPointers += java.lang.ref.WeakReference(replacement)
                        handle.submissionPointer = replacement
                        handle.submittedNativeId = nativeId
                    }
                    stale
                }
                // A stale completion only wakes the selector for its latest ID.
                // Failed parsing does not self-schedule an endless retry loop.
                if (replacement != null || changed) onSubmissionReady(logicalId)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            handle.submissionPending = false
            logger("lyric native ID preparation rejected id=$logicalId nativeId=$nativeId")
        }
    }

    private fun handleFor(payload: Any?): Handle? {
        val direct = payload as? Handle
        if (direct != null) return direct
        if (payload == null) return null
        return handles.firstNotNullOfOrNull { reference ->
            reference.get()?.takeIf { handle ->
                handle.pointer === payload || handle.submissionPointers.any { it.get() === payload }
            }
        }
    }

    private fun handleRawPointer(payload: Any?): Any? =
        (payload as? Handle)?.pointer ?: payload

    private fun currentPointer(handle: Handle): Any = handle.submissionPointer ?: handle.pointer

    private fun markLogicalId(handle: Handle, logicalAdamId: Long?): Boolean {
        logicalAdamId ?: return true
        if (logicalAdamId <= 0L) return false
        if (handle.logicalAdamId != null) return adamIdOf(handle) == logicalAdamId
        val actual = native.adamIdOf(handle.pointer) ?: return false
        handle.logicalAdamId = logicalAdamId
        handle.boundNativeId = actual
        return true
    }
}
