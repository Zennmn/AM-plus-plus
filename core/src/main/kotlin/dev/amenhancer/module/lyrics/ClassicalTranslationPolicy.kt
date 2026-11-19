package dev.amenhancer.module.lyrics

import java.security.MessageDigest

/** Pure formatting, CJK-detection, and bounded LRU cache helpers for classical guide/note translations. */
object ClassicalTranslationPolicy {
    fun isPredominantlyCjk(text: String): Boolean {
        var letters = 0
        var cjk = 0
        text.forEach { ch ->
            val code = ch.code
            val isHan = code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
            if (isHan) {
                cjk++
                letters++
            } else if (ch.isLetter()) {
                letters++
            }
        }
        return letters > 0 && cjk * 2 >= letters
    }

    fun needsTranslation(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        return trimmed.length >= 8 && !isPredominantlyCjk(trimmed)
    }

    fun formatBilingual(original: String, translated: String): String {
        val cleanOrig = original.trim()
        val cleanTrans = translated.trim()
        if (cleanOrig.isEmpty()) return cleanTrans
        if (cleanTrans.isEmpty() || cleanOrig == cleanTrans || cleanOrig.contains(cleanTrans)) {
            return cleanOrig
        }
        return "$cleanOrig\n$cleanTrans"
    }
}

class ClassicalTranslationCache(private val maxEntries: Int = 128) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(configKey: String, sourceText: String): String? = entries[key(configKey, sourceText)]

    @Synchronized
    fun put(configKey: String, sourceText: String, translated: String) {
        if (translated.isBlank()) return
        entries[key(configKey, sourceText)] = translated.trim()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun key(configKey: String, sourceText: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceText.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
        return "$configKey|$hex"
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
