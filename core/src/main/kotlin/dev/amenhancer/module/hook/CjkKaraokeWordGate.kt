package dev.amenhancer.module.hook

/**
 * Native timing and visual grouping metadata shared by the single-character
 * gate and the profile-enabled short-span gate. A rejected or malformed binding
 * keeps Apple's original (non-overridden) classifier result.
 */
data class CjkKaraokeWordTiming(
    val text: CharSequence,
    val nativeDurationMs: Int,
    val cumulativeDurationMs: Int,
    val cumulativeTextLength: Int,
    val splitBindingCount: Int,
    val isBackground: Boolean,
)

/**
 * Does not impose Apple's duration/length trigger. Those conditions remain in
 * z.a0; this gate only blocks merged or multi-character CJK chunks. Unicode
 * punctuation does not count as a CJK character, but stays in the native length.
 */
fun isSingleUnmergedCjkWord(timing: CjkKaraokeWordTiming): Boolean {
    if (timing.isBackground) return false

    val normalized = timing.text.toString().trim()
    val nativeCharacterCount = normalized.codePointCount(0, normalized.length)
    if (timing.splitBindingCount < 0 || timing.splitBindingCount > nativeCharacterCount) return false
    val cjkText = cjkKaraokeTextWithoutPunctuation(normalized)
    if (cjkText.codePointCount(0, cjkText.length) != 1) {
        return false
    }
    if (!containsCjkKaraokeScript(cjkText)) return false

    // g0 writes f/g as the visual chunk's accumulated duration/text length.
    // Equality with the current native word's values is the non-merged case.
    return timing.cumulativeDurationMs == timing.nativeDurationMs &&
        timing.cumulativeTextLength == normalized.length
}

/**
 * Allows a short unmerged foreground CJK span whose native duration averages
 * at least 1000 ms per CJK character, ignoring punctuation in that count.
 * The host still owns the animation gates and the original word length.
 */
fun isUnmergedCjkSpan(timing: CjkKaraokeWordTiming, maxCharacters: Int): Boolean {
    if (timing.isBackground || maxCharacters < 2) return false

    val normalized = timing.text.toString().trim()
    val cjkText = cjkKaraokeTextWithoutPunctuation(normalized)
    val characterCount = cjkText.codePointCount(0, cjkText.length)
    if (characterCount !in 2..minOf(maxCharacters, 3)) return false

    var offset = 0
    repeat(characterCount) {
        val next = cjkText.offsetByCodePoints(offset, 1)
        if (!containsCjkKaraokeScript(cjkText.substring(offset, next))) return false
        offset = next
    }

    // Native bindings may include punctuation views; bound them by the unchanged word.
    val nativeCharacterCount = normalized.codePointCount(0, normalized.length)
    if (timing.splitBindingCount < 0 || timing.splitBindingCount > nativeCharacterCount) return false
    return timing.nativeDurationMs >= characterCount * 1_000 &&
        timing.cumulativeDurationMs == timing.nativeDurationMs &&
        timing.cumulativeTextLength == normalized.length
}
