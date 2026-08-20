package dev.amenhancer.module.hook

/**
 * Keeps the latest line IDs reported by each native word callback stream.
 * Apple exposes primary, background, transliteration and transliteration
 * background streams independently, so the blur target is their union.
 */
internal class LyricWordHighlightState {
    private val lock = Any()
    private val lineIdsBySource = mutableMapOf<String, Set<Int>>()

    fun update(source: String, lineIds: Set<Int>): Set<Int> = synchronized(lock) {
        lineIdsBySource[source] = lineIds.toSet()
        lineIdsBySource.values.flatten().toSet()
    }

    fun snapshot(): Set<Int> = synchronized(lock) {
        lineIdsBySource.values.flatten().toSet()
    }

    fun clear() = synchronized(lock) {
        lineIdsBySource.clear()
    }
}
