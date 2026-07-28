package dev.amenhancer.module.hook

import android.view.View
import dev.amenhancer.module.ModuleConstants
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
}

internal class TargetSymbolKey<T : Any>(
    val id: String,
    internal val profileCandidates: TargetClassIndex.(AppleMusicProfile?) -> List<T> = { emptyList() },
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
        select(symbol, symbol.profileCandidates(index, profile), SymbolMatch.VERSION_PROFILE)?.let { return it }
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
    LYRICS_HIGHLIGHT_CALLBACK_OWNER,
    LYRICS_VIEW_MODEL,
    STACKED_NAVIGATION_MENU,
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
            TargetSymbolId.LYRICS_HIGHLIGHT_CALLBACK_OWNER to
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents\$lineEventCallback\$1",
            TargetSymbolId.LYRICS_VIEW_MODEL to
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            TargetSymbolId.STACKED_NAVIGATION_MENU to "Hd.b",
        ),
    )

    fun match(build: TargetBuild): AppleMusicProfile? = appleMusic650.takeIf {
        build.packageName == ModuleConstants.TARGET_PACKAGE &&
            build.versionName == "6.5.0" &&
            build.versionCode == 1580L
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

    val LyricsHighlightCallback = TargetSymbolKey(
        id = "lyrics-highlight-callback",
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
                    namePredicate = { it.startsWith("com.apple.") },
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
        fallbackName = { it.contains("PlayerLyricsViewModel", ignoreCase = true) },
        contract = { true },
    )

    val StackedNavigationMenu = classSymbol(
        id = "stacked-navigation-menu",
        profileId = TargetSymbolId.STACKED_NAVIGATION_MENU,
        fallbackName = { false },
        contract = { true },
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
): TargetSymbolKey<Method> = TargetSymbolKey(
    id = id,
    profileCandidates = { profile ->
        profile?.exactClasses?.get(profileOwner)
            ?.let(::load)
            ?.declaredMethods
            ?.filter(contract)
            .orEmpty()
    },
    structuralCandidates = { methods(fallbackOwner, contract) },
    identity = ::methodIdentity,
)

private fun hasLyricsChromeContract(candidate: Class<*>): Boolean =
    candidate.declaredMethods.any { method ->
        method.name == "a2" &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, IntArray::class.java),
            )
    } && candidate.declaredMethods.any { method ->
        method.name == "f2" &&
            View::class.java.isAssignableFrom(method.returnType) &&
            method.parameterTypes.isEmpty()
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

private fun methodIdentity(method: Method): String = buildString {
    append(method.declaringClass.name).append('#').append(method.name).append('(')
    append(method.parameterTypes.joinToString(",") { it.name })
    append("):").append(method.returnType.name)
}
