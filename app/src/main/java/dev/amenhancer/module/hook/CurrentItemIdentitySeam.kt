package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The verified current lyrics item identity seam: the I2 fragment's current
 * item field (`com.apple.android.music.player.fragment.m#c` of type
 * `com.apple.android.music.model.BaseContentItem`) read through `getId()` and
 * parsed with [parseCurrentItemAdamId]. The same item optionally supplies
 * `getTitle()`, `getArtistName()` and (when exposed by the host build)
 * `getCollectionName()` for the standalone current-song form.
 *
 * Lyric replacement and current-song identity capability share this exact
 * contract; neither consumer may reinterpret the identity as a title or
 * metadata match. Both resolve the seam through the same
 * [AppleMusicSymbols.LyricsCurrentItemField] symbol so a version change is
 * reported once as missing or ambiguous instead of being guessed.
 */
internal class CurrentItemIdentitySeam(
    private val symbols: TargetSymbolResolver,
) {
    private lateinit var currentItemField: Field
    private lateinit var currentItemGetId: Method
    private var currentItemGetTitle: Method? = null
    private var currentItemGetArtistName: Method? = null
    private var currentItemGetAlbum: Method? = null
    private var currentItemGetDuration: Method? = null

    /** The verified current item field resolution summary, or null before resolve. */
    var fieldSummary: String? = null
        private set

    /** Optional metadata contracts used by the standalone current-song UI. */
    var metadataSummary: String? = null
        private set

    /**
     * Resolves the seam against the given I2 install entry point. Returns a
     * diagnostic when the contract cannot be established, or null once the
     * seam is ready to read identities.
     */
    fun resolve(installMethod: Method): String? {
        val currentItemResolution = symbols.resolve(AppleMusicSymbols.LyricsCurrentItemField)
        val currentItemFieldValue = currentItemResolution.valueOrNull()
            ?: return currentItemResolution.summary
        val getId = resolveCurrentItemGetId(currentItemFieldValue.type)
            ?: return "BaseContentItem#getId() was unavailable; ${currentItemResolution.summary}"
        if (!currentItemFieldValue.declaringClass.isAssignableFrom(installMethod.declaringClass)) {
            return "Current lyrics item field is not in the I2 fragment hierarchy; " +
                currentItemResolution.summary
        }
        currentItemField = currentItemFieldValue.apply { isAccessible = true }
        currentItemGetId = getId
        currentItemGetTitle = resolveStringGetter(currentItemFieldValue.type, "getTitle")
        currentItemGetArtistName = resolveStringGetter(currentItemFieldValue.type, "getArtistName")
        currentItemGetAlbum = resolveStringGetter(currentItemFieldValue.type, "getCollectionName")
            ?: resolveStringGetter(currentItemFieldValue.type, "getAlbumName")
        currentItemGetDuration = resolveLongGetter(currentItemFieldValue.type, "getDuration")
        fieldSummary = currentItemResolution.summary
        metadataSummary = buildList {
            if (currentItemGetTitle == null) add("current-item-title-method unavailable")
            if (currentItemGetArtistName == null) add("current-item-artist-method unavailable")
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return null
    }

    /** The current lyrics item Adam ID of an I2 fragment, or null when unavailable. */
    fun currentItemAdamIdOf(fragment: Any?): Long? {
        if (fragment == null) return null
        return runCatching {
            val item = currentItemField.get(fragment) ?: return@runCatching null
            parseCurrentItemAdamId(currentItemGetId.invoke(item))
        }.getOrNull()
    }

    /** Rebinds the verified lyrics fragment field to an exact player item. */
    fun bindCurrentItemOf(fragment: Any?, item: Any?): Boolean {
        if (fragment == null || item == null) return false
        return runCatching {
            if (!currentItemField.type.isInstance(item)) return@runCatching false
            currentItemField.set(fragment, item)
            currentItemField.get(fragment) === item
        }.getOrDefault(false)
    }

    /** Reads identity directly from a verified player item argument. */
    fun detailsOfItem(item: Any?): CurrentSongDetails? {
        if (item == null) return null
        return runCatching {
            val appleMusicId = parseCurrentItemAdamId(currentItemGetId.invoke(item))
                ?: return@runCatching null
            CurrentSongDetails(
                appleMusicId = appleMusicId,
                title = invokeStringGetter(currentItemGetTitle, item),
                artist = invokeStringGetter(currentItemGetArtistName, item),
                album = invokeStringGetter(currentItemGetAlbum, item),
                durationMs = invokeLongGetter(currentItemGetDuration, item),
            )
        }.getOrNull()
    }

    private fun resolveCurrentItemGetId(itemType: Class<*>): Method? = resolveStringGetter(itemType, "getId")

    private fun resolveStringGetter(itemType: Class<*>, name: String): Method? = runCatching {
        itemType.getMethod(name)
            .takeIf { method -> method.returnType == String::class.java }
            ?.apply { isAccessible = true }
    }.getOrNull()

    private fun resolveLongGetter(itemType: Class<*>, name: String): Method? = runCatching {
        itemType.getMethod(name)
            .takeIf { method ->
                method.returnType == Long::class.javaPrimitiveType ||
                    Number::class.java.isAssignableFrom(method.returnType)
            }
            ?.apply { isAccessible = true }
    }.getOrNull()

    private fun invokeStringGetter(method: Method?, receiver: Any): String? = method
        ?.let { runCatching { it.invoke(receiver) as? String }.getOrNull() }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun invokeLongGetter(method: Method?, receiver: Any): Long? = method
        ?.let { runCatching { (it.invoke(receiver) as? Number)?.toLong() }.getOrNull() }
        ?.takeIf { it > 0L }
}

/** Parses Apple's current item identity; only a positive Adam ID is accepted. */
internal fun parseCurrentItemAdamId(value: Any?): Long? = when (value) {
    is String -> value.toLongOrNull()
    is Number -> value.toLong()
    else -> null
}?.takeIf { it > 0L }
