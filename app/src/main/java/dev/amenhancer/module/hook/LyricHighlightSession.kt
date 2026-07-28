package dev.amenhancer.module.hook

/** Keeps highlight continuity inside one native lyric document, never across songs. */
internal class LyricHighlightSession {
    private var token: Any? = null
    private val highlightedLineIds = mutableSetOf<Int>()

    @Synchronized
    fun enter(newToken: Any): Boolean {
        if (token === newToken) return false
        token = newToken
        highlightedLineIds.clear()
        return true
    }

    @Synchronized
    fun update(incoming: Set<Int>): Set<Int> {
        val resolved = BidirectionalBlurPolicy.resolveHighlights(
            current = highlightedLineIds,
            incoming = incoming,
        )
        highlightedLineIds.clear()
        highlightedLineIds.addAll(resolved)
        return highlightedLineIds.toSet()
    }

    @Synchronized
    fun add(lineId: Int) {
        highlightedLineIds.add(lineId)
    }

    @Synchronized
    fun replace(lineId: Int) {
        highlightedLineIds.clear()
        highlightedLineIds.add(lineId)
    }

    @Synchronized
    fun snapshot(): Set<Int> = highlightedLineIds.toSet()
}
