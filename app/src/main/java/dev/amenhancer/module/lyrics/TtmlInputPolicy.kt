package dev.amenhancer.module.lyrics

import java.util.Locale

/**
 * Acceptance policy for third-party TTML before it ever reaches Apple's
 * native parser. All limits are structural: UTF-8 byte size, required root
 * markers, and bounded counts of line/word tags. Anything violating the
 * policy is rejected and the original lyrics stay in place.
 */
object TtmlInputPolicy {

    const val MAX_TTML_BYTES = 512 * 1024
    const val MAX_LINES = 4096
    const val MAX_WORDS = 65536

    fun isAcceptable(ttml: String): Boolean {
        if (ttml.isEmpty()) return false
        if (ttml.toByteArray(Charsets.UTF_8).size > MAX_TTML_BYTES) return false
        val lower = ttml.lowercase(Locale.ROOT)
        if (!lower.contains("<tt") || !lower.contains("<body")) return false
        if (countTag(lower, "<p") > MAX_LINES) return false
        if (countTag(lower, "<span") > MAX_WORDS) return false
        return true
    }

    private fun countTag(text: String, tag: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(tag, index)
            if (index < 0) break
            val next = index + tag.length
            if (next >= text.length || text[next] == ' ' || text[next] == '>' ||
                text[next] == '\t' || text[next] == '\n'
            ) {
                count += 1
            }
            index = next
        }
        return count
    }
}
