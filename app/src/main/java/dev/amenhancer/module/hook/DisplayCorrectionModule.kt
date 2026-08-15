package dev.amenhancer.module.hook

/** The three display values corrected together for one host object bind. */
internal enum class DisplayCorrectionField {
    TITLE,
    ARTIST,
    ALBUM,
}

/** A bounded, immutable snapshot of the host values read for one bind. */
internal data class DisplayCorrectionSnapshot(
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    fun value(field: DisplayCorrectionField): String? = when (field) {
        DisplayCorrectionField.TITLE -> title
        DisplayCorrectionField.ARTIST -> artist
        DisplayCorrectionField.ALBUM -> album
    }
}

/** Candidate values returned by one object-level cache/identity lookup. */
internal data class DisplayCorrectionCandidates(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
) {
    fun value(field: DisplayCorrectionField): String? = when (field) {
        DisplayCorrectionField.TITLE -> title
        DisplayCorrectionField.ARTIST -> artist
        DisplayCorrectionField.ALBUM -> album
    }
}

/**
 * Reads and writes the host display object.  Implementations must keep all
 * host-specific reflection or generated accessors here; the module itself is
 * deliberately unaware of the host class shape.
 */
internal interface DisplayCorrectionAdapter<T : Any> {
    fun read(target: T, field: DisplayCorrectionField): String?

    fun write(target: T, field: DisplayCorrectionField, value: String)
}

/**
 * Resolves all candidates for a single host bind.  Keeping this operation
 * object-shaped gives the cache implementation one identity-snapshot seam;
 * callers do not need to repeat identity resolution once per display field.
 */
internal interface DisplayCorrectionLookup<T : Any> {
    fun lookup(target: T, original: DisplayCorrectionSnapshot): DisplayCorrectionCandidates
}

/** Convenience names for callers that use accessor/resolver vocabulary. */
internal typealias DisplayCorrectionAccessor<T> = DisplayCorrectionAdapter<T>
internal typealias DisplayCorrectionResolver<T> = DisplayCorrectionLookup<T>

/** Result of one object bind; a failed result never reports partial changes. */
internal data class DisplayCorrectionResult(
    val changedFields: Set<DisplayCorrectionField> = emptySet(),
    val failed: Boolean = false,
)

/**
 * Corrects title, artist and album in one bounded operation.
 *
 * The operation is fail-open by construction: all getters complete before the
 * resolver is called, and no setter is attempted until the complete candidate
 * set is available.  A setter failure restores every field whose write may
 * have been attempted (including the failing field, because a host setter may
 * mutate before throwing) and reports no partial result.
 */
internal class DisplayCorrectionModule<T : Any>(
    private val adapter: DisplayCorrectionAdapter<T>,
    private val lookup: DisplayCorrectionLookup<T>,
) {
    fun bind(target: T): DisplayCorrectionResult {
        val original = try {
            DisplayCorrectionSnapshot(
                title = adapter.read(target, DisplayCorrectionField.TITLE),
                artist = adapter.read(target, DisplayCorrectionField.ARTIST),
                album = adapter.read(target, DisplayCorrectionField.ALBUM),
            )
        } catch (_: Throwable) {
            return DisplayCorrectionResult(failed = true)
        }

        val candidates = try {
            lookup.lookup(target, original)
        } catch (_: Throwable) {
            return DisplayCorrectionResult(failed = true)
        }

        val changes = DisplayCorrectionField.entries.mapNotNull { field ->
            val raw = original.value(field)
            val candidate = candidates.value(field)
            if (raw.isNullOrBlank() || candidate.isNullOrBlank() || raw == candidate) {
                null
            } else {
                DisplayCorrectionChange(field, raw, candidate)
            }
        }
        if (changes.isEmpty()) return DisplayCorrectionResult()

        val attempted = mutableListOf<DisplayCorrectionChange>()
        try {
            changes.forEach { change ->
                // Record before invocation: a host setter may mutate then throw.
                attempted += change
                adapter.write(target, change.field, change.candidate)
            }
        } catch (_: Throwable) {
            attempted.asReversed().forEach { change ->
                runCatching { adapter.write(target, change.field, change.original) }
            }
            return DisplayCorrectionResult(failed = true)
        }

        return DisplayCorrectionResult(changedFields = changes.mapTo(linkedSetOf()) { it.field })
    }

    /** Alias useful to hook call sites that use correction terminology. */
    fun correct(target: T): DisplayCorrectionResult = bind(target)

    private data class DisplayCorrectionChange(
        val field: DisplayCorrectionField,
        val original: String,
        val candidate: String,
    )
}

/**
 * Reflection adapter for the two host object shapes currently used by Apple
 * Music: Attributes (`getName`/`setName`) and model display items
 * (`getTitle`/`setTitle`).  Missing getters simply make that field
 * uncorrectable; a missing setter is surfaced as an apply failure so the
 * module can preserve the original object.
 */
internal class ReflectiveDisplayCorrectionAdapter(
    private val titleGetter: String = "getTitle",
    private val titleSetter: String = "setTitle",
    private val artistGetter: String = "getArtistName",
    private val artistSetter: String = "setArtistName",
    private val albumGetter: String = "getCollectionName",
    private val albumSetter: String = "setCollectionName",
) : DisplayCorrectionAdapter<Any> {
    override fun read(target: Any, field: DisplayCorrectionField): String? {
        val getter = ReflectionMethodCache.find(
            owner = target.javaClass,
            name = methodName(field, getter = true),
            returnType = String::class.java,
        ) ?: return null
        return getter.invoke(target) as? String
    }

    override fun write(target: Any, field: DisplayCorrectionField, value: String) {
        val setter = ReflectionMethodCache.find(
            owner = target.javaClass,
            name = methodName(field, getter = false),
            parameterTypes = listOf(String::class.java),
        ) ?: throw NoSuchMethodException(
            "${target.javaClass.name}.${methodName(field, getter = false)}(String)",
        )
        setter.invoke(target, value)
    }

    private fun methodName(field: DisplayCorrectionField, getter: Boolean): String = when (field) {
        DisplayCorrectionField.TITLE -> if (getter) titleGetter else titleSetter
        DisplayCorrectionField.ARTIST -> if (getter) artistGetter else artistSetter
        DisplayCorrectionField.ALBUM -> if (getter) albumGetter else albumSetter
    }
}
