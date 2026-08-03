package dev.amenhancer.module.ui

import dev.amenhancer.module.model.CustomLyricsEntry

/**
 * Android-free pagination and search seam behind the custom lyrics page.
 *
 * The page renders only [visibleEntries] (at most [pageSize] at first) and
 * reveals more through [loadMore]; a case-insensitive query narrows by
 * display name or Apple Music ID before pagination applies.
 *
 * Contract:
 * - [update] replaces the dataset wholesale, so a refresh after
 *   save/toggle/delete/restore can never mix old and new rows.
 * - The revealed window survives [update] and [setQuery] but converges:
 *   it is clamped to the filtered total, and it re-seeds to the first page
 *   whenever the filtered total used to be empty.
 */
class CustomLyricsListState(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private var entries: List<CustomLyricsEntry> = emptyList()
    private var query: String = ""
    private var revealed: Int = 0

    val totalCount: Int get() = filtered().size

    val visibleCount: Int get() = visibleEntries.size

    val visibleEntries: List<CustomLyricsEntry> get() = filtered().take(revealed)

    val hasMore: Boolean get() = revealed < totalCount

    fun update(newEntries: List<CustomLyricsEntry>, newQuery: String = query) {
        entries = newEntries
        query = newQuery
        converge()
    }

    fun setQuery(newQuery: String) {
        query = newQuery
        converge()
    }

    fun loadMore(amount: Int = pageSize) {
        if (amount <= 0) return
        revealed = minOf(revealed + amount, totalCount)
        converge()
    }

    private fun converge() {
        revealed = minOf(revealed, totalCount)
        if (revealed == 0 && totalCount > 0) revealed = minOf(pageSize, totalCount)
    }

    private fun filtered(): List<CustomLyricsEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries
        return entries.filter { entry ->
            entry.displayName.lowercase().contains(needle) ||
                entry.appleMusicId.toString().contains(needle)
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
