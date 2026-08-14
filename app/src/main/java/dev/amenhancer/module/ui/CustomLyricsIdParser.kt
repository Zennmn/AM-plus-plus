package dev.amenhancer.module.ui

/** Parses the comma-separated Apple Music ID field used by the lyrics editor. */
internal object CustomLyricsIdParser {
    fun parse(value: String): List<Long>? {
        val tokens = value.split(',')
        if (tokens.any { it.trim().isEmpty() }) return null
        val ids = tokens.map {
            it.trim().toLongOrNull()?.takeIf { id -> id > 0L } ?: return null
        }
        return ids.takeIf { it.distinct().size == it.size }
    }

    fun parsePrimary(value: String): Long? = parse(value)?.firstOrNull()

    fun format(ids: List<Long>): String = ids.joinToString(",")
}
