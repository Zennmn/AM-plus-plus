package dev.amenhancer.module.hook

import android.app.Application
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Corrects only titles for which Apple already supplied a catalog relation.
 * A raw/local-library entity with no catalog data is left untouched.
 *
 * The install is composed of independent display seams, each resolving its
 * own target symbol and degrading on its own: the short-name getters, the
 * artist/album metadata accessors, the MediaEntity -> model.Song converter
 * that feeds the now-playing bottom sheet, the search results setData
 * seam, all concrete Song/Album library conversions, the player action-sheet
 * update and the StorePlatform response getter.  Direct Attributes title
 * objects are covered as well.  A missing or ambiguous seam never blocks the
 * others.
 */
internal class AppleMusicTitleCorrectionTarget(
    application: Application,
    private val symbols: TargetSymbolResolver,
    targetLanguage: String = "",
    cache: CatalogTitleCache? = null,
    cacheProvider: CatalogTitleCacheProvider? = null,
) : TitleCorrectionTarget {
    /** Defer preference loading until the feature actually resolves a title. */
    private val titleCache: CatalogTitleCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cache ?: cacheProvider?.get() ?: CatalogTitleCache(application, targetLanguage)
    }

    /** Prevent our reflective getter calls from re-entering this target. */
    private val callbackGuard: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    /** Only the artist row created by the current action sheet may use artist-id semantics. */
    private val actionSheetArtistIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Attributes are bound as one object: title, artist and album share a
     * single owner-aware lookup and a single fail-open correction transaction.
     */
    private val attributesDisplayCorrection: DisplayCorrectionModule<Any> by lazy {
        DisplayCorrectionModule(
            adapter = ReflectiveDisplayCorrectionAdapter(
                titleGetter = "getName",
                titleSetter = "setName",
                artistGetter = "getArtistName",
                artistSetter = "setArtistName",
                albumGetter = "getAlbumName",
                albumSetter = "setAlbumName",
            ),
            lookup = object : DisplayCorrectionLookup<Any> {
                override fun lookup(
                    target: Any,
                    original: DisplayCorrectionSnapshot,
                ): DisplayCorrectionCandidates {
                    val owner = titleCache.attributesOwner(target) ?: return DisplayCorrectionCandidates()
                    val identity = titleCache.identitySnapshotFor(owner)
                        ?: return DisplayCorrectionCandidates()
                    var title = titleCache.correctedTitleForIdentity(identity, original.title)
                    var artist = titleCache.correctedForIdentity(
                            identity,
                            original.artist,
                            TitleCorrectionPolicy.CacheKind.ARTIST,
                        )
                    var album = titleCache.correctedForIdentity(
                            identity,
                            original.album,
                            TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                        )
                    if (title == null && !original.title.isNullOrBlank()) {
                        titleCache.observeDisplayMiss(owner, identity)
                        title = titleCache.correctedTitleForIdentity(identity, original.title)
                        artist = titleCache.correctedForIdentity(
                            identity,
                            original.artist,
                            TitleCorrectionPolicy.CacheKind.ARTIST,
                        )
                        album = titleCache.correctedForIdentity(
                            identity,
                            original.album,
                            TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                        )
                    }
                    return DisplayCorrectionCandidates(title, artist, album)
                }
            },
        )
    }

    /**
     * Model display items carry a different accessor shape and, for converter
     * paths, may need a source MediaEntity identity.  A small binding wrapper
     * keeps that context out of the hot-path module contract while reusing one
     * module/lookup/adapter instance for every bind.
     */
    private val displayItemCorrection: DisplayCorrectionModule<DisplayCorrectionBinding> by lazy {
        val reflective = ReflectiveDisplayCorrectionAdapter()
        DisplayCorrectionModule(
            adapter = object : DisplayCorrectionAdapter<DisplayCorrectionBinding> {
                override fun read(
                    target: DisplayCorrectionBinding,
                    field: DisplayCorrectionField,
                ): String? = reflective.read(target.item, field)

                override fun write(
                    target: DisplayCorrectionBinding,
                    field: DisplayCorrectionField,
                    value: String,
                ) {
                    reflective.write(target.item, field, value)
                }
            },
            lookup = object : DisplayCorrectionLookup<DisplayCorrectionBinding> {
                override fun lookup(
                    target: DisplayCorrectionBinding,
                    original: DisplayCorrectionSnapshot,
                ): DisplayCorrectionCandidates {
                    val source = target.source
                    return if (source == null) {
                        val itemIdentity = titleCache.identitySnapshotFor(target.item)
                        val collectionIdentity = titleCache.identitySnapshotFor(
                            target.collectionOwner,
                            preferCollectionId = true,
                        ) ?: itemIdentity
                        var title = titleCache.correctedTitleForIdentity(itemIdentity, original.title)
                        var artist = titleCache.correctedForIdentity(
                                itemIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            )
                        var album = titleCache.correctedForIdentity(
                                collectionIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            )
                        val observationEntity = target.collectionOwner ?: target.item
                        val observationIdentity = titleCache.identitySnapshotFor(observationEntity)
                        if (title == null && !original.title.isNullOrBlank()) {
                            titleCache.observeDisplayMiss(observationEntity, observationIdentity)
                            title = titleCache.correctedTitleForIdentity(itemIdentity, original.title)
                            artist = titleCache.correctedForIdentity(
                                itemIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            )
                            album = titleCache.correctedForIdentity(
                                collectionIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            )
                        }
                        DisplayCorrectionCandidates(title, artist, album)
                    } else {
                        val sourceIdentity = titleCache.identitySnapshotFor(source)
                        val itemIdentity = titleCache.identitySnapshotFor(target.item)
                        var title = titleCache.correctedTitleForIdentity(sourceIdentity, original.title)
                            ?: titleCache.correctedTitleForIdentity(itemIdentity, original.title)
                        var artist = titleCache.correctedForIdentity(
                                sourceIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            ) ?: titleCache.correctedForIdentity(
                                itemIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            )
                        var album = titleCache.correctedForIdentity(
                                sourceIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            ) ?: titleCache.correctedForIdentity(
                                itemIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            )
                        if (title == null && !original.title.isNullOrBlank()) {
                            titleCache.observeDisplayMiss(source, sourceIdentity)
                            title = titleCache.correctedTitleForIdentity(sourceIdentity, original.title)
                                ?: titleCache.correctedTitleForIdentity(itemIdentity, original.title)
                            artist = titleCache.correctedForIdentity(
                                sourceIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            ) ?: titleCache.correctedForIdentity(
                                itemIdentity,
                                original.artist,
                                TitleCorrectionPolicy.CacheKind.ARTIST,
                            )
                            album = titleCache.correctedForIdentity(
                                sourceIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            ) ?: titleCache.correctedForIdentity(
                                itemIdentity,
                                original.album,
                                TitleCorrectionPolicy.CacheKind.ALBUM_NAME,
                            )
                        }
                        DisplayCorrectionCandidates(title, artist, album)
                    }
                }
            },
        )
    }

    private data class DisplayCorrectionBinding(
        val item: Any,
        val source: Any?,
        val collectionOwner: Any?,
    )

    override fun install(): TargetCapabilityInstall {
        val installed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val titleResolution = symbols.resolve(AppleMusicSymbols.MediaEntityGetTitleMethod)
        val attributesResolution = symbols.resolve(AppleMusicSymbols.MediaEntityGetAttributesMethod)
        val attributesNameResolution = symbols.resolve(AppleMusicSymbols.AttributesGetNameMethod)
        val attributesArtistResolution = symbols.resolve(AppleMusicSymbols.AttributesGetArtistNameMethod)
        val attributesAlbumResolution = symbols.resolve(AppleMusicSymbols.AttributesGetAlbumNameMethod)
        val attributesTitleResolution = symbols.resolve(AppleMusicSymbols.AttributesGetTitleMethod)
        val attributesTitleWithoutNameResolution = symbols.resolve(
            AppleMusicSymbols.AttributesGetTitleWithoutNameMethod,
        )
        val attributesShortNameResolution = symbols.resolve(AppleMusicSymbols.AttributesGetShortNameMethod)
        val titleDisplayResolution = symbols.resolve(AppleMusicSymbols.TitleGetStringForDisplayMethod)
        val collectionResolution = symbols.resolve(
            AppleMusicSymbols.MediaEntityToCollectionItemViewMethod,
        )

        installBaseHook(
            titleResolution,
            label = "MediaEntity.getTitle",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedTitle(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            attributesResolution,
            label = "MediaEntity.getAttributes",
            installed = installed,
            errors = errors,
        ) { param ->
            val entity = param.thisObject ?: return@installBaseHook
            val attributes = param.result ?: return@installBaseHook
            titleCache.captureCatalogMetadataDeferred(entity)
            correctAttributesDisplay(entity, attributes)
        }
        installBaseHook(
            attributesNameResolution,
            label = "Attributes.getName",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedAttributesTitle(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            attributesArtistResolution,
            label = "Attributes.getArtistName",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedAttributesArtist(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            attributesAlbumResolution,
            label = "Attributes.getAlbumName",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedAttributesAlbumName(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            attributesTitleResolution,
            label = "Attributes.getTitle display object",
            installed = installed,
            errors = errors,
        ) { param ->
            val title = param.result ?: return@installBaseHook
            titleCache.registerTitleOwner(param.thisObject, title)
            val raw = callString(title, "getStringForDisplay") ?: return@installBaseHook
            val corrected = titleCache.correctedAttributesTitle(param.thisObject, raw)
                ?: return@installBaseHook
            copyTitle(title, corrected)?.let { replacement ->
                titleCache.registerTitleOwner(param.thisObject, replacement)
                param.result = replacement
            }
        }
        installBaseHook(
            attributesTitleWithoutNameResolution,
            label = "Attributes.getTitleWithoutName display object",
            installed = installed,
            errors = errors,
        ) { param ->
            val title = param.result ?: return@installBaseHook
            titleCache.registerTitleOwner(param.thisObject, title)
            val raw = callString(title, "getStringForDisplay") ?: return@installBaseHook
            val corrected = titleCache.correctedAttributesTitle(param.thisObject, raw)
                ?: return@installBaseHook
            copyTitle(title, corrected)?.let { replacement ->
                titleCache.registerTitleOwner(param.thisObject, replacement)
                param.result = replacement
            }
        }
        installBaseHook(
            titleDisplayResolution,
            label = "Title.getStringForDisplay",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedTitleObject(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            attributesShortNameResolution,
            label = "Attributes.getShortName",
            installed = installed,
            errors = errors,
        ) { param ->
            val original = param.result as? String ?: return@installBaseHook
            titleCache.correctedAttributesTitle(param.thisObject, original)?.let { corrected ->
                param.result = corrected
            }
        }
        installBaseHook(
            collectionResolution,
            label = "MediaEntity.toCollectionItemView",
            installed = installed,
            errors = errors,
        ) { param ->
            val view = param.result ?: return@installBaseHook
            val original = callString(view, "getTitle") ?: return@installBaseHook
            titleCache.correctedTitle(param.thisObject, original)?.let { corrected ->
                callSetter(view, "setTitle", corrected)
            }
        }

        val seams = listOf(
            runSeam("short-name seam", errors) { installShortNameSeam() },
            runSeam("artist/album metadata seam", errors) { installAttributesMetadataSeam() },
            runSeam("song converter seam", errors) { installSongConverterSeam() },
            runSeam("search results seam", errors) { installSearchResultsSeam() },
            runSeam("library conversion seam", errors) { installLibraryConversionSeam() },
            runSeam("player action sheet seam", errors) { installPlayerActionSheetSeam() },
            runSeam("store platform seam", errors) { installStorePlatformSeam() },
        )
        val seamMessages = seams.map { it.message }
        val seamInstalled = seams.count { it.installed }
        if (installed.isNotEmpty() || seamInstalled > 0) {
            titleCache.startBackgroundServices()
        }

        val messages = listOf(
            titleResolution.summary,
            attributesResolution.summary,
            attributesNameResolution.summary,
            attributesArtistResolution.summary,
            attributesAlbumResolution.summary,
            attributesTitleResolution.summary,
            attributesTitleWithoutNameResolution.summary,
            attributesShortNameResolution.summary,
            titleDisplayResolution.summary,
            collectionResolution.summary,
            *seamMessages.toTypedArray(),
            *errors.toTypedArray(),
        )
        return if (installed.size + seamInstalled == 0) {
            TargetCapabilityInstall.Degraded(messages.joinToString("; "))
        } else {
            TargetCapabilityInstall.Active(
                "Catalog-backed song title correction installed; " +
                    messages.joinToString("; "),
            )
        }
    }

    private fun installBaseHook(
        resolution: TargetResolution<Method>,
        label: String,
        installed: MutableList<String>,
        errors: MutableList<String>,
        block: (ModernMethodHook.MethodHookParam) -> Unit,
    ) {
        val method = resolution.valueOrNull()
        if (method == null) {
            errors += resolution.summary
            return
        }
        runCatching {
            method.isAccessible = true
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                    runGuarded { block(param) }
                }
            })
        }.onSuccess {
            installed += label
        }.onFailure {
            errors += "$label hook: ${it.message.orEmpty()}"
        }
    }

    private data class SeamInstall(val installed: Boolean, val message: String)

    private fun runSeam(
        label: String,
        errors: MutableList<String>,
        block: () -> SeamInstall,
    ): SeamInstall = runCatching { block() }.getOrElse {
        val message = "$label degraded: ${it.message.orEmpty()}"
        errors += message
        SeamInstall(false, message)
    }

    /**
     * A callback frequently calls a host getter through reflection.  That
     * getter can itself be one of our hooks, so skip only the nested callback
     * while retaining the host method's original result and fail-open behavior.
     */
    private fun runGuarded(block: () -> Unit) {
        if (callbackGuard.get() == true) return
        callbackGuard.set(true)
        try {
            runCatching(block)
        } finally {
            callbackGuard.set(false)
        }
    }

    /** MediaEntity.getShortName() plus the mediaapi Song override. */
    private fun installShortNameSeam(): SeamInstall {
        val base = symbols.resolve(AppleMusicSymbols.MediaEntityGetShortNameMethod)
        val override = symbols.resolve(AppleMusicSymbols.SongGetShortNameMethod)
        val installed = mutableListOf<String>()
        base.valueOrNull()?.let { method ->
            hookTitleStringMethod(method)
            installed += "MediaEntity.getShortName"
        }
        override.valueOrNull()?.let { method ->
            hookTitleStringMethod(method)
            installed += "Song.getShortName"
        }
        if (installed.isEmpty()) {
            return SeamInstall(
                false,
                "short-name seam degraded: " +
                    listOf(base.summary, override.summary).joinToString("; "),
            )
        }
        return SeamInstall(true, "short-name seam on " + installed.joinToString(", "))
    }

    private fun hookTitleStringMethod(method: Method) {
        method.isAccessible = true
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val original = param.result as? String ?: return@runGuarded
                    titleCache.correctedTitle(param.thisObject, original)?.let { corrected ->
                        param.result = corrected
                    }
                }
            }
        })
    }

    /** Verifies the direct artist/album accessor contracts used by detail pages. */
    private fun installAttributesMetadataSeam(): SeamInstall {
        val resolutions = listOf(
            AppleMusicSymbols.AttributesGetArtistNameMethod,
            AppleMusicSymbols.AttributesSetArtistNameMethod,
            AppleMusicSymbols.AttributesGetAlbumNameMethod,
            AppleMusicSymbols.AttributesSetAlbumNameMethod,
        ).map { it to symbols.resolve(it) }
        val missing = resolutions.filter { (_, resolution) -> resolution.valueOrNull() == null }
        if (missing.isNotEmpty()) {
            return SeamInstall(
                false,
                "artist/album metadata seam degraded: " +
                    missing.joinToString("; ") { (_, resolution) -> resolution.summary },
            )
        }
        return SeamInstall(
            true,
            "artist/album metadata seam installed (" +
                resolutions.joinToString("; ") { (_, resolution) -> resolution.summary } + ")",
        )
    }

    /**
     * The now-playing bottom sheet binds the `model.Song` produced by
     * AMTool's "MediaEntity -> model.Song" converter (`y8.B.b` in 6.5.x);
     * the converted item is corrected in place.
     */
    private fun installSongConverterSeam(): SeamInstall {
        val resolution = symbols.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)
        val converter = resolution.valueOrNull()
            ?: return SeamInstall(false, "song converter seam degraded: ${resolution.summary}")
        converter.isAccessible = true
        ModernXposedRuntime.hookMethod(converter, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val song = param.result ?: return@runGuarded
                    val source = param.args.getOrNull(0)
                    if (source != null) titleCache.captureCatalogMetadata(source)
                    correctDisplayItem(source, song)
                }
            }
        })
        return SeamInstall(true, "song converter seam installed with source identity; ${resolution.summary}")
    }

    /**
     * Search section results arrive as target-language catalog entities;
     * each is captured into the cache and its display attributes rewritten.
     */
    private fun installSearchResultsSeam(): SeamInstall {
        val resolution = symbols.resolve(AppleMusicSymbols.SearchSectionResultResponseSetDataMethod)
        val setData = resolution.valueOrNull()
            ?: return SeamInstall(false, "search results seam degraded: ${resolution.summary}")
        setData.isAccessible = true
        ModernXposedRuntime.hookMethod(setData, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val list = param.args.getOrNull(0) as? List<*> ?: return@runGuarded
                    list.forEach { entity ->
                        if (entity != null) correctCatalogEntity(entity)
                    }
                }
            }
        })
        return SeamInstall(true, "search results seam installed; ${resolution.summary}")
    }

    /**
     * The two-argument library conversions that the one-argument MediaEntity
     * hook cannot see because virtual dispatch resolves to the overrides.
     */
    private fun installLibraryConversionSeam(): SeamInstall {
        val songModel = symbols.resolve(AppleMusicSymbols.SongToCollectionItemViewMethod)
        val albumModel = symbols.resolve(AppleMusicSymbols.AlbumToCollectionItemViewMethod)
        val song = symbols.resolve(AppleMusicSymbols.LibrarySongToCollectionItemViewMethod)
        val album = symbols.resolve(AppleMusicSymbols.LibraryAlbumToCollectionItemViewMethod)
        val nativeSong = symbols.resolve(AppleMusicSymbols.NativeLibrarySongConverterMethod)
        val nativeAlbum = symbols.resolve(AppleMusicSymbols.NativeLibraryAlbumConverterMethod)
        val installed = mutableListOf<String>()
        songModel.valueOrNull()?.let { method ->
            hookCollectionConversion(method)
            installed += "Song"
        }
        albumModel.valueOrNull()?.let { method ->
            hookCollectionConversion(method)
            installed += "Album"
        }
        song.valueOrNull()?.let { method ->
            hookCollectionConversion(method)
            installed += "LibrarySong"
        }
        album.valueOrNull()?.let { method ->
            hookCollectionConversion(method)
            installed += "LibraryAlbum"
        }
        nativeSong.valueOrNull()?.let { method ->
            hookNativeSongConversion(method)
            installed += "v5.a.n"
        }
        nativeAlbum.valueOrNull()?.let { method ->
            hookNativeAlbumConversion(method)
            installed += "v5.a.b"
        }
        if (installed.isEmpty()) {
            return SeamInstall(
                false,
                "library conversion seam degraded: " +
                    listOf(
                        songModel.summary,
                        albumModel.summary,
                        song.summary,
                        album.summary,
                        nativeSong.summary,
                        nativeAlbum.summary,
                    )
                        .joinToString("; "),
            )
        }
        return SeamInstall(true, "library conversion seam on " + installed.joinToString(", "))
    }

    private fun hookCollectionConversion(method: Method) {
        method.isAccessible = true
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val view = param.result ?: return@runGuarded
                    val raw = callString(view, "getTitle") ?: return@runGuarded
                    titleCache.correctedTitle(param.thisObject, raw)?.let { corrected ->
                        callSetter(view, "setTitle", corrected)
                    }
                }
            }
        })
    }

    /** AMTool's direct local-library factory path (`v5.a.n`). */
    private fun hookNativeSongConversion(method: Method) {
        method.isAccessible = true
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    param.args.getOrNull(0)?.let(::correctDisplayItem)
                }
            }
        })
    }

    /** AMTool's direct local-library album factory path (`v5.a.b`). */
    private fun hookNativeAlbumConversion(method: Method) {
        method.isAccessible = true
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    param.result?.let(::correctDisplayItem)
                }
            }
        })
    }

    /**
     * The now-playing bottom action sheet receives model objects directly and
     * therefore bypasses both MediaEntity attributes and collection conversion.
     * AMTool rewrites these arguments before the sheet reads artist/album text.
     */
    private fun installPlayerActionSheetSeam(): SeamInstall {
        val resolution = symbols.resolve(AppleMusicSymbols.PlayerActionSheetMethod)
        val method = resolution.valueOrNull()
            ?: return SeamInstall(false, "player action sheet seam degraded: ${resolution.summary}")
        method.isAccessible = true
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val playbackItem = param.args.getOrNull(0)
                    val collectionView = param.args.getOrNull(1)
                    val artistId = param.args.getOrNull(2) as? String
                    artistId?.takeIf(String::isNotBlank)?.let(actionSheetArtistIds::add)
                    // AMTool resolves artist/title with the playback item's id,
                    // but resolves the collection name with getCollectionId().
                    // Keep that identity split when both objects are supplied.
                    playbackItem?.let {
                        correctDisplayItem(null, it, it)
                        titleCache.aliasDisplayArtist(it, artistId)
                    }
                    collectionView?.let { correctDisplayItem(null, it, playbackItem) }
                }
            }
        })
        val responseResolution = symbols.resolve(
            AppleMusicSymbols.PlayerActionSheetResponseApplyMethod,
        )
        responseResolution.valueOrNull()?.let { responseMethod ->
            responseMethod.isAccessible = true
            ModernXposedRuntime.hookMethod(responseMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                    runGuarded {
                        val map = param.args.firstOrNull() as? Map<*, *> ?: return@runGuarded
                        map.entries.forEach { (key, value) ->
                            if (value == null) return@forEach
                            val keyId = key?.toString()
                            if (keyId == null || keyId !in actionSheetArtistIds) return@forEach
                            val raw = callString(value, "getTitle") ?: return@forEach
                            titleCache.correctedArtistById(keyId, raw)
                                ?.let { corrected -> callSetter(value, "setTitle", corrected) }
                        }
                    }
                }
            })
        }
        return SeamInstall(
            true,
            "player action sheet seam installed; ${resolution.summary}; ${responseResolution.summary}",
        )
    }

    /**
     * Store platform responses expose display views through the map getter;
     * the views are corrected from the cache without capturing their
     * storefront titles (those must not replace target-language data).
     */
    private fun installStorePlatformSeam(): SeamInstall {
        val resolution = symbols.resolve(
            AppleMusicSymbols.BaseStorePlatformResponseGetStorePlatformDataMethod,
        )
        val getter = resolution.valueOrNull()
            ?: return SeamInstall(false, "store platform seam degraded: ${resolution.summary}")
        getter.isAccessible = true
        ModernXposedRuntime.hookMethod(getter, object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                runGuarded {
                    val map = param.result as? Map<*, *> ?: return@runGuarded
                    map.values.forEach { value ->
                        if (value != null) correctDisplayItem(value)
                    }
                }
            }
        })
        return SeamInstall(true, "store platform seam installed; ${resolution.summary}")
    }

    /** Captures a target-language catalog entity and rewrites its attributes. */
    private fun correctCatalogEntity(entity: Any) {
        val mediaKind = when (TitleCorrectionPolicy.entityKindOf(entity.javaClass.name)) {
            TitleCorrectionPolicy.EntityKind.ALBUM -> "albums"
            else -> "songs"
        }
        titleCache.captureCatalogMetadataForEntity(entity, mediaKind)
        val attributes = callObject(entity, "getAttributes") ?: return
        titleCache.registerAttributesOwner(entity, attributes)
        correctAttributesDisplay(entity, attributes)
    }

    /** Rewrites title/artist/album of an Attributes object for its owning entity. */
    private fun correctAttributesDisplay(entity: Any, attributes: Any) {
        titleCache.registerAttributesOwner(entity, attributes)
        attributesDisplayCorrection.bind(attributes)
    }

    /** Rewrites title/artist/album of a model display item (Song, CollectionItemView). */
    private fun correctDisplayItem(item: Any) = correctDisplayItem(null, item)

    /** Uses a source MediaEntity id when the converted display item has none. */
    private fun correctDisplayItem(source: Any?, item: Any, collectionOwner: Any? = null) {
        displayItemCorrection.bind(DisplayCorrectionBinding(item, source, collectionOwner))
    }

    private fun callString(receiver: Any, name: String): String? = runCatching {
        ReflectionMethodCache.find(
            owner = receiver.javaClass,
            name = name,
            returnType = String::class.java,
        )?.invoke(receiver) as? String
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun callObject(receiver: Any, name: String): Any? = runCatching {
        ReflectionMethodCache.find(
            owner = receiver.javaClass,
            name = name,
        )?.invoke(receiver)
    }.getOrNull()

    private fun copyTitle(title: Any, corrected: String): Any? = runCatching {
        val constructor = ReflectionConstructorCache.find(
            owner = title.javaClass,
            parameterTypes = STRING_PARAMETER_TYPES,
        ) ?: return null
        constructor.newInstance(corrected)
    }.getOrNull()

    private fun callSetter(receiver: Any, name: String, value: String) = runCatching {
        ReflectionMethodCache.find(
            owner = receiver.javaClass,
            name = name,
            parameterTypes = STRING_PARAMETER_TYPES,
        )?.invoke(receiver, value)
    }

    private companion object {
        val STRING_PARAMETER_TYPES = listOf(String::class.java)
    }
}
internal fun interface TitleCorrectionTarget {
    fun install(): TargetCapabilityInstall
}
