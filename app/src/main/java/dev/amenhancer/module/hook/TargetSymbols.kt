package dev.amenhancer.module.hook

import android.view.View
import dev.amenhancer.module.ModuleConstants
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale

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

    fun hierarchyMethods(
        namePredicate: (String) -> Boolean,
        contract: (Method) -> Boolean,
    ): List<Method> = classes(namePredicate) { true }
        .flatMap { type -> methodsFromHierarchy(type, contract) }
        .distinctBy(::methodIdentity)

    fun hierarchyFields(
        namePredicate: (String) -> Boolean,
        contract: (Field) -> Boolean,
    ): List<Field> = classes(namePredicate) { true }
        .flatMap { type -> fieldsFromHierarchy(type, contract) }
        .distinctBy(::fieldIdentity)

    fun methodsFromHierarchy(type: Class<*>, contract: (Method) -> Boolean): List<Method> =
        generateSequence(type) { it.superclass }
            .flatMap { current ->
                current.declaredMethods.asSequence().filter { method ->
                    runCatching { contract(method) }.getOrDefault(false)
                }
            }
            .distinctBy(::methodIdentity)
            .toList()

    fun fieldsFromHierarchy(type: Class<*>, contract: (Field) -> Boolean): List<Field> =
        generateSequence(type) { it.superclass }
            .flatMap { current ->
                current.declaredFields.asSequence().filter { field ->
                    runCatching { contract(field) }.getOrDefault(false)
                }
            }
            .distinctBy(::fieldIdentity)
            .toList()
}

internal class TargetSymbolKey<T : Any>(
    val id: String,
    internal val profileCandidates: TargetClassIndex.(AppleMusicProfile?) -> List<T> = { emptyList() },
    internal val profilePolicy: ProfilePolicy = ProfilePolicy.NO_PROFILE,
    internal val stableCandidates: TargetClassIndex.() -> List<T> = { emptyList() },
    internal val structuralCandidates: TargetClassIndex.() -> List<T>,
    internal val identity: (T) -> String,
)

internal enum class ProfilePolicy {
    NO_PROFILE,
    /** The verified identity is authoritative; a stale profile degrades this symbol. */
    EXACT_REQUIRED,
    /** Try the verified identity first, then the symbol's reviewed safe fallback contract. */
    EXACT_PREFERRED,
}

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
        if (profile != null && symbol.profilePolicy != ProfilePolicy.NO_PROFILE) {
            select(
                symbol,
                symbol.profileCandidates(index, profile),
                SymbolMatch.VERSION_PROFILE,
            )?.let { return it }
            if (symbol.profilePolicy == ProfilePolicy.EXACT_REQUIRED) {
                return TargetResolution.Missing(symbol.id, profile.id)
            }
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
    val exactMethods: Map<TargetSymbolId, String> = emptyMap(),
    val exactFields: Map<TargetSymbolId, String> = emptyMap(),
)

internal enum class TargetSymbolId {
    PLAYER_CONTROLLER,
    PLAYER_ACTIVITY,
    PLAYER_ACTIVITY_CREATE_STACKED_NAVIGATION_HOLDER,
    PLAYER_ACTIVITY_ROOT,
    PLAYER_ACTIVITY_BEHAVIOR_FIELD,
    STATIC_COLLAPSED_BEHAVIOR,
    STATIC_COLLAPSED_INTERCEPT_METHOD,
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
    LYRICS_ITEM_UPDATE_METHOD,
    PLAYER_METADATA_HUB,
    METADATA_TO_ITEM_CONVERTER,
    LYRICS_AVAILABILITY_OWNER,
    MEDIA_ENTITY_TO_SONG_CONVERTER,
    STORE_FRONT_LANGUAGE_ARRAY_OWNER,
    STORE_FRONT_LANGUAGE_ARRAY_METHOD,
}

private object AppleMusicProfiles {
    private val appleMusic650 = AppleMusicProfile(
        id = "apple-music-6.5.0-1580",
        exactClasses = mapOf(
            TargetSymbolId.PLAYER_CONTROLLER to "com.apple.android.music.player.fragment.w0",
            TargetSymbolId.PLAYER_ACTIVITY to "com.apple.android.music.common.activity.PlayerActivity",
            TargetSymbolId.STATIC_COLLAPSED_BEHAVIOR to
                "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior",
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
            TargetSymbolId.MEDIA_ENTITY_TO_SONG_CONVERTER to "y8.B",
            TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_OWNER to "J5.a",
        ),
        exactMethods = mapOf(
            TargetSymbolId.PLAYER_ACTIVITY_CREATE_STACKED_NAVIGATION_HOLDER to "k1",
            TargetSymbolId.PLAYER_ACTIVITY_ROOT to "n0",
            TargetSymbolId.STATIC_COLLAPSED_INTERCEPT_METHOD to "h",
            TargetSymbolId.LYRICS_ITEM_UPDATE_METHOD to "o2",
            TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_METHOD to "b",
        ),
        exactFields = mapOf(
            TargetSymbolId.PLAYER_ACTIVITY_BEHAVIOR_FIELD to "c1",
        ),
    )

    private val appleMusic651 = AppleMusicProfile(
        id = "apple-music-6.5.1-1583",
        exactClasses = mapOf(
            TargetSymbolId.PLAYER_CONTROLLER to "com.apple.android.music.player.fragment.q0",
            TargetSymbolId.PLAYER_ACTIVITY to "com.apple.android.music.common.activity.PlayerActivity",
            TargetSymbolId.STATIC_COLLAPSED_BEHAVIOR to
                "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior",
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
            TargetSymbolId.MEDIA_ENTITY_TO_SONG_CONVERTER to "y8.B",
            TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_OWNER to "J5.a",
        ),
        exactMethods = mapOf(
            TargetSymbolId.PLAYER_ACTIVITY_CREATE_STACKED_NAVIGATION_HOLDER to "j1",
            TargetSymbolId.PLAYER_ACTIVITY_ROOT to "l1",
            TargetSymbolId.STATIC_COLLAPSED_INTERCEPT_METHOD to "h",
            TargetSymbolId.LYRICS_ITEM_UPDATE_METHOD to "o2",
            TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_METHOD to "b",
        ),
        exactFields = mapOf(
            TargetSymbolId.PLAYER_ACTIVITY_BEHAVIOR_FIELD to "c1",
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

    val PlayerControllerInitialize = methodSymbol(
        id = "player-controller-initialize",
        profileOwner = TargetSymbolId.PLAYER_CONTROLLER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::isPlayerControllerInitialize,
        structuralContract = ::isStructuralPlayerControllerInitialize,
    )

    val PlayerControllerCreateView = methodSymbol(
        id = "player-controller-create-view",
        profileOwner = TargetSymbolId.PLAYER_CONTROLLER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::isPlayerControllerCreateView,
        structuralContract = ::isStructuralPlayerControllerCreateView,
    )

    val PlayerControllerSelectPane = methodSymbol(
        id = "player-controller-select-pane",
        profileOwner = TargetSymbolId.PLAYER_CONTROLLER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::isPlayerControllerSelectPane,
        structuralContract = ::isStructuralPlayerControllerSelectPane,
    )

    val PlayerActivity = classSymbol(
        id = "player-activity",
        profileId = TargetSymbolId.PLAYER_ACTIVITY,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackName = { it.endsWith(".common.activity.PlayerActivity") },
        contract = { true },
    )

    val PlayerActivityCreateStackedNavigationHolder = methodSymbol(
        id = "player-activity-create-stacked-navigation-holder",
        profileOwner = TargetSymbolId.PLAYER_ACTIVITY,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactMethodId = TargetSymbolId.PLAYER_ACTIVITY_CREATE_STACKED_NAVIGATION_HOLDER,
        fallbackOwner = { it.endsWith(".common.activity.PlayerActivity") },
        contract = ::isPlayerActivityCreateStackedNavigationHolder,
    )

    val PlayerActivityRoot = methodSymbol(
        id = "player-activity-root",
        profileOwner = TargetSymbolId.PLAYER_ACTIVITY,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactMethodId = TargetSymbolId.PLAYER_ACTIVITY_ROOT,
        searchHierarchy = true,
        fallbackOwner = { it.endsWith(".common.activity.PlayerActivity") },
        contract = ::isPlayerActivityRoot,
    )

    val PlayerActivityBehaviorField = fieldSymbol(
        id = "player-activity-bottom-sheet-behavior",
        profileOwner = TargetSymbolId.PLAYER_ACTIVITY,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactFieldId = TargetSymbolId.PLAYER_ACTIVITY_BEHAVIOR_FIELD,
        searchHierarchy = true,
        fallbackOwner = { it.endsWith(".common.activity.PlayerActivity") },
        contract = ::isPlayerActivityBehaviorField,
    )

    val StaticCollapsedInterceptMethod = methodSymbol(
        id = "static-collapsed-intercept-method",
        profileOwner = TargetSymbolId.STATIC_COLLAPSED_BEHAVIOR,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactMethodId = TargetSymbolId.STATIC_COLLAPSED_INTERCEPT_METHOD,
        fallbackOwner = { it.endsWith(".StaticCollapsedBottomSheetBehavior") },
        contract = ::isStaticCollapsedIntercept,
    )

    val EditorialVideoUrlSelector = methodSymbol(
        id = "editorial-video-url-selector",
        profileOwner = TargetSymbolId.EDITORIAL_VIDEO_OWNER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { name ->
            name.startsWith("com.apple.android.music.player.") &&
                name.substringAfterLast('.').substringBefore('$').length <= 3
        },
        contract = ::isEditorialVideoUrlSelector,
    )

    val LyricsFragment = classSymbol(
        id = "lyrics-fragment",
        profileId = TargetSymbolId.LYRICS_FRAGMENT,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackName = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = { true },
    )

    val LyricsFragmentOnResume = methodSymbol(
        id = "lyrics-fragment-on-resume",
        profileOwner = TargetSymbolId.LYRICS_FRAGMENT,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = ::isLyricsFragmentOnResume,
    )

    val LyricsFragmentUpdateMetrics = methodSymbol(
        id = "lyrics-fragment-update-metrics",
        profileOwner = TargetSymbolId.LYRICS_FRAGMENT,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = ::isLyricsFragmentUpdateMetrics,
    )

    val LyricsChromeFragment = classSymbol(
        id = "lyrics-chrome-fragment",
        profileId = TargetSymbolId.LYRICS_CHROME,
        fallbackName = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::hasLyricsChromeContract,
    )

    val LyricsChromeAnimate = methodSymbol(
        id = "lyrics-chrome-animate",
        profileOwner = TargetSymbolId.LYRICS_CHROME,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.startsWith("com.apple.android.music.player.fragment.") },
        contract = ::isLyricsChromeAnimate,
        structuralContract = ::isStructuralLyricsChromeAnimate,
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
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        stableName = "com.apple.android.music.ttml.javanative.model.LyricsLineVector",
        fallbackName = { it.endsWith(".ttml.javanative.model.LyricsLineVector") },
        contract = { true },
    )

    val LyricsSessionProcessor = methodSymbol(
        id = "lyrics-session-processor",
        profileOwner = TargetSymbolId.LYRICS_EVENT_PROCESSOR,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.endsWith(".ttml.SongInfoTimeProcessor") },
        contract = ::isLyricsSessionProcessor,
    )

    val LyricsHighlightCallback = TargetSymbolKey(
        id = "lyrics-highlight-callback",
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
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
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        stableName = "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
        fallbackName = { it.endsWith(".PlayerLyricsViewModel", ignoreCase = true) },
        contract = { true },
    )

    val LyricsViewModelNotifyWordHighlight = methodSymbol(
        id = "lyrics-view-model-notify-word-highlight",
        profileOwner = TargetSymbolId.LYRICS_VIEW_MODEL,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewModel", ignoreCase = true) },
        contract = ::isLyricsViewModelNotifyWordHighlight,
    )

    val LyricsViewModelSetCurrentHighlightedLine = methodSymbol(
        id = "lyrics-view-model-set-current-highlighted-line",
        profileOwner = TargetSymbolId.LYRICS_VIEW_MODEL,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewModel", ignoreCase = true) },
        contract = ::isLyricsViewModelSetCurrentHighlightedLine,
    )

    val StackedNavigationMenu = classSymbol(
        id = "stacked-navigation-menu",
        profileId = TargetSymbolId.STACKED_NAVIGATION_MENU,
        stableName = "Hd.b",
        fallbackName = { false },
        contract = ::hasStackedNavigationMenuContract,
    )

    val StackedNavigationMenuOnMeasure = methodSymbol(
        id = "stacked-navigation-menu-on-measure",
        profileOwner = TargetSymbolId.STACKED_NAVIGATION_MENU,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = { it == "Hd.b" },
        contract = ::isStackedNavigationMenuOnMeasure,
    )

    val SongInfoPtr = classSymbol(
        id = "song-info-ptr",
        profileId = TargetSymbolId.SONG_INFO_PTR,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        stableName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
        fallbackName = { it.endsWith(".ttml.javanative.model.SongInfo\$SongInfoPtr") },
        contract = ::hasSongInfoPtrContract,
    )

    val SongInfoNative = classSymbol(
        id = "song-info-native",
        profileId = TargetSymbolId.SONG_INFO_NATIVE,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        stableName = "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative",
        fallbackName = { it.endsWith(".ttml.javanative.model.SongInfo\$SongInfoNative") },
        contract = ::hasSongInfoNativeContract,
    )

    val TtmlParserNative = classSymbol(
        id = "ttml-parser-native",
        profileId = TargetSymbolId.TTML_PARSER_NATIVE,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
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

    /**
     * PlayerLyricsViewFragment.o2(v3.v, BaseContentItem, flags$c) — the
     * natural-transition item update entry point. The contract requires the
     * exact name "o2", the verified v3.v and BaseContentItem parameter types,
     * and a flags holder declared as a member of the fragment's immediate
     * superclass whose only boolean fields are exactly {a, b, c}; Apple's own
     * item-changed signal is the flags holder's `a` field. A version whose
     * o2 shape differs resolves Missing or Ambiguous instead of silently
     * selecting an unrelated method.
     */
    val LyricsItemUpdateMethod = methodSymbol(
        id = "lyrics-item-update-method",
        profileOwner = TargetSymbolId.LYRICS_FRAGMENT,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactMethodId = TargetSymbolId.LYRICS_ITEM_UPDATE_METHOD,
        fallbackOwner = { it.endsWith(".PlayerLyricsViewFragment") },
        contract = ::isLyricsItemUpdateMethod,
        structuralContract = ::isLyricsItemUpdateMethod,
    )

    val PlayerMetadataPublishMethod = TargetSymbolKey(
        id = "player-metadata-publish-method",
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        profileCandidates = { profile ->
            runCatching {
                profile?.exactClasses?.get(TargetSymbolId.PLAYER_METADATA_HUB)
                    ?.let(::load)
                    ?.declaredMethods
                    ?.filter(::isPlayerMetadataPublishMethod)
                    .orEmpty()
            }.getOrDefault(emptyList())
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
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = ::isShortPlayerClass,
        contract = ::isMetadataToPlaybackItemMethod,
        structuralContract = ::isStructurallyMetadataToPlaybackItemMethod,
    )

    val LyricsAvailabilityPredicate = methodSymbol(
        id = "lyrics-availability-predicate",
        profileOwner = TargetSymbolId.LYRICS_AVAILABILITY_OWNER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        fallbackOwner = ::isShortPlayerClass,
        contract = ::isLyricsAvailabilityPredicate,
        structuralContract = ::isStructurallyLyricsAvailabilityPredicate,
    )

    val TtmlSongInfoFromTtml = methodSymbol(
        id = "ttml-song-info-from-ttml",
        profileOwner = TargetSymbolId.TTML_PARSER_NATIVE,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
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
     * the exact field name "c" plus the exact declared type. If a profile pin
     * is stale, structural discovery is restricted to the lyrics fragment's
     * hierarchy and accepts only a unique BaseContentItem field.
     */
    val LyricsCurrentItemField = TargetSymbolKey(
        id = "lyrics-current-item-field",
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        profileCandidates = { profile ->
            runCatching {
                profile?.exactClasses?.get(TargetSymbolId.LYRICS_CURRENT_ITEM_FIELD)
                    ?.let(::load)
                    ?.declaredFields
                    ?.filter(::isLyricsCurrentItemField)
                    .orEmpty()
            }.getOrDefault(emptyList())
        },
        structuralCandidates = {
            val lyricsFragment = load(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            )
            if (lyricsFragment == null) {
                emptyList()
            } else {
                val hierarchyFields = fields(
                    { it.startsWith("com.apple.android.music.player.fragment.") },
                    ::isStructurallyLyricsCurrentItemField,
                ).filter { field ->
                    field.declaringClass.isAssignableFrom(lyricsFragment)
                }
                hierarchyFields
            }
        },
        identity = ::fieldIdentity,
    )

    /** Stable public seam; the implementation class and its singleton remain obfuscated. */
    val MediaLibraryType = classSymbol(
        id = "media-library-type",
        stableName = "com.apple.android.medialibrary.library.MediaLibrary",
        fallbackName = { it == "com.apple.android.medialibrary.library.MediaLibrary" },
        contract = { it.isInterface },
    )

    /** Finds the implementation's no-argument singleton accessor (W() in 6.5.1). */
    val MediaLibrarySingletonMethod = TargetSymbolKey(
        id = "media-library-singleton-method",
        stableCandidates = {
            val mediaLibrary = load("com.apple.android.medialibrary.library.MediaLibrary")
                ?: return@TargetSymbolKey emptyList()
            classes(
                namePredicate = { it.startsWith("com.apple.android.medialibrary.library.") },
                contract = { candidate ->
                    !candidate.isInterface && mediaLibrary.isAssignableFrom(candidate)
                },
            ).flatMap { candidate ->
                candidate.declaredMethods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        mediaLibrary.isAssignableFrom(method.returnType)
                }
            }.distinctBy(::methodIdentity)
        },
        structuralCandidates = {
            val mediaLibrary = load("com.apple.android.medialibrary.library.MediaLibrary")
                ?: return@TargetSymbolKey emptyList()
            classes(
                namePredicate = { it.startsWith("com.apple.android.medialibrary.library.") },
                contract = { candidate ->
                    !candidate.isInterface && mediaLibrary.isAssignableFrom(candidate)
                },
            ).flatMap { candidate ->
                candidate.declaredMethods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        mediaLibrary.isAssignableFrom(method.returnType)
                }
            }.distinctBy(::methodIdentity)
        },
        identity = ::methodIdentity,
    )

    /** MediaLibrary.r0(MediaLibrary$g) in 6.5.1; the name is deliberately not pinned. */
    val MediaLibraryUpdateMethod = TargetSymbolKey(
        id = "media-library-update-method",
        structuralCandidates = {
            mediaLibraryImplementationMethods(::isMediaLibraryUpdateMethod)
        },
        identity = ::methodIdentity,
    )

    /** Stable query seam used by AMTool to enumerate songs after a poll. */
    val MediaLibraryItemsQueryMethod = TargetSymbolKey(
        id = "media-library-items-query-method",
        stableCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryItemsQueryMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryItemsQueryMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Stable query seam used by AMTool to enumerate albums after a poll. */
    val MediaLibraryAlbumsQueryMethod = TargetSymbolKey(
        id = "media-library-albums-query-method",
        stableCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryAlbumsQueryMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryAlbumsQueryMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    private fun TargetClassIndex.mediaLibraryImplementationMethods(
        contract: (Method) -> Boolean,
    ): List<Method> {
        val mediaLibrary = load("com.apple.android.medialibrary.library.MediaLibrary")
            ?: return emptyList()
        return classes(
            namePredicate = { it.startsWith("com.apple.android.medialibrary.library.") },
            contract = { candidate ->
                !candidate.isInterface && mediaLibrary.isAssignableFrom(candidate)
            },
        ).flatMap { candidate ->
            candidate.declaredMethods.filter { method ->
                runCatching { contract(method) }.getOrDefault(false)
            }
        }.distinctBy(::methodIdentity)
    }

    val MediaLibraryReadyMethod = TargetSymbolKey(
        id = "media-library-ready-method",
        stableCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryReadyMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryReadyMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Stable interface seam used to reach Apple's native catalog refresh after polling. */
    val MediaLibraryNativePointerMethod = TargetSymbolKey(
        id = "media-library-native-pointer-method",
        stableCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryNativePointerMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.medialibrary.library.MediaLibrary")
                ?.declaredMethods
                ?.filter(::isMediaLibraryNativePointerMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val MediaLibraryNativeCatalogRefreshMethod = TargetSymbolKey(
        id = "media-library-native-catalog-refresh-method",
        stableCandidates = {
            load("com.apple.android.medialibrary.javanative.medialibrary.library.SVMediaLibrary\$SVMediaLibraryNative")
                ?.declaredMethods
                ?.filter(::isMediaLibraryNativeCatalogRefreshMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.medialibrary.javanative.medialibrary.library.SVMediaLibrary\$SVMediaLibraryNative")
                ?.declaredMethods
                ?.filter(::isMediaLibraryNativeCatalogRefreshMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val ConfigurationStoreStoreFrontLanguageMethod = TargetSymbolKey(
        id = "configuration-store-storefront-language-method",
        stableCandidates = {
            load("com.apple.android.music.storeapi.stores.ConfigurationStore")
                ?.declaredMethods
                ?.filter(::isConfigurationStoreStoreFrontLanguageMethod)
                .orEmpty()
        },
        structuralCandidates = {
            methods(
                { it.startsWith("com.apple.android.music.storeapi.stores.") },
                ::isConfigurationStoreStoreFrontLanguageMethod,
            )
        },
        identity = ::methodIdentity,
    )

    /** Stable StoreApi seam used by AMTool to replace the Accept-Language header. */
    val StoreApiHeadersSetMethod = TargetSymbolKey(
        id = "store-api-headers-set-method",
        stableCandidates = {
            load("com.apple.android.music.storeapi.modelprivate.Headers")
                ?.declaredMethods
                ?.filter(::isStoreApiHeadersSetMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.storeapi.modelprivate.Headers")
                ?.declaredMethods
                ?.filter(::isStoreApiHeadersSetMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * The Catalog lookup path also receives a request map; patch its explicit
     * language header on the concrete repository implementation.
     *
     * `MediaApiRepository` only declares this suspend method.  Hooking that
     * interface declaration is rejected by the runtime, while the host's
     * `MediaApiRepositoryImpl` owns the executable body.  Keep the interface
     * out of both candidate sets so a missing or changed implementation
     * degrades to Missing instead of installing an abstract hook.
     */
    val MediaApiRepositoryGetEntitiesWithIdsMethod = TargetSymbolKey(
        id = "media-api-repository-get-entities-with-ids-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.repository.MediaApiRepositoryImpl")
                ?.takeUnless { it.isInterface }
                ?.declaredMethods
                ?.filter(::isMediaApiRepositoryGetEntitiesWithIdsMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.repository.MediaApiRepositoryImpl")
                ?.takeUnless { it.isInterface }
                ?.declaredMethods
                ?.filter(::isMediaApiRepositoryGetEntitiesWithIdsMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * Invocation-only variant of the Catalog seam.  AMTool reflects the
     * stable `MediaApiRepository` interface Method and invokes it on the
     * Holder's concrete instance; this Method must not be reused as an
     * executable Hook target because the interface declaration is abstract.
     */
    val MediaApiRepositoryGetEntitiesWithIdsInvocationMethod = TargetSymbolKey(
        id = "media-api-repository-get-entities-with-ids-invocation-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.repository.MediaApiRepository")
                ?.declaredMethods
                ?.filter(::isMediaApiRepositoryGetEntitiesWithIdsInvocationMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.repository.MediaApiRepository")
                ?.declaredMethods
                ?.filter(::isMediaApiRepositoryGetEntitiesWithIdsInvocationMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** AMTool ia(27) `languageMethod`: `J5.a.b(Context): String[]`. */
    val StoreFrontLanguageArrayMethod = methodSymbol(
        id = "store-front-language-array-method",
        profileOwner = TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_OWNER,
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        exactMethodId = TargetSymbolId.STORE_FRONT_LANGUAGE_ARRAY_METHOD,
        fallbackOwner = ::isObfuscatedTopLevelClass,
        contract = ::isStoreFrontLanguageArrayMethod,
    )

    /**
     * AMTool ia(28) "iCloud Accept-Language helper": an interface method that
     * derives the Accept-Language value from `Locale.getDefault()`.  The
     * DexKit rule anchors on the method body; reflection only sees the
     * interface signature, so the symbol degrades to missing on renamed
     * surfaces without touching the other language hooks.
     */
    val ICloudAcceptLanguageHelperMethod = TargetSymbolKey(
        id = "icloud-accept-language-helper-method",
        structuralCandidates = {
            methods(
                { it.contains("icloud", ignoreCase = true) },
                ::isICloudAcceptLanguageHelperMethod,
            )
        },
        identity = ::methodIdentity,
    )

    /** AMTool ia(29) "StoreApi header map helper": map returned with Accept-Language. */
    val StoreApiHeaderMapMethod = TargetSymbolKey(
        id = "store-api-header-map-method",
        structuralCandidates = {
            methods(
                { it.startsWith("com.apple.android.music.storeapi.") },
                ::isStoreApiHeaderMapMethod,
            )
        },
        identity = ::methodIdentity,
    )

    /**
     * Apple Music 6.5.1's actual commerce request seam.  The method is
     * private on `commerce.jsinterface.ITunes`, so broad StoreApi map scans
     * never see it; keep this exact resolver separate and fail-open.
     */
    val ITunesGetHeadersMapMethod = TargetSymbolKey(
        id = "itunes-get-headers-map-method",
        stableCandidates = {
            load("com.apple.android.music.commerce.jsinterface.ITunes")
                ?.declaredMethods
                ?.filter(::isITunesGetHeadersMapMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.commerce.jsinterface.ITunes")
                ?.declaredMethods
                ?.filter(::isITunesGetHeadersMapMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool's 6.5.1 `locale-header-map` seam: the obfuscated static
     * `ma.c.a(aa.d): Map` helper whose body builds the request header map.
     * The exact class/method identity is pinned for the supported host; a
     * missing class simply leaves this one seam disabled.
     */
    val LocaleHeaderMapMethod = TargetSymbolKey(
        id = "locale-header-map-method",
        stableCandidates = {
            load("ma.c")
                ?.declaredMethods
                ?.filter(::isLocaleHeaderMapMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("ma.c")
                ?.declaredMethods
                ?.filter(::isLocaleHeaderMapMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool ia(26) "MediaApi language param choke": on Apple Music 6.5.1
     * this is the static `s8.F.c0(Map): LinkedHashMap` helper.  Keep the
     * older package-shaped fallback for hosts that expose a non-obfuscated
     * equivalent, but prefer the pinned 6.5.1 identity whenever available.
     */
    val MediaApiLanguageParamMethod = TargetSymbolKey(
        id = "media-api-language-param-method",
        stableCandidates = {
            load("s8.F")
                ?.declaredMethods
                ?.filter(::isMediaApiLanguageParamMethod)
                .orEmpty()
        },
        structuralCandidates = {
            methods(
                { it.startsWith("com.apple.android.music.mediaapi.") },
                ::isMediaApiLanguageParamMethod,
            )
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool's Store lookup seam (ot.java case 9): `Request.Builder.setParam
     * (String, String)` returning the builder.  The signature is confirmed by
     * the report; the injection behavior it drives is only partially
     * confirmed, so the hook stays conservative and fails open.
     */
    val StoreLookupSetParamMethod = TargetSymbolKey(
        id = "store-lookup-set-param-method",
        stableCandidates = {
            load("com.apple.android.music.storeapi.modelprivate.Request\$Builder")
                ?.declaredMethods
                ?.filter(::isStoreLookupSetParamMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.storeapi.modelprivate.Request\$Builder")
                ?.declaredMethods
                ?.filter(::isStoreLookupSetParamMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val MediaEntityType = classSymbol(
        id = "media-entity-type",
        stableName = "com.apple.android.music.mediaapi.models.MediaEntity",
        fallbackName = { it == "com.apple.android.music.mediaapi.models.MediaEntity" },
        contract = { !it.isInterface },
    )

    val MediaEntityGetTitleMethod = TargetSymbolKey(
        id = "media-entity-get-title-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetTitleMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetTitleMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val MediaEntityGetAttributesMethod = TargetSymbolKey(
        id = "media-entity-get-attributes-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetAttributesMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetAttributesMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val MediaEntityToCollectionItemViewMethod = TargetSymbolKey(
        id = "media-entity-to-collection-item-view-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool also hooks the concrete two-argument conversion overrides on
     * catalog Song and Album.  A call through the override does not dispatch
     * through MediaEntity's one-argument convenience method.
     */
    val SongToCollectionItemViewMethod = TargetSymbolKey(
        id = "song-to-collection-item-view-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.Song")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.Song")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** See [SongToCollectionItemViewMethod] for the concrete Album override. */
    val AlbumToCollectionItemViewMethod = TargetSymbolKey(
        id = "album-to-collection-item-view-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.Album")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.Album")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * Apple Music 6.5.1's player display/update seam:
     * `player.d1.y0(PlaybackItem, CollectionItemView, String, Context, View)`.
     * The method has no return value and its owner is stable, so a missing or
     * changed signature must leave this seam disabled instead of selecting a
     * nearby obfuscated five-argument method.
     */
    val PlayerActionSheetMethod = TargetSymbolKey(
        id = "player-action-sheet-method",
        stableCandidates = {
            load("com.apple.android.music.player.d1")
                ?.declaredMethods
                ?.filter(::isPlayerPlaybackItemViewUpdateMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.player.d1")
                ?.declaredMethods
                ?.filter(::isPlayerPlaybackItemViewUpdateMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Applies resolved action-sheet rows before they overwrite local titles. */
    val PlayerActionSheetResponseApplyMethod = TargetSymbolKey(
        id = "player-action-sheet-response-apply-method",
        stableCandidates = {
            load("com.apple.android.music.collection.mediaapi.fragment.G")
                ?.declaredMethods
                ?.filter(::isPlayerActionSheetResponseApplyMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.collection.mediaapi.fragment.G")
                ?.declaredMethods
                ?.filter(::isPlayerActionSheetResponseApplyMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * Native media-library conversion helper `v5.a.n(BasePlaybackItem,
     * SVEntityNative$SVEntitySRef): void`.  This exact static method populates
     * a playback model from a native entity; its parameter classes are part of
     * the contract so unrelated `v5.a.n` overloads cannot be selected.
     */
    val NativeLibrarySongConverterMethod = TargetSymbolKey(
        id = "native-library-song-converter-method",
        stableCandidates = {
            load("v5.a")
                ?.declaredMethods
                ?.filter(::isBasePlaybackItemNativeEntityPopulateMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("v5.a")
                ?.declaredMethods
                ?.filter(::isBasePlaybackItemNativeEntityPopulateMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Native media-library `SVEntitySRef -> model.Album` conversion helper. */
    val NativeLibraryAlbumConverterMethod = TargetSymbolKey(
        id = "native-library-album-converter-method",
        stableCandidates = {
            load("v5.a")
                ?.declaredMethods
                ?.filter(::isNativeEntityAlbumConverterMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("v5.a")
                ?.declaredMethods
                ?.filter(::isNativeEntityAlbumConverterMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * The compact display title used by the player page and the now-playing
     * bottom sheet. `mediaapi.models.Song` overrides it, so the base
     * declaration and the override are separate symbols; both are hooked so
     * virtual dispatch through either one is corrected.
     */
    val MediaEntityGetShortNameMethod = TargetSymbolKey(
        id = "media-entity-get-short-name-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetShortNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.MediaEntity")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetShortNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** The `mediaapi.models.Song` override of `getShortName()`. */
    val SongGetShortNameMethod = TargetSymbolKey(
        id = "song-get-short-name-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.Song")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetShortNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.Song")
                ?.declaredMethods
                ?.filter(::isMediaEntityGetShortNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AppleCurator and a few profile controllers call Attributes.getShortName
     * directly instead of going through MediaEntity.getShortName.
     */
    val AttributesGetShortNameMethod = TargetSymbolKey(
        id = "attributes-get-short-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetShortNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetShortNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Direct Attributes.getName callers can bypass MediaEntity.getTitle(). */
    val AttributesGetNameMethod = TargetSymbolKey(
        id = "attributes-get-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Verified Attributes accessors used by the artist/album metadata seam. */
    val AttributesGetArtistNameMethod = TargetSymbolKey(
        id = "attributes-get-artist-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetArtistNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetArtistNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * Attributes exposes the title used by several pages as an immutable
     * `internals.Title` object.  AMTool reads `Title.getStringForDisplay()`;
     * rewriting only `Attributes.getName()` therefore misses those pages.
     */
    val AttributesGetTitleMethod = TargetSymbolKey(
        id = "attributes-get-title-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetTitleMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetTitleMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** Some detail/profile pages render the title-without-name object directly. */
    val AttributesGetTitleWithoutNameMethod = TargetSymbolKey(
        id = "attributes-get-title-without-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetTitleWithoutNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetTitleWithoutNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** The immutable Title object is what several pages finally render. */
    val TitleGetStringForDisplayMethod = TargetSymbolKey(
        id = "title-get-string-for-display-method",
        stableCandidates = {
            load(TITLE_CLASS)
                ?.declaredMethods
                ?.filter(::isTitleGetStringForDisplayMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(TITLE_CLASS)
                ?.declaredMethods
                ?.filter(::isTitleGetStringForDisplayMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val AttributesSetArtistNameMethod = TargetSymbolKey(
        id = "attributes-set-artist-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesSetArtistNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesSetArtistNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val AttributesGetAlbumNameMethod = TargetSymbolKey(
        id = "attributes-get-album-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetAlbumNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesGetAlbumNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    val AttributesSetAlbumNameMethod = TargetSymbolKey(
        id = "attributes-set-album-name-method",
        stableCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesSetAlbumNameMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load(ATTRIBUTES_CLASS)
                ?.declaredMethods
                ?.filter(::isAttributesSetAlbumNameMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool's "MediaEntity -> model.Song" hook: the static conversion that
     * builds the `model.Song` bound by the now-playing bottom sheet. The
     * class is obfuscated but verified as `y8.B` in both 6.5.0 and 6.5.1;
     * the structural contract is the unique static
     * `(mediaapi.models.Song, Bundle) -> model.Song` signature.
     */
    val MediaEntityToSongConverterMethod = TargetSymbolKey(
        id = "media-entity-to-song-converter-method",
        profilePolicy = ProfilePolicy.EXACT_PREFERRED,
        profileCandidates = { profile ->
            runCatching {
                profile?.exactClasses?.get(TargetSymbolId.MEDIA_ENTITY_TO_SONG_CONVERTER)
                    ?.let(::load)
                    ?.declaredMethods
                    ?.filter(::isMediaEntityToSongConverterMethod)
                    .orEmpty()
            }.getOrDefault(emptyList())
        },
        structuralCandidates = {
            methods(::isObfuscatedTopLevelClass, ::isMediaEntityToSongConverterMethod)
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool's "Hook SearchSectionResultResponse.setData": the search
     * response seam that carries `List<MediaEntity>` results into the search
     * UI; each entry's display attributes are corrected from the catalog
     * cache (and target-language search entities are captured into it).
     */
    val SearchSectionResultResponseSetDataMethod = TargetSymbolKey(
        id = "search-section-result-response-set-data-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.internals.SearchResultsResponse\$SearchSectionResultResponse")
                ?.declaredMethods
                ?.filter(::isSearchSectionResultResponseSetDataMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.internals.SearchResultsResponse\$SearchSectionResultResponse")
                ?.declaredMethods
                ?.filter(::isSearchSectionResultResponseSetDataMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * The two-argument library conversion overrides
     * (`toCollectionItemView(Bundle, boolean)`) declared by LibrarySong and
     * LibraryAlbum. The one-argument MediaEntity hook never fires for them
     * because virtual dispatch resolves straight to these overrides.
     */
    val LibrarySongToCollectionItemViewMethod = TargetSymbolKey(
        id = "library-song-to-collection-item-view-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.LibrarySong")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.LibrarySong")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /** See [LibrarySongToCollectionItemViewMethod]. */
    val LibraryAlbumToCollectionItemViewMethod = TargetSymbolKey(
        id = "library-album-to-collection-item-view-method",
        stableCandidates = {
            load("com.apple.android.music.mediaapi.models.LibraryAlbum")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.mediaapi.models.LibraryAlbum")
                ?.declaredMethods
                ?.filter(::isLibraryEntityToCollectionItemViewMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    /**
     * AMTool's "Hook StorePlatform": every StoreApi page response exposes
     * its display items through this accessor as
     * `Map<String, CollectionItemView>`; the views are corrected from the
     * catalog cache without re-capturing their storefront titles.
     */
    val BaseStorePlatformResponseGetStorePlatformDataMethod = TargetSymbolKey(
        id = "base-store-platform-response-get-store-platform-data-method",
        stableCandidates = {
            load("com.apple.android.music.model.BaseStorePlatformResponse")
                ?.declaredMethods
                ?.filter(::isBaseStorePlatformResponseGetStorePlatformDataMethod)
                .orEmpty()
        },
        structuralCandidates = {
            load("com.apple.android.music.model.BaseStorePlatformResponse")
                ?.declaredMethods
                ?.filter(::isBaseStorePlatformResponseGetStorePlatformDataMethod)
                .orEmpty()
        },
        identity = ::methodIdentity,
    )

    private const val ATTRIBUTES_CLASS =
        "com.apple.android.music.mediaapi.models.internals.Attributes"
    private const val TITLE_CLASS =
        "com.apple.android.music.mediaapi.models.internals.Title"

}

private fun classSymbol(
    id: String,
    profileId: TargetSymbolId? = null,
    profilePolicy: ProfilePolicy = if (profileId == null) {
        ProfilePolicy.NO_PROFILE
    } else {
        ProfilePolicy.EXACT_REQUIRED
    },
    stableName: String? = null,
    fallbackName: (String) -> Boolean,
    contract: (Class<*>) -> Boolean,
): TargetSymbolKey<Class<*>> = TargetSymbolKey(
    id = id,
    profilePolicy = profilePolicy,
    profileCandidates = { profile ->
        runCatching {
            profileId?.let { profile?.exactClasses?.get(it) }
                ?.let(::load)
                ?.takeIf(contract)
                ?.let(::listOf)
                .orEmpty()
        }.getOrDefault(emptyList())
    },
    stableCandidates = {
        runCatching {
            stableName?.let(::load)?.takeIf(contract)?.let(::listOf).orEmpty()
        }.getOrDefault(emptyList())
    },
    structuralCandidates = { classes(fallbackName, contract) },
    identity = { it.name },
)

private fun methodSymbol(
    id: String,
    profileOwner: TargetSymbolId,
    profilePolicy: ProfilePolicy = ProfilePolicy.EXACT_REQUIRED,
    exactMethodId: TargetSymbolId? = null,
    searchHierarchy: Boolean = false,
    fallbackOwner: (String) -> Boolean,
    contract: (Method) -> Boolean,
    structuralContract: (Method) -> Boolean = contract,
): TargetSymbolKey<Method> = TargetSymbolKey(
    id = id,
    profilePolicy = profilePolicy,
    profileCandidates = { profile ->
        runCatching {
            val expectedMethodName = exactMethodId?.let { profile?.exactMethods?.get(it) }
            profile?.exactClasses?.get(profileOwner)
                ?.let(::load)
                ?.let { owner ->
                    val methods = if (searchHierarchy) {
                        methodsFromHierarchy(owner, contract)
                    } else {
                        owner.declaredMethods.filter(contract)
                    }
                    methods.filter { method ->
                        expectedMethodName == null || method.name == expectedMethodName
                    }
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    },
    structuralCandidates = {
        if (searchHierarchy) {
            hierarchyMethods(fallbackOwner, structuralContract)
        } else {
            methods(fallbackOwner, structuralContract)
        }
    },
    identity = ::methodIdentity,
)

private fun fieldSymbol(
    id: String,
    profileOwner: TargetSymbolId,
    profilePolicy: ProfilePolicy = ProfilePolicy.EXACT_REQUIRED,
    exactFieldId: TargetSymbolId? = null,
    searchHierarchy: Boolean = false,
    fallbackOwner: (String) -> Boolean,
    contract: (Field) -> Boolean,
    structuralContract: (Field) -> Boolean = contract,
): TargetSymbolKey<Field> = TargetSymbolKey(
    id = id,
    profilePolicy = profilePolicy,
    profileCandidates = { profile ->
        runCatching {
            val expectedFieldName = exactFieldId?.let { profile?.exactFields?.get(it) }
            profile?.exactClasses?.get(profileOwner)
                ?.let(::load)
                ?.let { owner ->
                    val fields = if (searchHierarchy) {
                        fieldsFromHierarchy(owner, contract)
                    } else {
                        owner.declaredFields.filter(contract)
                    }
                    fields.filter { field ->
                        expectedFieldName == null || field.name == expectedFieldName
                    }
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    },
    structuralCandidates = {
        if (searchHierarchy) {
            hierarchyFields(fallbackOwner, structuralContract)
        } else {
            fields(fallbackOwner, structuralContract)
        }
    },
    identity = ::fieldIdentity,
)

private fun hasLyricsChromeContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any(::isLyricsChromeAnimate) && hasLyricsChromeViewContract(candidate)

private fun hasLyricsChromeViewContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
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

private fun isPlayerControllerInitialize(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "w1" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.singleOrNull()?.name?.endsWith(".BagConfig") == true

private fun isStructuralPlayerControllerInitialize(method: Method): Boolean =
    isPlayerControllerInitialize(method) && hasPlayerControllerHookContract(method.declaringClass)

private fun isPlayerControllerCreateView(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "onCreateView" &&
        View::class.java.isAssignableFrom(method.returnType) &&
        method.parameterTypes.map { it.name } == listOf(
        "android.view.LayoutInflater",
        "android.view.ViewGroup",
        "android.os.Bundle",
    )

private fun isStructuralPlayerControllerCreateView(method: Method): Boolean =
    isPlayerControllerCreateView(method) && hasPlayerControllerHookContract(method.declaringClass)

private fun isPlayerControllerSelectPane(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "F1" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.size == 2 &&
        method.parameterTypes[0].isEnum &&
        method.parameterTypes[1].name == "android.os.Bundle"

private fun isStructuralPlayerControllerSelectPane(method: Method): Boolean =
    isPlayerControllerSelectPane(method) && hasPlayerControllerHookContract(method.declaringClass)

private fun hasPlayerControllerHookContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any(::isPlayerControllerInitialize) &&
        candidate.declaredMethods.any(::isPlayerControllerCreateView) &&
        candidate.declaredMethods.any(::isPlayerControllerSelectPane)

private fun isPlayerActivityCreateStackedNavigationHolder(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.isEmpty() &&
        method.returnType.name == "com.apple.android.music.common.activity.PlayerActivity\$m"

private fun isPlayerActivityRoot(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.isEmpty() &&
        View::class.java.isAssignableFrom(method.returnType)

private fun isPlayerActivityBehaviorField(field: Field): Boolean =
    !Modifier.isStatic(field.modifiers) && isBottomSheetBehaviorType(field.type)

private fun isStaticCollapsedIntercept(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "h" &&
        method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
            "androidx.coordinatorlayout.widget.CoordinatorLayout",
            "android.view.View",
            "android.view.MotionEvent",
        )

private fun isBottomSheetBehaviorType(type: Class<*>): Boolean {
    var current: Class<*>? = type
    while (current != null) {
        if (current.name == "com.google.android.material.bottomsheet.BottomSheetBehavior") return true
        current = current.superclass
    }
    return type.name.endsWith("PlayerBottomSheetBehavior")
}

private fun isLyricsFragmentOnResume(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "onResume" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.isEmpty()

private fun isLyricsFragmentUpdateMetrics(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "j2" &&
        method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.isEmpty()

private fun isLyricsChromeAnimate(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "a2" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, IntArray::class.java),
        )

private fun isStructuralLyricsChromeAnimate(method: Method): Boolean =
    isLyricsChromeAnimate(method) && hasLyricsChromeViewContract(method.declaringClass)

private fun isStackedNavigationMenuOnMeasure(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "onMeasure" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )

private fun isLyricsViewModelNotifyWordHighlight(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "notifyWordHighlight" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(
            arrayOf(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ),
        )

private fun isLyricsViewModelSetCurrentHighlightedLine(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "setCurrentHighlightedLine" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))

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

/**
 * The verified o2 contract: exact name, void return, the v3.v metadata type,
 * the BaseContentItem current item, and a flags holder declared as a member
 * of the fragment's immediate superclass whose non-static boolean fields are
 * exactly {a, b, c}. Both verified profiles (6.5.0 `e$c`, 6.5.1 `d$c`) carry
 * that exact shape, so the contract can never silently select an unrelated
 * three-argument method.
 */
private fun isLyricsItemUpdateMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "o2" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.size == 3 &&
        method.parameterTypes[0].name == "v3.v" &&
        method.parameterTypes[1].name == "com.apple.android.music.model.BaseContentItem" &&
        isLyricsItemUpdateFlagsType(method.parameterTypes[2], method.declaringClass)

private fun isLyricsItemUpdateFlagsType(flagsType: Class<*>, fragmentType: Class<*>): Boolean {
    val base = fragmentType.superclass ?: return false
    if (flagsType.declaringClass != base) return false
    if (base.declaredClasses.none { it == flagsType }) return false
    val booleanFields = flagsType.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && it.type == java.lang.Boolean.TYPE }
        .map(Field::getName)
        .toSet()
    return booleanFields == setOf("a", "b", "c")
}

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

private fun isMediaLibraryUpdateMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.parameterCount == 1 &&
        method.parameterTypes.single().isEnum &&
        method.parameterTypes.single().enumConstants.orEmpty().any {
            (it as? Enum<*>)?.name == "UserInitiatedPoll"
        }

private fun isMediaLibraryItemsQueryMethod(method: Method): Boolean =
    isMediaLibraryQueryMethod(method, "g")

private fun isMediaLibraryAlbumsQueryMethod(method: Method): Boolean =
    isMediaLibraryQueryMethod(method, "y")

private fun isMediaLibraryQueryMethod(method: Method, name: String): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == name &&
        method.parameterTypes.singleOrNull()?.name == "G5.g" &&
        method.returnType.name == "Vf.o"

private fun isMediaLibraryReadyMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "isReady" &&
        method.parameterCount == 0 &&
        method.returnType == Boolean::class.javaPrimitiveType

private fun isMediaLibraryNativePointerMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.parameterCount == 0 &&
        method.returnType.name ==
            "com.apple.android.medialibrary.javanative.medialibrary.library.SVMediaLibrary\$SVMediaLibraryPtr"

private fun isMediaLibraryNativeCatalogRefreshMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "lookupAndRefreshCatalogItemsInLibrary" &&
        method.parameterCount == 0 &&
        method.returnType.name.endsWith("MediaErr\$MediaError")

private fun isConfigurationStoreStoreFrontLanguageMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "storeFrontLanguageOrDefault" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isStoreApiHeadersSetMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "set" &&
        method.parameterCount == 2 &&
        method.parameterTypes[0] == String::class.java &&
        method.parameterTypes[1].isAssignableFrom(String::class.java)

private fun isStoreFrontLanguageArrayMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.singleOrNull()?.name == "android.content.Context" &&
        method.returnType == Array<String>::class.java &&
        method.name == "b"

private fun isICloudAcceptLanguageHelperMethod(method: Method): Boolean =
    method.declaringClass.isInterface &&
        method.declaringClass.name.lowercase(Locale.ROOT).contains(".icloud.") &&
        !Modifier.isStatic(method.modifiers) &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java &&
        method.name.contains("language", ignoreCase = true)

private fun isStoreApiHeaderMapMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        java.util.Map::class.java.isAssignableFrom(method.returnType) &&
        method.name.contains("map", ignoreCase = true)

private fun isITunesGetHeadersMapMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.declaringClass.name == "com.apple.android.music.commerce.jsinterface.ITunes" &&
        method.name == "getHeadersMap" &&
        method.parameterCount == 0 &&
        java.util.Map::class.java.isAssignableFrom(method.returnType)

private fun isLocaleHeaderMapMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.declaringClass.name == "ma.c" &&
        method.name == "a" &&
        method.parameterCount == 1 &&
        method.parameterTypes.single().name == "aa.d" &&
        java.util.Map::class.java.isAssignableFrom(method.returnType)

private fun isMediaApiLanguageParamMethod(method: Method): Boolean =
    (
        Modifier.isStatic(method.modifiers) &&
            method.declaringClass.name == "s8.F" &&
            method.name == "c0" &&
            method.parameterCount == 1 &&
            java.util.Map::class.java.isAssignableFrom(method.parameterTypes.single()) &&
            java.util.LinkedHashMap::class.java.isAssignableFrom(method.returnType)
        ) || (
        !Modifier.isStatic(method.modifiers) &&
            java.util.Map::class.java.isAssignableFrom(method.returnType) &&
            method.name.contains("language", ignoreCase = true)
        )

private fun isStoreLookupSetParamMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "setParam" &&
        method.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) &&
        method.returnType.name == "com.apple.android.music.storeapi.modelprivate.Request\$Builder"

private fun isMediaApiRepositoryGetEntitiesWithIdsMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        !Modifier.isAbstract(method.modifiers) &&
        method.name == "getEntitiesWithIds" &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        String::class.java.name,
        "java.util.List",
        "java.util.Map",
        "kotlin.coroutines.Continuation",
    ) &&
        method.returnType == Any::class.java

private fun isMediaApiRepositoryGetEntitiesWithIdsInvocationMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.declaringClass.isInterface &&
        method.name == "getEntitiesWithIds" &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        String::class.java.name,
        "java.util.List",
        "java.util.Map",
        "kotlin.coroutines.Continuation",
    ) &&
        method.returnType == Any::class.java

private fun isMediaEntityGetTitleMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getTitle" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isMediaEntityGetAttributesMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getAttributes" &&
        method.parameterCount == 0 &&
        method.returnType != Void.TYPE

private fun isAttributesGetShortNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getShortName" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isMediaEntityToCollectionItemViewMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "toCollectionItemView" &&
        method.parameterCount == 1 &&
        method.parameterTypes.single().name == "android.os.Bundle" &&
        method.returnType.name == "com.apple.android.music.model.CollectionItemView"

private fun isPlayerPlaybackItemViewUpdateMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "y0" &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        "com.apple.android.music.model.PlaybackItem",
        "com.apple.android.music.model.CollectionItemView",
        String::class.java.name,
        "android.content.Context",
        "android.view.View",
    ) &&
        method.returnType == Void.TYPE

private fun isPlayerActionSheetResponseApplyMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "accept" &&
        method.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
        method.returnType == Void.TYPE

private fun isBasePlaybackItemNativeEntityPopulateMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.name == "n" &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        "com.apple.android.music.model.BasePlaybackItem",
        "com.apple.android.medialibrary.javanative.medialibrary.svmodel.SVEntityNative\$SVEntitySRef",
    ) &&
        method.returnType == Void.TYPE

private fun isNativeEntityAlbumConverterMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.name == "b" &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        "com.apple.android.medialibrary.javanative.medialibrary.svmodel.SVEntityNative\$SVEntitySRef",
        Boolean::class.javaPrimitiveType!!.name,
    ) &&
        method.returnType.name == "com.apple.android.music.model.Album"

private fun isMediaEntityGetShortNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getShortName" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isAttributesGetArtistNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getArtistName" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isAttributesGetNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getName" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isAttributesGetTitleMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getTitle" &&
        method.parameterCount == 0 &&
        method.returnType.name == "com.apple.android.music.mediaapi.models.internals.Title"

private fun isAttributesGetTitleWithoutNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getTitleWithoutName" &&
        method.parameterCount == 0 &&
        method.returnType.name == "com.apple.android.music.mediaapi.models.internals.Title"

private fun isTitleGetStringForDisplayMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getStringForDisplay" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isAttributesSetArtistNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "setArtistName" &&
        method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
        method.returnType == Void.TYPE

private fun isAttributesGetAlbumNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getAlbumName" &&
        method.parameterCount == 0 &&
        method.returnType == String::class.java

private fun isAttributesSetAlbumNameMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "setAlbumName" &&
        method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
        method.returnType == Void.TYPE

/**
 * The verified AMTool "MediaEntity -> model.Song" converter shape
 * (`y8.B.b(Song, Bundle)` in both 6.5.0 and 6.5.1). The static signature is
 * specific enough that an unknown build can only produce a unique match or
 * an explicit ambiguity, never a silent first pick.
 */
private fun isMediaEntityToSongConverterMethod(method: Method): Boolean =
    Modifier.isStatic(method.modifiers) &&
        method.parameterTypes.map(Class<*>::getName) == listOf(
        "com.apple.android.music.mediaapi.models.Song",
        "android.os.Bundle",
    ) &&
        method.returnType.name == "com.apple.android.music.model.Song"

private fun isSearchSectionResultResponseSetDataMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "setData" &&
        method.parameterCount == 1 &&
        method.parameterTypes.single() == java.util.List::class.java &&
        method.returnType == Void.TYPE

/** The two-argument library conversion override (`toCollectionItemView(Bundle, Z)`). */
private fun isLibraryEntityToCollectionItemViewMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "toCollectionItemView" &&
        method.parameterTypes.contentEquals(
            arrayOf(
                android.os.Bundle::class.java,
                Boolean::class.javaPrimitiveType,
            ),
        ) &&
        method.returnType.name == "com.apple.android.music.model.CollectionItemView"

private fun isBaseStorePlatformResponseGetStorePlatformDataMethod(method: Method): Boolean =
    !Modifier.isStatic(method.modifiers) &&
        method.name == "getStorePlatformData" &&
        method.parameterCount == 0 &&
        java.util.Map::class.java.isAssignableFrom(method.returnType)

/** Obfuscated top-level classes such as `y8.B`, `Hd.b`, `J5.a` or `T8.a`. */
internal fun isObfuscatedTopLevelClass(name: String): Boolean =
    name.length == 4 &&
        name[0].isLetterOrDigit() &&
        name[1].isLetterOrDigit() &&
        name[2] == '.' &&
        name[3].isLetterOrDigit()

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
