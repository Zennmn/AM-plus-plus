package io.github.proify.lyricon.amprovider.xposed

/** Bounded memoization of a pure text policy; never stores media IDs, aliases or host objects. */
internal class AppleCollaborationArtistCache(
    private val capacity: Int = 256,
    private val classify: (String) -> Boolean,
) {
    private val results = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    fun isCollaboration(credit: String): Boolean {
        val normalized = credit.trim()
        if (normalized.isEmpty()) return false
        // Avoid retaining unusually large server-provided strings.
        if (normalized.length > 512) return classify(normalized)
        synchronized(results) { results[normalized]?.let { return it } }
        val result = classify(normalized)
        synchronized(results) {
            results[normalized] = result
            if (results.size > capacity) {
                val oldest = results.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
        return result
    }
}
