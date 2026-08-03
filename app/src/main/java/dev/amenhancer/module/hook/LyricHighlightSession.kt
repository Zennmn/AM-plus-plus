package dev.amenhancer.module.hook

/** Keeps highlight continuity inside one native lyric document, never across songs. */
internal class LyricHighlightSession {
    private var token: Any? = null
    private val highlightedLineIds = mutableSetOf<Int>()
    private val completedOverlapLineIds = mutableSetOf<Int>()
    private var gap = false

    @Synchronized
    fun enter(newToken: Any): Boolean {
        if (token === newToken) return false
        token = newToken
        highlightedLineIds.clear()
        completedOverlapLineIds.clear()
        gap = false
        return true
    }

    @Synchronized
    fun update(incoming: Set<Int>): Set<Int> {
        if (incoming.isEmpty()) {
            gap = true
            return snapshotLocked()
        }
        gap = false
        if (incoming == highlightedLineIds) return snapshotLocked()
        val completedOverlap = if (
            highlightedLineIds.size > 1 && highlightedLineIds.containsAll(incoming)
        ) {
            val firstIncoming = incoming.min()
            (highlightedLineIds - incoming).filterTo(mutableSetOf()) { lineId ->
                lineId < firstIncoming
            }
        } else {
            emptySet()
        }
        completedOverlapLineIds.clear()
        completedOverlapLineIds.addAll(completedOverlap)
        highlightedLineIds.clear()
        highlightedLineIds.addAll(incoming)
        return snapshotLocked()
    }

    @Synchronized
    fun replace(lineId: Int) {
        gap = false
        completedOverlapLineIds.clear()
        highlightedLineIds.clear()
        highlightedLineIds.add(lineId)
    }

    @Synchronized
    fun snapshot(): Set<Int> = snapshotLocked()

    @Synchronized
    fun isGap(): Boolean = gap

    private fun snapshotLocked(): Set<Int> = highlightedLineIds + completedOverlapLineIds
}
