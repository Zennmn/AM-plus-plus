/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/** Small, platform-free policy for retrying a transient empty fixed-region lookup. */
internal object ConfiguredMetadataRetryPolicy {
    const val RETRY_AFTER_MILLIS = 10_000L

    fun shouldSkip(
        lastMissUptimeMillis: Long,
        nowUptimeMillis: Long,
        retryAfterMillis: Long = RETRY_AFTER_MILLIS,
    ): Boolean {
        if (retryAfterMillis <= 0L) return false
        val elapsed = nowUptimeMillis - lastMissUptimeMillis
        // A monotonic clock should not move backwards; if a caller supplies a regressed value,
        // treat the old miss as expired rather than blocking the request indefinitely.
        return elapsed in 0 until retryAfterMillis
    }
}
