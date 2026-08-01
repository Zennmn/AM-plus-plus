package dev.amenhancer.module.font

/**
 * Retry policy for the remote lyrics-font load.
 *
 * Transient open/read failures are retried a bounded number of times with
 * backoff; permanent validation failures (magic/size/hash/Typeface) never
 * retry, because retrying cannot repair corrupted data. Android-free so the
 * decision can be unit-tested directly.
 */
internal object FontLoadRetryPolicy {
    /** Total attempts per process start, including the first one. */
    const val MAX_ATTEMPTS = 3

    const val INITIAL_BACKOFF_MILLIS = 100L

    const val BACKOFF_CAP_MILLIS = 1000L

    /** `attempt` is zero-based; the last attempt never schedules a retry. */
    fun shouldRetry(attempt: Int, transient: Boolean): Boolean =
        transient && attempt < MAX_ATTEMPTS - 1

    /** Exponential backoff, capped so a stuck service cannot stall shutdown. */
    fun backoffMillis(attempt: Int): Long =
        (INITIAL_BACKOFF_MILLIS shl attempt).coerceAtMost(BACKOFF_CAP_MILLIS)
}
