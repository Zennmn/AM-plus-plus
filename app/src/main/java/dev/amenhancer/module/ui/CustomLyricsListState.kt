package dev.amenhancer.module.ui

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources

internal data class CustomLyricsUiGroup(
    val entries: List<CustomLyricsEntry>,
) {
    init {
        require(entries.isNotEmpty())
    }

    val primary: CustomLyricsEntry get() = entries.first()
    val appleMusicIds: List<Long> get() = entries.map(CustomLyricsEntry::appleMusicId)
    val allEnabled: Boolean get() = entries.all(CustomLyricsEntry::enabled)
}

internal fun groupCustomLyricsEntries(entries: List<CustomLyricsEntry>): List<CustomLyricsUiGroup> {
    val groups = mutableListOf<MutableList<CustomLyricsEntry>>()
    entries.forEach { entry ->
        val previous = groups.lastOrNull()
        if (previous != null && canMergeCustomLyricsEntries(previous.first(), entry)) {
            previous += entry
        } else {
            groups += mutableListOf(entry)
        }
    }
    return groups.map(::CustomLyricsUiGroup)
}

private fun canMergeCustomLyricsEntries(
    first: CustomLyricsEntry,
    second: CustomLyricsEntry,
): Boolean = first.source == CustomLyricsSources.AM_LYRICS &&
    second.source == CustomLyricsSources.AM_LYRICS &&
    first.displayName == second.displayName &&
    first.sha256 == second.sha256

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
    private var groups: List<CustomLyricsUiGroup> = emptyList()
    private var query: String = ""
    private var revealed: Int = 0

    val totalCount: Int get() = filtered().size

    val visibleCount: Int get() = visibleGroups.size

    val visibleEntries: List<CustomLyricsEntry> get() = visibleGroups.map(CustomLyricsUiGroup::primary)
    internal val visibleGroups: List<CustomLyricsUiGroup> get() = filtered().take(revealed)

    val hasMore: Boolean get() = revealed < totalCount

    fun update(newEntries: List<CustomLyricsEntry>, newQuery: String = query) {
        groups = groupCustomLyricsEntries(newEntries)
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

    private fun filtered(): List<CustomLyricsUiGroup> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return groups
        return groups.filter { group ->
            group.entries.any { entry ->
                entry.displayName.lowercase().contains(needle) ||
                    entry.appleMusicId.toString().contains(needle)
            }
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
