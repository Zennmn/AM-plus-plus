package dev.amenhancer.module.hook

/**
 * Keeps the latest line IDs reported by each native word callback stream.
 * Apple exposes primary, background, transliteration and transliteration
 * background streams independently, so the blur target is their union. A
 * line-subset transition records departed rows as candidates; a later word
 * callback can prove one of those rows is still in flight and retain it until
 * the next distinct line snapshot.
 */
internal class LyricWordHighlightState {
    private val lock = Any()
    private val lineIdsBySource = mutableMapOf<String, Set<Int>>()
    private val reportedLineIds = mutableSetOf<Int>()
    private var lineSnapshot = emptySet<Int>()
    private var subsetCandidateLineIds = emptySet<Int>()
    private var subsetGraceLineIds = emptySet<Int>()

    fun update(source: String, lineIds: Set<Int>): Set<Int> = synchronized(lock) {
        val snapshot = lineIds.toSet()
        lineIdsBySource[source] = snapshot
        reportedLineIds.addAll(snapshot)
        if (subsetCandidateLineIds.isNotEmpty()) {
            subsetGraceLineIds += snapshot.intersect(subsetCandidateLineIds)
        }
        snapshotLocked()
    }

    /**
     * Records a non-empty line callback without changing the line session itself.
     *
     * A subset callback records each departed row as a candidate. Keep only a
     * candidate subsequently corroborated by a word callback in grace, and only
     * for this overlap transition. The grace is retired by the next distinct
     * non-empty line callback; empty callbacks retain it just as they retain the
     * existing line-session snapshot.
     */
    fun onLineHighlightsChanged(lineIds: Set<Int>): Set<Int> = synchronized(lock) {
        if (lineIds.isEmpty()) return@synchronized snapshotLocked()

        val incoming = lineIds.toSet()
        if (incoming != lineSnapshot) {
            val departed = if (
                lineSnapshot.size > incoming.size &&
                lineSnapshot.containsAll(incoming)
            ) {
                lineSnapshot - incoming
            } else {
                emptySet()
            }
            subsetGraceLineIds = departed.intersect(reportedLineIds)
            subsetCandidateLineIds = departed
            reportedLineIds.clear()
            lineSnapshot = incoming
        }
        snapshotLocked()
    }

    /** Clears only line-transition evidence while keeping live word streams intact. */
    fun resetLineHistory() = synchronized(lock) {
        reportedLineIds.clear()
        lineSnapshot = emptySet()
        subsetCandidateLineIds = emptySet()
        subsetGraceLineIds = emptySet()
    }

    fun snapshot(): Set<Int> = synchronized(lock) {
        snapshotLocked()
    }

    /** Returns only the currently reported word rows, excluding subset-transition grace. */
    fun liveSnapshot(): Set<Int> = synchronized(lock) {
        liveSnapshotLocked()
    }

    fun clear() = synchronized(lock) {
        lineIdsBySource.clear()
        reportedLineIds.clear()
        lineSnapshot = emptySet()
        subsetCandidateLineIds = emptySet()
        subsetGraceLineIds = emptySet()
    }

    private fun snapshotLocked(): Set<Int> = liveSnapshotLocked() + subsetGraceLineIds

    private fun liveSnapshotLocked(): Set<Int> = lineIdsBySource.values
        .flatten()
        .toSet()
}
