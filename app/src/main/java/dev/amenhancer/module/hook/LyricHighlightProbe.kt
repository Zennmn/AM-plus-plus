package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log

/**
 * A deliberately small, opt-in diagnostic sink for lyric highlight events.
 *
 * The probe is disabled unless the process was started with
 * `log.tag.AMPP-LYRIC-PROBE=DEBUG`.  Nothing is persisted and the probe never
 * receives lyric text; it only receives native arguments and line identities.
 * Every failure in the gate, clock, formatter, or sink is swallowed so that a
 * diagnostic problem cannot affect the blur path.
 */
internal class LyricHighlightProbe(
    private val sink: LyricHighlightProbeSink = AndroidLogLyricHighlightProbeSink,
    private val enabled: () -> Boolean = DEFAULT_ENABLED,
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() },
    private val maxEvents: Int = MAX_EVENTS,
) {
    private val lock = Any()
    private var emittedEvents = 0
    private var lastBlurKey: String? = null

    /** Sources are intentionally a closed, text-only vocabulary. */
    object Source {
        const val NATIVE = "native"
        const val VM4 = "vm4"
        const val VM1 = "vm1"
        const val SYNTHETIC_INSTALL = "synthetic-install"
        const val SESSION = "session"
        const val SESSION_UPDATE = "session-update"
        const val WORD = "word"
        const val BG_WORD = "bg-word"
        const val PR_WORD = "pr-word"
        const val PR_BG_WORD = "pr-bg-word"
        // A blur frame has no callback source; it is a coalesced renderer
        // observation and is kept distinct from callback events.
        const val BLUR = "blur"
    }

    /** Records a native `call(Long, vector, Long)` event. */
    fun recordNative(
        firstNative: Long?,
        lineIds: Collection<Int>,
        lastNative: Long?,
        rawLineIds: Collection<Int>? = null,
    ) {
        record(
            source = Source.NATIVE,
            nativeFirst = firstNative,
            lineIds = lineIds,
            rawLineIds = rawLineIds ?: lineIds,
            nativeLast = lastNative,
        )
    }

    /** Records one of Apple's four word-level callback vectors. */
    fun recordWord(
        source: String,
        firstNative: Long?,
        wordKeys: Collection<String>,
        lastNative: Long?,
    ) {
        record(
            source = source,
            nativeFirst = firstNative,
            nativeLast = lastNative,
            wordKeys = wordKeys,
        )
    }

    /** Records a processEvents boundary without dereferencing native objects. */
    fun recordSession(token: Any?, processPosition: Long?) {
        synchronized(lock) { lastBlurKey = null }
        record(
            source = Source.SESSION,
            sessionId = token?.let(System::identityHashCode),
            processPosition = processPosition,
        )
    }

    /** Records the exact callback set before and after LyricHighlightSession. */
    fun recordSessionUpdate(
        incomingIds: Collection<Int>,
        activeIds: Collection<Int>,
        gap: Boolean,
        opening: Boolean,
    ) {
        record(
            source = Source.SESSION_UPDATE,
            lineIds = incomingIds,
            blurActive = activeIds,
            sessionGap = gap,
            sessionOpening = opening,
        )
    }

    /** Records the four-argument ViewModel fallback. */
    fun recordVm4(lineId: Int) {
        record(source = Source.VM4, lineIds = listOf(lineId))
    }

    /** Records the single-argument ViewModel fallback. */
    fun recordVm1(lineId: Int) {
        record(source = Source.VM1, lineIds = listOf(lineId))
    }

    /** Records the empty callback snapshot emitted when the native hook installs. */
    fun recordSyntheticInstall() {
        synchronized(lock) { lastBlurKey = null }
        record(source = Source.SYNTHETIC_INSTALL)
    }

    /**
     * Records the coalesced state used by one blur frame.  [visibleIds] are
     * adapter positions (not text), and are intentionally summarized just like
     * highlight IDs.
     */
    fun recordBlurFrame(
        activeIds: Collection<Int>,
        effectiveIds: Collection<Int>,
        visibleIds: Collection<Int>,
        includeFocus: Boolean = true,
        immediate: Boolean = false,
    ) {
        if (runCatching { enabled() }.getOrDefault(false).not()) return
        val blurKey = buildBlurKey(activeIds, effectiveIds, visibleIds, includeFocus, immediate)
        synchronized(lock) {
            if (blurKey == lastBlurKey) return
            lastBlurKey = blurKey
        }
        record(
            source = Source.BLUR,
            blurActive = activeIds,
            blurEffective = effectiveIds,
            blurVisible = visibleIds,
            blurIncludeFocus = includeFocus,
            blurImmediate = immediate,
        )
    }

    /** Keep one frame per visible/active state, never one line per Choreographer tick. */
    private fun buildBlurKey(
        activeIds: Collection<Int>,
        effectiveIds: Collection<Int>,
        visibleIds: Collection<Int>,
        includeFocus: Boolean,
        immediate: Boolean,
    ): String {
        fun ids(values: Collection<Int>): String = values.asSequence()
            .distinct()
            .sorted()
            .joinToString(",")
        val visible = visibleIds.asSequence().sorted().toList()
        return buildString {
            append(ids(activeIds)).append('|')
            append(ids(effectiveIds)).append('|')
            append(visible.size).append(':')
            append(visible.firstOrNull() ?: "null").append(':')
            append(visible.lastOrNull() ?: "null").append('|')
            append(includeFocus).append('|').append(immediate)
        }
    }

    private fun record(
        source: String,
        nativeFirst: Long? = null,
        lineIds: Collection<Int> = emptyList(),
        rawLineIds: Collection<Int>? = null,
        wordKeys: Collection<String>? = null,
        nativeLast: Long? = null,
        sessionId: Int? = null,
        processPosition: Long? = null,
        blurActive: Collection<Int>? = null,
        blurEffective: Collection<Int>? = null,
        blurVisible: Collection<Int>? = null,
        blurIncludeFocus: Boolean? = null,
        blurImmediate: Boolean? = null,
        sessionGap: Boolean? = null,
        sessionOpening: Boolean? = null,
    ) {
        // Check the explicit device gate before doing any work.  In particular,
        // the default path does not even call uptimeMillis or allocate a line.
        val shouldLog = runCatching { enabled() }.getOrDefault(false)
        if (!shouldLog) return

        val now = runCatching { uptimeMillis() }.getOrDefault(0L)
        synchronized(lock) {
            if (emittedEvents >= maxEvents.coerceAtLeast(0)) return
            emittedEvents += 1
        }
        val line = runCatching {
            format(
                source = source,
                nativeFirst = nativeFirst,
                lineIds = lineIds,
                rawLineIds = rawLineIds,
                wordKeys = wordKeys,
                nativeLast = nativeLast,
                sessionId = sessionId,
                processPosition = processPosition,
                blurActive = blurActive,
                blurEffective = blurEffective,
                blurVisible = blurVisible,
                blurIncludeFocus = blurIncludeFocus,
                blurImmediate = blurImmediate,
                sessionGap = sessionGap,
                sessionOpening = sessionOpening,
                uptimeMillis = now,
            )
        }.getOrNull() ?: return
        // A test sink or Android's logger may fail (for example while a process
        // is tearing down); diagnostics are always fail-open.
        runCatching { sink.write(line) }
    }

    companion object {
        /** Android log tag.  The emitted line is prefixed with brackets. */
        const val TAG = "AMPP-LYRIC-PROBE"
        const val PREFIX = "[$TAG]"

        /** Keep both events and ID lists bounded on a noisy callback path. */
        const val MAX_EVENTS = 128
        const val MAX_IDS = 32

        /** Pure formatter seam used by JVM tests; it never touches Android APIs. */
        fun format(
            source: String,
            nativeFirst: Long? = null,
            lineIds: Collection<Int> = emptyList(),
            nativeLast: Long? = null,
            rawLineIds: Collection<Int>? = null,
            wordKeys: Collection<String>? = null,
            sessionId: Int? = null,
            processPosition: Long? = null,
            blurActive: Collection<Int>? = null,
            blurEffective: Collection<Int>? = null,
            blurVisible: Collection<Int>? = null,
            blurIncludeFocus: Boolean? = null,
            blurImmediate: Boolean? = null,
            sessionGap: Boolean? = null,
            sessionOpening: Boolean? = null,
            uptimeMillis: Long = 0L,
        ): String {
            val lineSummary = summarize(lineIds)
            val rawLineSummary = rawLineIds?.let(::summarize)
            val wordSummary = wordKeys?.let(::summarizeStrings)
            val activeSummary = summarizeNullable(blurActive)
            val effectiveSummary = summarizeNullable(blurEffective)
            val visibleSummary = summarizeNullable(blurVisible)
            return buildString(192) {
                append(PREFIX)
                append(" source=").append(source)
                append(" native0=").append(nativeFirst ?: "null")
                append(" native2=").append(nativeLast ?: "null")
                append(" sessionId=").append(sessionId ?: "null")
                append(" processPosition=").append(processPosition ?: "null")
                append(" rawLineIds=").append(rawLineSummary?.ids ?: "null")
                append(" rawLineCount=").append(rawLineSummary?.count ?: "null")
                append(" wordKeys=").append(wordSummary?.ids ?: "null")
                append(" wordCount=").append(wordSummary?.count ?: "null")
                append(" effectiveLineIds=").append(lineSummary.ids)
                append(" lineIds=").append(lineSummary.ids)
                append(" lineCount=").append(lineSummary.count)
                append(" blurActive=").append(activeSummary)
                append(" blurEffective=").append(effectiveSummary)
                append(" blurVisible=").append(visibleSummary)
                append(" blurIncludeFocus=").append(blurIncludeFocus ?: "null")
                append(" blurImmediate=").append(blurImmediate ?: "null")
                append(" sessionGap=").append(sessionGap ?: "null")
                append(" sessionOpening=").append(sessionOpening ?: "null")
                append(" uptime=").append(uptimeMillis)
            }
        }

        private fun summarizeNullable(values: Collection<Int>?): String =
            values?.let(::summarize)?.toString() ?: "null"

        private fun summarize(values: Collection<Int>): IdSummary {
            // Sorting before truncating makes the emitted prefix deterministic,
            // even if a native vector's iteration order changes between builds.
            val sorted = values.asSequence().distinct().sorted().toList()
            return IdSummary(
                ids = sorted.take(MAX_IDS),
                count = sorted.size,
            )
        }

        private fun summarizeStrings(values: Collection<String>): StringSummary {
            val sorted = values.asSequence().distinct().sorted().toList()
            return StringSummary(
                ids = sorted.take(MAX_IDS),
                count = sorted.size,
            )
        }

        private data class IdSummary(
            val ids: List<Int>,
            val count: Int,
        ) {
            override fun toString(): String = "{ids=$ids,count=$count}"
        }

        private data class StringSummary(
            val ids: List<String>,
            val count: Int,
        )

        private val DEFAULT_ENABLED: () -> Boolean = {
            // Diagnostics are opt-in through the Android log tag.  Keeping
            // this gate here means normal lyric playback does not allocate,
            // clock, or emit anything from the probe hot path.
            Log.isLoggable(TAG, Log.DEBUG)
        }
    }
}

/** A tiny seam for tests and for hosts that want to collect lines elsewhere. */
internal fun interface LyricHighlightProbeSink {
    fun write(line: String)
}

/** Explicit no-op sink for callers that do not want any diagnostic output. */
internal object NoOpLyricHighlightProbeSink : LyricHighlightProbeSink {
    override fun write(line: String) = Unit
}

private object AndroidLogLyricHighlightProbeSink : LyricHighlightProbeSink {
    override fun write(line: String) {
        Log.d(LyricHighlightProbe.TAG, line)
    }
}
