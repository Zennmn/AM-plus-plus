package dev.amenhancer.module.hook

import android.view.View
import dev.amenhancer.module.ModuleConstants
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

internal data class TargetBuild(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
) {
    val displayName: String
        get() = if (versionName.isBlank() && versionCode < 0) "unknown" else "$versionName ($versionCode)"

    companion object {
        val UNKNOWN = TargetBuild(ModuleConstants.TARGET_PACKAGE, "", -1)
    }
}

internal enum class SymbolMatch {
    VERSION_PROFILE,
    STABLE_NAME,
    STRUCTURAL_FALLBACK,
}

internal sealed interface TargetResolution<out T : Any> {
    val symbol: String
    val summary: String

    data class Found<T : Any>(
        override val symbol: String,
        val value: T,
        val match: SymbolMatch,
        val profileId: String?,
    ) : TargetResolution<T> {
        override val summary: String = buildString {
            append(symbol).append(" resolved via ").append(match.name.lowercase())
            profileId?.let { append(" [").append(it).append(']') }
        }
    }

    data class Missing(
        override val symbol: String,
        val profileId: String?,
    ) : TargetResolution<Nothing> {
        override val summary: String = "$symbol was not found" + profileId?.let { " [$it]" }.orEmpty()
    }

    data class Ambiguous(
        override val symbol: String,
        val candidates: List<String>,
        val profileId: String?,
    ) : TargetResolution<Nothing> {
        override val summary: String =
            "$symbol was ambiguous (" + candidates.size + " candidates)" +
                candidates.take(3).joinToString(prefix = ": ", separator = ", ") +
                profileId?.let { " [$it]" }.orEmpty()
    }
}

internal fun <T : Any> TargetResolution<T>.valueOrNull(): T? =
    (this as? TargetResolution.Found<T>)?.value

internal interface TargetClassSource {
    fun classNames(): List<String>
    fun loadClass(name: String): Class<*>?
}

internal class TargetClassIndex(private val source: TargetClassSource) {
    private val names: List<String> by lazy {
        source.classNames().distinct().sorted()
    }
    private val loaded = mutableMapOf<String, Class<*>?>()

    fun load(name: String): Class<*>? = synchronized(loaded) {
        if (loaded.containsKey(name)) return@synchronized loaded[name]
        source.loadClass(name).also { loaded[name] = it }
    }

    fun classes(
        namePredicate: (String) -> Boolean,
        contract: (Class<*>) -> Boolean,
    ): List<Class<*>> = names.asSequence()
        .filter(namePredicate)
        .mapNotNull(::load)
        .filter { candidate -> runCatching { contract(candidate) }.getOrDefault(false) }
        .distinctBy(Class<*>::getName)
        .toList()

    fun methods(
        namePredicate: (String) -> Boolean,
        contract: (Method) -> Boolean,
    ): List<Method> = classes(namePredicate) { true }
        .flatMap { type ->
            runCatching {
                type.declaredMethods.filter { method ->
                    runCatching { contract(method) }.getOrDefault(false)
                }
            }.getOrDefault(emptyList())
        }
        .distinctBy(::methodIdentity)

    fun fields(
        namePredicate: (String) -> Boolean,
        contract: (Field) -> Boolean,
    ): List<Field> = classes(namePredicate) { true }
        .flatMap { type ->
            runCatching {
                type.declaredFields.filter { field ->
                    runCatching { contract(field) }.getOrDefault(false)
                }
            }.getOrDefault(emptyList())
        }
        .distinctBy(::fieldIdentity)
}

internal class TargetSymbolKey<T : Any>(
    val id: String,
    internal val profileCandidates: TargetClassIndex.(AppleMusicProfile?) -> List<T> = { emptyList() },
    internal val profileBound: Boolean = false,
    internal val stableCandidates: TargetClassIndex.() -> List<T> = { emptyList() },
    internal val structuralCandidates: TargetClassIndex.() -> List<T>,
    internal val identity: (T) -> String,
)

internal interface TargetSymbolResolver {
    fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T>
}

internal class IndexedTargetSymbolResolver(
    build: TargetBuild,
    source: TargetClassSource,
) : TargetSymbolResolver {
    private val profile = AppleMusicProfiles.match(build)
    private val index = TargetClassIndex(source)
    private val resolutions = IdentityHashMap<TargetSymbolKey<*>, TargetResolution<*>>()

    override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> =
        synchronized(resolutions) {
            @Suppress("UNCHECKED_CAST")
            resolutions[symbol]?.let { return@synchronized it as TargetResolution<T> }
            resolveUncached(symbol).also { resolutions[symbol] = it }
        }

    private fun <T : Any> resolveUncached(symbol: TargetSymbolKey<T>): TargetResolution<T> {
        if (profile != null && symbol.profileBound) {
            return select(
                symbol,
                symbol.profileCandidates(index, profile),
                SymbolMatch.VERSION_PROFILE,
            ) ?: TargetResolution.Missing(symbol.id, profile.id)
        }
        select(symbol, symbol.stableCandidates(index), SymbolMatch.STABLE_NAME)?.let { return it }
        val fallback = distinctCandidates(symbol, symbol.structuralCandidates(index))
        return when (fallback.size) {
            0 -> TargetResolution.Missing(symbol.id, profile?.id)
            1 -> TargetResolution.Found(
                symbol = symbol.id,
                value = fallback.single(),
                match = SymbolMatch.STRUCTURAL_FALLBACK,
                profileId = profile?.id,
            )
            else -> TargetResolution.Ambiguous(symbol.id, fallback.map(symbol.identity), profile?.id)
        }
    }

    private fun <T : Any> select(
        symbol: TargetSymbolKey<T>,
        candidates: List<T>,
        match: SymbolMatch,
    ): TargetResolution<T>? {
        val distinct = distinctCandidates(symbol, candidates)
        return when (distinct.size) {
            0 -> null
            1 -> TargetResolution.Found(symbol.id, distinct.single(), match, profile?.id)
            else -> TargetResolution.Ambiguous(symbol.id, distinct.map(symbol.identity), profile?.id)
        }
    }

    private fun <T : Any> distinctCandidates(symbol: TargetSymbolKey<T>, candidates: List<T>): List<T> =
        candidates.distinctBy(symbol.identity)
}

internal data class AppleMusicProfile(
    val id: String,
    val exactClasses: Map<TargetSymbolId, String>,
)

internal enum class TargetSymbolId {
    PLAYER_CONTROLLER,
    PLAYER_ACTIVITY,
    EDITORIAL_VIDEO_OWNER,
    LYRICS_FRAGMENT,
    LYRICS_CHROME,
    LYRICS_LINE_VECTOR,
    LYRICS_EVENT_PROCESSOR,
    LYRICS_HIGHLIGHT_CALLBACK_OWNER,
    LYRICS_VIEW_MODEL,
    STACKED_NAVIGATION_MENU,
    SONG_INFO_PTR,
    SONG_INFO_NATIVE,
    TTML_PARSER_NATIVE,
    LYRICS_CURRENT_ITEM_FIELD,
    PLAYER_METADATA_HUB,
    METADATA_TO_ITEM_CONVERTER,
    LYRICS_AVAILABILITY_OWNER,
}

private object AppleMusicProfiles {
    private val appleMusic650 = AppleMusicProfile(
        id = "apple-music-6.5.0-1580",
        exactClasses = mapOf(
            TargetSymbolId.PLAYER_CONTROLLER to "com.apple.android.music.player.fragment.w0",
            TargetSymbolId.PLAYER_ACTIVITY to "com.apple.android.music.common.activity.PlayerActivity",
            TargetSymbolId.EDITORIAL_VIDEO_OWNER to "com.apple.android.music.player.c1",
            TargetSymbolId.LYRICS_FRAGMENT to "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            TargetSymbolId.LYRICS_CHROME to "com.apple.android.music.player.fragment.e",
            TargetSymbolId.LYRICS_LINE_VECTOR to
                "com.apple.android.music.ttml.javanative.model.LyricsLineVector",
            TargetSymbolId.LYRICS_EVENT_PROCESSOR to
                "com.apple.android.music.ttml.SongInfoTimeProcessor",
            TargetSymbolId.LYRICS_HIGHLIGHT_CALLBACK_OWNER to
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents\$lineEventCallback\$1",
            TargetSymbolId.LYRICS_VIEW_MODEL to
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            TargetSymbolId.STACKED_NAVIGATION_MENU to "Hd.b",
            TargetSymbolId.SONG_INFO_PTR to
                "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
            TargetSymbolId.SONG_INFO_NATIVE to
                "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative",
            TargetSymbolId.TTML_PARSER_NATIVE to
                "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative",
            TargetSymbolId.LYRICS_CURRENT_ITEM_FIELD to
                "com.apple.android.music.player.fragment.m",
            TargetSymbolId.PLAYER_METADATA_HUB to "com.apple.android.music.player.f",
            TargetSymbolId.METADATA_TO_ITEM_CONVERTER to "com.apple.android.music.player.P",
            TargetSymbolId.LYRICS_AVAILABILITY_OWNER to "com.apple.android.music.player.d1",
        ),
    )

    private val appleMusic651 = AppleMusicProfile(
        id = "apple-music-6.5.1-1583",
        exactClasses = mapOf(
            TargetSymbolId.PLAYER_CONTROLLER to "com.apple.android.music.player.fragment.q0",
            TargetSymbolId.PLAYER_ACTIVITY to "com.apple.android.music.common.activity.PlayerActivity",
            TargetSymbolId.EDITORIAL_VIDEO_OWNER to "com.apple.android.music.player.f1",
            TargetSymbolId.LYRICS_FRAGMENT to "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            TargetSymbolId.LYRICS_CHROME to "com.apple.android.music.player.fragment.d",
            TargetSymbolId.LYRICS_LINE_VECTOR to
                "com.apple.android.music.ttml.javanative.model.LyricsLineVector",
            TargetSymbolId.LYRICS_EVENT_PROCESSOR to
                "com.apple.android.music.ttml.SongInfoTimeProcessor",
            TargetSymbolId.LYRICS_HIGHLIGHT_CALLBACK_OWNER to
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents\$lineEventCallback\$1",
            TargetSymbolId.LYRICS_VIEW_MODEL to
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            TargetSymbolId.STACKED_NAVIGATION_MENU to "Hd.b",
            TargetSymbolId.SONG_INFO_PTR to
                "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
            TargetSymbolId.SONG_INFO_NATIVE to
                "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative",
            TargetSymbolId.TTML_PARSER_NATIVE to
                "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative",
            TargetSymbolId.LYRICS_CURRENT_ITEM_FIELD to
                "com.apple.android.music.player.fragment.l",
            TargetSymbolId.PLAYER_METADATA_HUB to "com.apple.android.music.player.f",
            TargetSymbolId.METADATA_TO_ITEM_CONVERTER to "com.apple.android.music.player.O",
            TargetSymbolId.LYRICS_AVAILABILITY_OWNER to "com.apple.android.music.player.e1",
        ),
    )

    fun match(build: TargetBuild): AppleMusicProfile? {
        if (build.packageName != ModuleConstants.TARGET_PACKAGE) return null
        return when {
            build.versionName == "6.5.0" && build.versionCode == 1580L -> appleMusic650
            build.versionName == "6.5.1" && build.versionCode == 1583L -> appleMusic651
            else -> null
        }
    }
}

internal object AppleMusicSymbols {
    val PlayerController = classSymbol(
        id = "player-controller",
        profileId = TargetSymbolId.PLAYER_CONTROLLER,
        fallbackName = { it.startsWith("com.apple.android.music.player.fragment.") },
    ) { candidate ->
        candidate.declaredMethods.any { method ->
            method.name == "w1" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.singleOrNull()?.name?.endsWith(".BagConfig") == true
        }
    }

    val PlayerActivity = classSymbol(
        id = "player-activity",
        profileId = TargetSymbolId.PLAYER_ACTIVITY,
        fallbackName = { it.endsWith(".common.activity.PlayerActivity") },
        contract = { true },
    )

    val EditorialVideoUrlSelector = methodSymbol(
        id = "editorial-video-url-selector",
        profileOwner = TargetSymbolId.EDITORIAL_VIDEO_OWNER,
        fallbackOwner = { name ->
            name.startsWith("com.apple.android.music.player.") &&
                name.substringAfterLast('.').substringBefore('$').length <= 3
        },
        contract = ::isEditorialVideoUrlSelector,
    )

    val LyricsFragment = classSymbol(
        id = "lyrics-fragment",
        profileId = TargetSymbolId.LYRICS_FRAGMENT,
        fallbackName = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = { true },
    )

    val LyricsChromeFragment = classSymbol(
        id = "lyrics-chrome-fragment",
        profileId = TargetSymbolId.LYRICS_CHROME,
        fallbackName = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::hasLyricsChromeContract,
    )

    val RecyclerView = classSymbol(
        id = "lyrics-recycler-view",
        stableName = "androidx.recyclerview.widget.RecyclerView",
        fallbackName = { false },
        contract = { true },
    )

    val LyricsLineVector = classSymbol(
        id = "lyrics-line-vector",
        profileId = TargetSymbolId.LYRICS_LINE_VECTOR,
        stableName = "com.apple.android.music.ttml.javanative.model.LyricsLineVector",
        fallbackName = { it.endsWith(".ttml.javanative.model.LyricsLineVector") },
        contract = { true },
    )

    val LyricsSessionProcessor = methodSymbol(
        id = "lyrics-session-processor",
        profileOwner = TargetSymbolId.LYRICS_EVENT_PROCESSOR,
        fallbackOwner = { it.endsWith(".ttml.SongInfoTimeProcessor") },
        contract = ::isLyricsSessionProcessor,
    )

    val LyricsHighlightCallback = TargetSymbolKey(
        id = "lyrics-highlight-callback",
        profileBound = true,
        profileCandidates = { profile ->
            val owner = profile?.exactClasses?.get(TargetSymbolId.LYRICS_HIGHLIGHT_CALLBACK_OWNER)
                ?.let(::load)
            val vector = profile?.exactClasses?.get(TargetSymbolId.LYRICS_LINE_VECTOR)
                ?.let(::load)
            if (owner == null || vector == null) {
                emptyList()
            } else {
                runCatching {
                    owner.declaredMethods.filter { method ->
                        isLyricsHighlightCallback(method, vector)
                    }
                }.getOrDefault(emptyList())
            }
        },
        structuralCandidates = {
            val vector = (
                listOfNotNull(load("com.apple.android.music.ttml.javanative.model.LyricsLineVector")) +
                    classes(
                        namePredicate = { it.endsWith(".ttml.javanative.model.LyricsLineVector") },
                        contract = { true },
                    )
                ).distinctBy { it.name }.singleOrNull()
            if (vector == null) {
                emptyList()
            } else {
                methods(
                    namePredicate = {
                        it.startsWith("com.apple.android.music.ttml.SongInfoTimeProcessor\$")
                    },
                    contract = { method -> isLyricsHighlightCallback(method, vector) },
                )
            }
        },
        identity = ::methodIdentity,
    )

    val LyricsViewModel = classSymbol(
        id = "lyrics-view-model",
        profileId = TargetSymbolId.LYRICS_VIEW_MODEL,
        stableName = "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
        fallbackName = { it.endsWith(".PlayerLyricsViewModel", ignoreCase = true) },
        contract = { true },
    )

    val StackedNavigationMenu = classSymbol(
        id = "stacked-navigation-menu",
        profileId = TargetSymbolId.STACKED_NAVIGATION_MENU,
        stableName = "Hd.b",
        fallbackName = { false },
        contract = ::hasStackedNavigationMenuContract,
    )

    val SongInfoPtr = classSymbol(
        id = "song-info-ptr",
        profileId = TargetSymbolId.SONG_INFO_PTR,
        stableName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
        fallbackName = { it.endsWith(".ttml.javanative.model.SongInfo\$SongInfoPtr") },
        contract = ::hasSongInfoPtrContract,
    )

    val SongInfoNative = classSymbol(
        id = "song-info-native",
        profileId = TargetSymbolId.SONG_INFO_NATIVE,
        stableName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative",
        fallbackName = { it.endsWith(".ttml.javanative.model.SongInfo\$SongInfoNative") },
        contract = ::hasSongInfoNativeContract,
    )

    val TtmlParserNative = classSymbol(
        id = "ttml-parser-native",
        profileId = TargetSymbolId.TTML_PARSER_NATIVE,
        stableName = "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative",
        fallbackName = { it.endsWith(".ttml.javanative.TTMLParser\$TTMLParserNative") },
        contract = ::hasTtmlParserNativeContract,
    )

    /**
     * PlayerLyricsViewFragment.I2(SongInfoPtr) — the lyrics installation
     * entry point. The contract requires the exact name "I2" plus the
     * SongInfoPtr shape, so the same-shaped R2(SongInfoPtr) can never be
     * selected silently: a version whose I2 is missing resolves Missing even
     * when R2 is present.
     */
    val LyricsInstallMethod = methodSymbol(
        id = "lyrics-install-method",
        profileOwner = TargetSymbolId.LYRICS_FRAGMENT,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = ::isLyricsInstallMethod,
    )

    val PlayerMetadataPublishMethod = TargetSymbolKey(
        id = "player-metadata-publish-method",
        profileBound = true,
        profileCandidates = { profile ->
            profile?.exactClasses?.get(TargetSymbolId.PLAYER_METADATA_HUB)
                ?.let(::load)
                ?.declaredMethods
                ?.filter(::isPlayerMetadataPublishMethod)
                .orEmpty()
        },
        structuralCandidates = {
            val metadataType = metadataConverterCandidates().singleOrNull()
                ?.parameterTypes
                ?.singleOrNull()
            if (metadataType == null) {
                emptyList()
            } else {
                methods(::isShortPlayerClass) { method ->
                    isStructuralPlayerMetadataPublishMethod(method, metadataType)
                }
            }
        },
        identity = ::methodIdentity,
    )

    val MetadataToPlaybackItemMethod = methodSymbol(
        id = "metadata-to-playback-item-method",
        profileOwner = TargetSymbolId.METADATA_TO_ITEM_CONVERTER,
        fallbackOwner = ::isShortPlayerClass,
        contract = ::isMetadataToPlaybackItemMethod,
        structuralContract = ::isStructurallyMetadataToPlaybackItemMethod,
    )

    val LyricsAvailabilityPredicate = methodSymbol(
        id = "lyrics-availability-predicate",
        profileOwner = TargetSymbolId.LYRICS_AVAILABILITY_OWNER,
        fallbackOwner = ::isShortPlayerClass,
        contract = ::isLyricsAvailabilityPredicate,
        structuralContract = ::isStructurallyLyricsAvailabilityPredicate,
    )

    val TtmlSongInfoFromTtml = methodSymbol(
        id = "ttml-song-info-from-ttml",
        profileOwner = TargetSymbolId.TTML_PARSER_NATIVE,
        fallbackOwner = { it.endsWith(".ttml.javanative.TTMLParser\$TTMLParserNative") },
        contract = ::isTtmlSongInfoFromTtml,
    )

    /**
     * The fragment hierarchy's current lyrics item
     * (`com.apple.android.music.player.fragment.m#c` of type
     * `com.apple.android.music.model.BaseContentItem`). Apple's own I2 body
     * reads this field, calls `getId()` on the item and refuses any incoming
     * SongInfoPtr whose Adam ID differs — so the incoming pointer can be a
     * stale leftover from a previous song, and this field is the only
     * authoritative song identity for every I2 entry. The contract requires
     * the exact field name "c" plus the exact declared type, so a same-named
     * field of another type can never be selected silently.
     */
    val LyricsCurrentItemField = TargetSymbolKey(
        id = "lyrics-current-item-field",
        profileBound = true,
        profileCandidates = { profile ->
            profile?.exactClasses?.get(TargetSymbolId.LYRICS_CURRENT_ITEM_FIELD)
                ?.let(::load)
                ?.declaredFields
                ?.filter(::isLyricsCurrentItemField)
                .orEmpty()
        },
        structuralCandidates = {
            val lyricsFragment = load(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            )
            if (lyricsFragment == null) {
                fields(
                    { it.startsWith("com.apple.android.music.player.fragment.") },
                    ::isLyricsCurrentItemField,
                )
            } else {
                val hierarchyFields = fields(
                    { it.startsWith("com.apple.android.music.player.fragment.") },
                    ::isStructurallyLyricsCurrentItemField,
                ).filter { field ->
                    field.declaringClass.isAssignableFrom(lyricsFragment)
                }
                hierarchyFields.filter { it.name == "c" }.ifEmpty { hierarchyFields }
            }
        },
        identity = ::fieldIdentity,
    )

}

private fun classSymbol(
    id: String,
    profileId: TargetSymbolId? = null,
    stableName: String? = null,
    fallbackName: (String) -> Boolean,
    contract: (Class<*>) -> Boolean,
): TargetSymbolKey<Class<*>> = TargetSymbolKey(
    id = id,
    profileBound = profileId != null,
    profileCandidates = { profile ->
        profileId?.let { profile?.exactClasses?.get(it) }
            ?.let(::load)
            ?.takeIf(contract)
            ?.let(::listOf)
            .orEmpty()
    },
    stableCandidates = {
        stableName?.let(::load)?.takeIf(contract)?.let(::listOf).orEmpty()
    },
    structuralCandidates = { classes(fallbackName, contract) },
    identity = { it.name },
)

private fun methodSymbol(
    id: String,
    profileOwner: TargetSymbolId,
    fallbackOwner: (String) -> Boolean,
    contract: (Method) -> Boolean,
    structuralContract: (Method) -> Boolean = contract,
): TargetSymbolKey<Method> = TargetSymbolKey(
    id = id,
    profileBound = true,
    profileCandidates = { profile ->
        profile?.exactClasses?.get(profileOwner)
            ?.let(::load)
            ?.declaredMethods
            ?.filter(contract)
            .orEmpty()
    },
    structuralCandidates = { methods(fallbackOwner, structuralContract) },
    identity = ::methodIdentity,
)

private fun hasLyricsChromeContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
        method.name == "a2" &&
            !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, IntArray::class.java),
            )
    } && candidate.declaredMethods.any { method ->
        method.name == "f2" &&
            !Modifier.isStatic(method.modifiers) &&
            View::class.java.isAssignableFrom(method.returnType) &&
            method.parameterTypes.isEmpty()
    }

private fun hasStackedNavigationMenuContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
        method.name == "onMeasure" &&
            !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
    }

private fun isEditorialVideoUrlSelector(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.returnType == String::class.java &&
        method.parameterTypes.size == 3 &&
        method.parameterTypes[0].name == "com.apple.android.music.model.Song" &&
        method.parameterTypes[1] == Float::class.javaPrimitiveType &&
        method.parameterTypes[2].isArray &&
        method.parameterTypes[2].componentType?.name ==
        "com.apple.android.music.mediaapi.models.internals.EditorialVideo\$Flavor"

private fun isLyricsHighlightCallback(method: Method, vectorClass: Class<*>): Boolean =
    method.name == "call" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.size == 3 &&
        method.parameterTypes[0] == Long::class.javaPrimitiveType &&
        (
            method.parameterTypes[1] == vectorClass ||
                vectorClass.isAssignableFrom(method.parameterTypes[1])
            ) &&
        method.parameterTypes[2] == Long::class.javaPrimitiveType

private fun isLyricsSessionProcessor(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "processEvents" &&
        method.returnType == Long::class.javaPrimitiveType &&
        method.parameterTypes.size == 7 &&
        method.parameterTypes[0].name.endsWith(
            ".ttml.javanative.model.SongInfo\$SongInfoPtr",
        ) &&
        method.parameterTypes[1] == Long::class.javaPrimitiveType

private fun isLyricsInstallMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "I2" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.size == 1 &&
        method.parameterTypes[0].name.endsWith(
            ".ttml.javanative.model.SongInfo\$SongInfoPtr",
        )

private fun isPlayerMetadataPublishMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "g" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.singleOrNull()?.name == "v3.v"

private fun isMetadataToPlaybackItemMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.name == "b" &&
        method.parameterTypes.singleOrNull()?.name == "v3.v" &&
        method.returnType.name == "com.apple.android.music.model.PlaybackItem"

private fun isStructurallyMetadataToPlaybackItemMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.singleOrNull()?.isPrimitive == false &&
        method.returnType.name == "com.apple.android.music.model.PlaybackItem" &&
        method.declaringClass.declaredMethods.any { sibling ->
            Modifier.isStatic(sibling.modifiers) &&
                sibling.parameterTypes.contentEquals(method.parameterTypes) &&
                sibling.returnType.name == "com.apple.android.music.model.BaseContentItem"
        }

private fun isLyricsAvailabilityPredicate(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.name == "i" &&
        method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.singleOrNull()?.name == "com.apple.android.music.model.PlaybackItem"

private fun isStructurallyLyricsAvailabilityPredicate(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.singleOrNull()?.name == "com.apple.android.music.model.PlaybackItem" &&
        method.declaringClass.declaredMethods.any { sibling ->
            Modifier.isStatic(sibling.modifiers) &&
                sibling.returnType == Boolean::class.javaPrimitiveType &&
                sibling.parameterTypes.size == 2 &&
                sibling.parameterTypes.all {
                    it.name == "com.apple.android.music.model.PlaybackItem"
                }
        }

private fun isTtmlSongInfoFromTtml(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "songInfoFromTTML" &&
        method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
        method.returnType.name.endsWith(".ttml.javanative.model.SongInfo\$SongInfoPtr")

private fun isLyricsCurrentItemField(field: Field): Boolean =
    !Modifier.isStatic(field.modifiers) &&
        field.name == "c" &&
        field.type.name == "com.apple.android.music.model.BaseContentItem"

private fun isStructurallyLyricsCurrentItemField(field: Field): Boolean =
    !Modifier.isStatic(field.modifiers) &&
        field.type.name == "com.apple.android.music.model.BaseContentItem"

private fun TargetClassIndex.metadataConverterCandidates(): List<Method> =
    methods(::isShortPlayerClass, ::isStructurallyMetadataToPlaybackItemMethod)

private fun isStructuralPlayerMetadataPublishMethod(method: Method, metadataType: Class<*>): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name != "onMediaMetadataChanged" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.singleOrNull() == metadataType &&
        method.declaringClass.declaredMethods.any { sibling ->
            !Modifier.isStatic(sibling.modifiers) &&
                sibling.name == "onMediaMetadataChanged" &&
                sibling.returnType == Void.TYPE &&
                sibling.parameterTypes.singleOrNull() == metadataType
        }

private fun isShortPlayerClass(name: String): Boolean =
    name.startsWith("com.apple.android.music.player.") &&
        name.substringAfterLast('.').substringBefore('$').length <= 3

private fun hasSongInfoPtrContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
        method.name == "get" &&
            method.parameterCount == 0 &&
            method.returnType.name.endsWith(".ttml.javanative.model.SongInfo\$SongInfoNative")
    }

private fun hasSongInfoNativeContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
        method.name == "getSections" &&
            method.parameterCount == 0 &&
            method.returnType.name.endsWith(".ttml.javanative.model.LyricsSectionVector")
    } && candidate.declaredMethods.any { method ->
        method.name == "getAdamId" &&
            method.parameterCount == 0 &&
            method.returnType == Long::class.javaPrimitiveType
    } && candidate.declaredMethods.any { method ->
        method.name == "setAdamId" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Long::class.javaPrimitiveType
    }

private fun hasTtmlParserNativeContract(candidate: Class<*>): Boolean =
    candidate.declaredConstructors.any { constructor ->
        constructor.parameterCount == 0 && !Modifier.isPrivate(constructor.modifiers)
    } && candidate.declaredMethods.any(::isTtmlSongInfoFromTtml)

private fun methodIdentity(method: Method): String = buildString {
    append(method.declaringClass.name).append('#').append(method.name).append('(')
    append(method.parameterTypes.joinToString(",") { it.name })
    append("):").append(method.returnType.name)
}

private fun fieldIdentity(field: Field): String = buildString {
    append(field.declaringClass.name).append('#').append(field.name).append(':')
        .append(field.type.name)
}
