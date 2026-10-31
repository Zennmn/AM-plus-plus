package dev.amenhancer.module.hook

private val CJK_KARAOKE_PUNCTUATION = Regex("\\p{P}")

/** Eligibility-only copy; native word text and display punctuation must remain untouched. */
fun cjkKaraokeTextWithoutPunctuation(text: CharSequence): String =
    CJK_KARAOKE_PUNCTUATION.replace(text, "")

/** Script classification shared by the pure word gate and the host adapter. */
fun containsCjkKaraokeScript(text: CharSequence): Boolean {
    for (index in text.indices) {
        when (Character.UnicodeBlock.of(text[index])) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> return true
        }
    }
    return false
}
