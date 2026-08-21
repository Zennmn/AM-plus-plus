package dev.amenhancer.module.hook

/**
 * The host still owns the actual long-glow thresholds. AM++ only answers the
 * safety question: is this a single, unmerged CJK native word? Returning
 * false is deliberately fail-closed, so a malformed binding keeps Apple's
 * original (non-overridden) classifier result.
 */
internal data class CjkKaraokeWordTiming(
    val text: CharSequence,
    val nativeDurationMs: Int,
    val cumulativeDurationMs: Int,
    val cumulativeTextLength: Int,
    val splitBindingCount: Int,
    val isBackground: Boolean,
)

/**
 * Does not impose Apple's duration/length trigger. Those conditions remain in
 * z.a0; this gate only blocks merged or multi-character CJK chunks.
 */
internal fun isSingleUnmergedCjkWord(timing: CjkKaraokeWordTiming): Boolean {
    if (timing.isBackground) return false
    if (timing.splitBindingCount < 0 || timing.splitBindingCount > 1) return false

    val normalized = timing.text.toString().trim()
    if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) != 1) {
        return false
    }
    if (!containsCjkKaraokeScript(normalized)) return false

    // g0 writes f/g as the visual chunk's accumulated duration/text length.
    // Equality with the current native word's values is the non-merged case.
    return timing.cumulativeDurationMs == timing.nativeDurationMs &&
        timing.cumulativeTextLength == 1
}
