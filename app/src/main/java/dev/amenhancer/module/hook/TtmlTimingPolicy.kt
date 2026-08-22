package dev.amenhancer.module.hook

import java.util.LinkedHashMap

/**
 * The native SongInfo surface does not expose Apple's root timing attribute.
 * Keep the raw-TTML check small and deterministic so the parser seam can bind
 * the observed mode to the exact pointer later received by I2.
 */
internal enum class TtmlTimingMode {
    WORD,
    NON_WORD,
}

/**
 * Metadata observed from the raw TTML before Apple's native parser hides it.
 * Language is intentionally nullable: a missing language declaration must not
 * be guessed from lyric text, but non-Word timing remains eligible regardless
 * of language.
 */
internal data class TtmlDocumentMetadata(
    val timingMode: TtmlTimingMode,
    val language: String?,
    val hasTranslation: Boolean,
) {
    val isForeign: Boolean
        get() = language?.let(::isForeignLanguage) == true

    /** A Word-timed foreign document without a translation track needs a fallback. */
    val needsTranslationFallback: Boolean
        get() = timingMode == TtmlTimingMode.WORD && isForeign && !hasTranslation

    private companion object {
        fun isForeignLanguage(language: String): Boolean {
            val normalized = language.trim().lowercase().replace('_', '-')
            if (normalized.isBlank()) return false
            return normalized != "zh" &&
                !normalized.startsWith("zh-") &&
                normalized != "cmn" &&
                !normalized.startsWith("cmn-") &&
                normalized != "zho" &&
                !normalized.startsWith("zho-") &&
                normalized != "chi" &&
                !normalized.startsWith("chi-") &&
                normalized != "yue" &&
                !normalized.startsWith("yue-") &&
                normalized != "wuu" &&
                !normalized.startsWith("wuu-") &&
                normalized != "nan" &&
                !normalized.startsWith("nan-") &&
                normalized != "hak" &&
                !normalized.startsWith("hak-") &&
                normalized != "lzh" &&
                !normalized.startsWith("lzh-")
        }
    }
}

internal object TtmlTimingPolicy {
    private val rootTag = Regex("""(?is)<tt\b[^>]*>""")
    private val timingAttribute = Regex(
        """(?is)(?:[A-Za-z_][\w.-]*:)?timing\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )
    private val languageAttribute = Regex(
        """(?is)(?:xml:lang|lang)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )
    private val translationsBlock = Regex(
        """(?is)<translations\b[^>]*>(.*?)</translations\s*>""",
    )
    private val translationElement = Regex(
        """(?is)<translation\b[^>]*>(.*?)</translation\s*>""",
    )
    private val markup = Regex("""(?is)<[^>]+>""")

    fun metadataOf(ttml: String): TtmlDocumentMetadata = runCatching {
        val root = rootTag.find(ttml)?.value
        val timingMode = timingModeOf(root)
        val language = root?.let { rootTagLanguage(it) }
        val hasTranslation = translationsBlock.find(ttml)?.let { block ->
            translationElement.findAll(block.groupValues[1]).any { translation ->
                markup.replace(translation.groupValues[1], " ").isNotBlank()
            }
        } == true
        TtmlDocumentMetadata(
            timingMode = timingMode,
            language = language,
            hasTranslation = hasTranslation,
        )
    }.getOrElse {
        TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.NON_WORD,
            language = null,
            hasTranslation = false,
        )
    }

    /** Missing, Line, or any value other than Word is intentionally non-word. */
    fun modeOf(ttml: String): TtmlTimingMode = metadataOf(ttml).timingMode

    fun isWord(ttml: String): Boolean = modeOf(ttml) == TtmlTimingMode.WORD

    private fun timingModeOf(root: String?): TtmlTimingMode {
        if (root == null) return TtmlTimingMode.NON_WORD
        val value = timingAttribute.find(root)?.let { match ->
            match.groups[1]?.value ?: match.groups[2]?.value
        }
        return if (value?.trim().equals("Word", ignoreCase = true)) {
            TtmlTimingMode.WORD
        } else {
            TtmlTimingMode.NON_WORD
        }
    }

    private fun rootTagLanguage(root: String): String? = languageAttribute.find(root)?.let { match ->
        match.groups[1]?.value?.trim()?.takeIf(String::isNotBlank)
            ?: match.groups[2]?.value?.trim()?.takeIf(String::isNotBlank)
    }
}

/**
 * Bounded identity observations from the parser seam. Weak references avoid
 * retaining JavaCPP pointer wrappers (and their native addresses) after Apple
 * releases a lyric document.
 */
internal class TtmlTimingObservationRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Observation(
        val pointer: java.lang.ref.WeakReference<Any>,
        val metadata: TtmlDocumentMetadata,
    )

    private val observations = ArrayDeque<Observation>()
    private val idObservations = object : LinkedHashMap<Long, TtmlDocumentMetadata>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, TtmlDocumentMetadata>?,
        ): Boolean = size > maxEntries.coerceAtLeast(1)
    }

    fun record(
        pointer: Any?,
        metadata: TtmlDocumentMetadata,
        appleMusicId: Long? = null,
    ) {
        if (pointer == null) return
        synchronized(observations) {
            sweepCleared()
            val iterator = observations.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().pointer.get() === pointer) iterator.remove()
            }
            while (observations.size >= maxEntries.coerceAtLeast(1)) observations.removeFirst()
            observations.addLast(Observation(java.lang.ref.WeakReference(pointer), metadata))
            appleMusicId?.takeIf { it > 0L }?.let { idObservations[it] = metadata }
        }
    }

    fun record(pointer: Any?, mode: TtmlTimingMode) = record(
        pointer = pointer,
        metadata = TtmlDocumentMetadata(
            timingMode = mode,
            language = null,
            hasTranslation = false,
        ),
    )

    fun metadataOf(pointer: Any?): TtmlDocumentMetadata? {
        if (pointer == null) return null
        synchronized(observations) {
            sweepCleared()
            return observations.firstOrNull { it.pointer.get() === pointer }?.metadata
        }
    }

    /** Returns the latest observed native TTML metadata for a verified song ID. */
    fun metadataOfAppleMusicId(appleMusicId: Long): TtmlDocumentMetadata? {
        if (appleMusicId <= 0L) return null
        synchronized(observations) { return idObservations[appleMusicId] }
    }

    fun modeOf(pointer: Any?): TtmlTimingMode? {
        return metadataOf(pointer)?.timingMode
    }

    private fun sweepCleared() {
        val iterator = observations.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().pointer.get() == null) iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
