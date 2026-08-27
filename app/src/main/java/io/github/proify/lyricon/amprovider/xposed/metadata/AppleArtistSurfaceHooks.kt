/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal interface AppleArtistSurfaceHost {
    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun mediaApiAttribute(
        attributes: Any,
        attribute: AppleMediaApiTextAttribute,
    ): String?

    fun mediaApiEntityRelationshipEntities(
        entity: Any,
        relationshipKey: String,
    ): Collection<Any>

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
    )

    fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    )

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>)

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        notifyModelChange: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun retryOriginalMetadata(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun activeMetadataPageOwner(): Any?

    fun knownArtistProfileCredits(artistId: String): Set<String>

    fun onMetadataPageAttached(owner: Any, recycler: RecyclerView)

    fun onMetadataPageDetached(owner: Any)

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)
}

internal class AppleArtistSurfaceHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val host: AppleArtistSurfaceHost,
    private val refreshQueue: AppleInAppMetadataRefreshQueue? = null,
) {
    private val pageBuildData = Collections.synchronizedMap(WeakHashMap<Any, ArtistPageBuildData>())
    private val topSongModels = WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    private val topSongBindings = WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    // GenericProfile renders top-songs directly in populateViews(), bypassing
    // addSwipingChartItemA2(). Keep the relationship IDs so final h1 binds can
    // still be associated with their catalog entities.
    private val genericProfileTopSongIds =
        Collections.synchronizedMap(WeakHashMap<Any, List<String>>())
    private val genericProfileTopSongTexts: MutableMap<Any, Map<String, Pair<String?, String?>>> =
        Collections.synchronizedMap(WeakHashMap())
    private val profileMediaIds = WeakIdentityMap<Any, String>()
    private val headerModelIds = WeakIdentityMap<Any, String>()
    private val headerBindingIds = WeakIdentityMap<Any, String>()
    private val finalBoundResolutionIds = WeakIdentityMap<Any, String>()
    private val topSongCandidateArtistIds =
        ConcurrentHashMap<String, MutableSet<String>>()
    private val topSongRetryIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var latestProfileMediaId: String? = null

    @Volatile
    private var latestProfileController: Any? = null

    private fun dispatchSurfaceWork(
        kind: AppleMetadataRefreshKind,
        mediaId: String? = null,
        target: Any? = null,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
        work: () -> Unit,
    ) {
        val queue = refreshQueue
        if (queue == null) {
            runtime.mainHandler.post(work)
        } else {
            queue.enqueueAction(
                kind = kind,
                mediaId = mediaId,
                priority = priority,
                originalResolutionMode = originalResolutionMode,
                target = target,
                action = work,
            )
        }
    }

    fun installTopSongHooks() {
        val classes = artistClasses() ?: return
        val recycler = checkNotNull(classes["recycler"])
        val mediaEntity = checkNotNull(classes["media_entity"])
        val baseController = checkNotNull(classes["base_controller"])
        val artistController = checkNotNull(classes["artist_controller"])
        val topSongModel = checkNotNull(classes["top_song_model"])
        installPageLifecycle(artistController, recycler.clazz)
        installGenericProfileBuildHooks(baseController, mediaEntity)

        runCatching {
            val buildMethod = AppleReflection.findMethod(
                baseController.clazz,
                baseController.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_BUILD_METHOD
                ),
                parameterTypes = listOf(
                    String::class.java,
                    mediaEntity.clazz,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
            )
            val modelBindMethod = AppleReflection.findMethod(
                topSongModel.clazz,
                topSongModel.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, Any::class.java),
            )
            val titleField = topSongModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD
            )
            val subtitleField = topSongModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD
            )
            runtime.hookRegistrar.installHook(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = chain.args.getOrNull(0),
                        mediaId = host.mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    host.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.SONG,
                    )
                    librarySurfaceHooks.registerController(mediaId, controller)
                    associateTopSongWithProfileArtist(controller, mediaId)
                    requestPageOriginalMetadata(listOf(mediaId))
                },
                after = { chain, result ->
                    val model = result ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = chain.args.getOrNull(0),
                        mediaId = librarySurfaceHooks.entityMediaId(entity)
                            ?: host.mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    val snapshot = ArtistTopSongModelSnapshot(
                        mediaId = mediaId,
                        originalTitle = reflectiveField(model, titleField)?.toString(),
                        originalSubtitle = reflectiveField(model, subtitleField)?.toString(),
                        originalArtist = metadataStore.accountMetadata(mediaId)?.artist,
                    )
                    topSongModels[model] = snapshot
                },
            )
            runtime.hookRegistrar.installHook(
                modelBindMethod,
                before = { chain -> bindTopSongModel(chain.thisObject, chain.args.getOrNull(1), true) },
                after = { chain, _ ->
                    val model = chain.thisObject ?: return@installHook
                    bindTopSongModel(model, chain.args.getOrNull(1), false)
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页歌曲排行元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${modelBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页歌曲排行元数据 Hook 安装失败", it)
        }
    }

    fun installProfileHooks() {
        val classes = artistClasses() ?: return
        val mediaEntity = checkNotNull(classes["media_entity"])
        val artistController = checkNotNull(classes["artist_controller"])
        val headerModel = checkNotNull(classes["header_model"])
        runCatching {
            val buildMethod = AppleReflection.findMethod(
                artistController.clazz,
                artistController.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_PROFILE_BUILD_METHOD
                ),
                parameterTypes = listOf(
                    mediaEntity.clazz,
                    Boolean::class.javaPrimitiveType!!,
                    Set::class.java,
                ),
            )
            val headerBindMethod = AppleReflection.findMethod(
                headerModel.clazz,
                headerModel.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, Any::class.java),
            )
            val headerTitleField = headerModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD
            )

            runtime.hookRegistrar.installHook(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.firstOrNull() ?: return@installHook
                    val attributes = host.mediaApiEntityAttributes(entity) ?: return@installHook
                    val mediaId = host.mediaApiEntityCatalogId(entity, attributes)
                        ?: return@installHook
                    profileMediaIds[controller] = mediaId
                    latestProfileMediaId = mediaId
                    pageBuildData[controller] = ArtistPageBuildData(
                        artist = entity,
                        isAddMusicMode = chain.args.getOrNull(1) as? Boolean
                            ?: return@installHook,
                        selectedItemIds = chain.args.getOrNull(2),
                    )
                    host.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        knownAttributes = attributes,
                    )
                    host.enrichLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        attributes = attributes,
                    )
                    librarySurfaceHooks.registerController(mediaId, controller)
                    host.effectiveAlias(mediaId)?.let { alias ->
                        librarySurfaceHooks.applyAliasToEntity(
                            entity = entity,
                            kind = InAppLibraryEntityKind.ARTIST,
                            alias = alias,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_profile_build, contentId=$mediaId, " +
                                "entity=${entity.javaClass.name}@${System.identityHashCode(entity)}, " +
                                "attributeName=${host.mediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME)}, " +
                                "artistIds=${metadataStore.associatedArtistIds(mediaId)}, " +
                                "effective=${host.effectiveAlias(mediaId)?.let {
                                    "${it.title}/${it.artist}/${it.album}"
                                }}"
                        )
                    }
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val mediaId = profileMediaIds[controller] ?: return@installHook
                    val profileWork = {
                        host.markMetadataVisible(listOf(mediaId))
                        host.enrichLibraryEntitiesForResolution(listOf(mediaId))
                        host.effectiveAlias(mediaId)?.let { alias ->
                            host.applyAliasToMetadataRefs(
                                mediaId = mediaId,
                                alias = alias,
                                notifyModelChange = true,
                            )
                        }
                        if (host.shouldRequestOverride(mediaId)) {
                            host.scheduleMetadataResolution(
                                mediaIds = listOf(mediaId),
                                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                                originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                    }
                    dispatchSurfaceWork(
                        kind = AppleMetadataRefreshKind.ARTIST_BINDING,
                        mediaId = mediaId,
                        target = controller,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                        originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                        work = profileWork,
                    )
                },
            )
            runtime.hookRegistrar.installHook(
                headerBindMethod,
                before = { chain -> bindHeaderModel(
                    model = chain.thisObject,
                    holder = chain.args.getOrNull(1),
                    beginModelBind = true,
                    headerTitleField = headerTitleField,
                ) },
                after = { chain, _ ->
                    val binding = bindHeaderModel(
                        model = chain.thisObject,
                        holder = chain.args.getOrNull(1),
                        beginModelBind = false,
                        headerTitleField = headerTitleField,
                    )
                    if (BuildConfig.DEBUG) {
                        val model = chain.thisObject ?: return@installHook
                        val mediaId = headerMediaId(model, headerTitleField) ?: return@installHook
                        val root = binding?.let(dataBindingHooks::root)
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_profile_header_visible, contentId=$mediaId, " +
                                "position=${chain.args.getOrNull(0)}, " +
                                "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                                "modelTitle=${reflectiveField(model, headerTitleField)}, " +
                                "binding=${binding?.javaClass?.name}@" +
                                "${binding?.let(System::identityHashCode)}, " +
                                "bindingMediaId=${binding?.let(dataBindingHooks::mediaId)}, " +
                                "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                                "texts=${root?.let(dataBindingHooks::renderedTexts)}"
                        )
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页标题实时元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${headerBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页标题实时元数据 Hook 安装失败", it)
        }
    }

    fun handleFinalBinding(model: Any, finalHolder: Any?, position: Int?) {
        val classes = artistClasses() ?: return
        val topSongClass = classes["top_song_model"]?.clazz
        val headerResolved = classes["header_model"] ?: return
        val headerTitleField = headerResolved.target.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD
        )
        when {
            topSongClass?.isInstance(model) == true -> {
                val snapshot = topSongModels[model] ?: resolveGenericTopSongSnapshot(model) ?: return
                val binding = bindingFromFinalHolder(finalHolder)
                if (binding != null) {
                    topSongBindings[binding] = snapshot
                    dataBindingHooks.capture(binding)
                    dataBindingHooks.register(
                        mediaId = snapshot.mediaId,
                        binding = binding,
                        originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                    )
                }
                onFinalBound(
                    model = model,
                    mediaId = snapshot.mediaId,
                    kind = MetadataPageFinalBindingKind.ARTIST_TOP_SONG,
                    position = position,
                    binding = binding,
                )
            }

            headerResolved.clazz.isInstance(model) -> {
                val mediaId = headerMediaId(model, headerTitleField) ?: return
                headerModelIds[model] = mediaId
                val binding = bindingFromFinalHolder(finalHolder)
                if (binding != null) {
                    headerBindingIds[binding] = mediaId
                    dataBindingHooks.capture(binding)
                    dataBindingHooks.register(
                        mediaId = mediaId,
                        binding = binding,
                        originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                    )
                }
                onFinalBound(
                    model = model,
                    mediaId = mediaId,
                    kind = MetadataPageFinalBindingKind.ARTIST_HEADER,
                    position = position,
                    binding = binding,
                )
            }
        }
    }

    fun hasBuildData(controller: Any): Boolean = pageBuildData[controller] != null

    fun requestControllerBuild(controller: Any): Boolean {
        val buildData = pageBuildData[controller] ?: return false
        val target = artistClasses()?.get("artist_controller")?.target ?: return false
        AppleReflection.call(
            controller,
            target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_CONTROLLER_SET_DATA_METHOD),
            buildData.artist,
            buildData.isAddMusicMode,
            buildData.selectedItemIds,
        )
        return true
    }

    fun clearController(controller: Any) {
        pageBuildData.remove(controller)
        genericProfileTopSongIds.remove(controller)
        genericProfileTopSongTexts.remove(controller)
        val detachedMediaId = profileMediaIds[controller]
        profileMediaIds.remove(controller)
        if (latestProfileMediaId == detachedMediaId) latestProfileMediaId = null
        if (latestProfileController === controller) latestProfileController = null
    }

    fun fallbackArtistId(
        mediaId: String,
        existingArtistIds: List<String>,
        songArtistCredit: String?,
    ): String? = topSongCandidateArtistIds[mediaId].orEmpty()
        .mapNotNull { profileArtistId ->
            artistProfileFallbackArtistId(
                profileArtistId = profileArtistId,
                existingArtistIds = existingArtistIds,
                songArtistCredit = songArtistCredit,
                profileArtistCredits = host.knownArtistProfileCredits(profileArtistId),
            )
        }
        .distinct()
        .singleOrNull()

    fun clearTopSongCandidates(mediaId: String) {
        topSongCandidateArtistIds.remove(mediaId)
    }

    fun onBeginBindingModel(binding: Any) {
        headerBindingIds.remove(binding)
    }

    fun onBindingMediaIdChanged(binding: Any, mediaId: String) {
        topSongBindings[binding]
            ?.takeIf { snapshot -> snapshot.mediaId != mediaId }
            ?.let { topSongBindings.remove(binding) }
        headerBindingIds[binding]
            ?.takeIf { artistId -> artistId != mediaId }
            ?.let { headerBindingIds.remove(binding) }
    }

    fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode =
        if (topSongBindings[binding] != null) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST
        } else {
            InAppOriginalResolutionMode.AFTER_LOCALIZED
        }

    fun shouldInvalidateAppliedAlias(
        binding: Any,
        mediaId: String,
        appliedAlias: AppliedMetadataAlias,
        pendingAlias: AppliedMetadataAlias?,
        effectiveAlias: AppleInternalCatalogResolver.Alias,
        expectedTitle: String?,
        renderedTexts: Collection<String>,
    ): Boolean {
        if (headerBindingIds[binding] != mediaId) return false
        return shouldInvalidateArtistHeaderAppliedAlias(
            appliedAlias = appliedAlias,
            effectiveAlias = AppliedMetadataAlias(mediaId, effectiveAlias),
            pendingAlias = pendingAlias,
            expectedTitle = expectedTitle,
            renderedTexts = renderedTexts,
        )
    }

    fun subtitleForBinding(
        binding: Any?,
        defaultSubtitle: String?,
        replacementArtist: String,
    ): String? {
        val topSong = binding?.let { topSongBindings[it] } ?: return defaultSubtitle
        return artistProfileSubtitleWithArtist(
            originalSubtitle = topSong.originalSubtitle,
            originalArtist = topSong.originalArtist,
            replacementArtist = replacementArtist,
        )
    }

    fun isRecyclerAdapter(adapter: Any): Boolean {
        val classes = artistClasses() ?: return false
        val controllerClasses = listOfNotNull(
            classes["artist_controller"]?.clazz,
            classes["base_controller"]?.clazz,
        )
        if (controllerClasses.any { it.isInstance(adapter) }) return true
        return generateSequence(adapter.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
            }
            .any { value -> controllerClasses.any { it.isInstance(value) } }
    }

    private fun installGenericProfileBuildHooks(
        baseController: ResolvedAppleMusicHookClass,
        mediaEntity: ResolvedAppleMusicHookClass,
    ) {
        runCatching {
            val baseClass = runCatching {
                runtime.classLoader.loadClass(
                    "com.apple.android.music.profiles.BaseProfileEpoxyController",
                )
            }.getOrDefault(baseController.clazz)
            val mediaEntityClass = runCatching {
                runtime.classLoader.loadClass(
                    "com.apple.android.music.mediaapi.models.MediaEntity",
                )
            }.getOrDefault(mediaEntity.clazz)
            val buildMethod = baseClass.declaredMethods.firstOrNull { method ->
                method.name == "buildModels" &&
                    method.parameterTypes.size == 3 &&
                    !method.isBridge &&
                    method.parameterTypes[0].isAssignableFrom(mediaEntityClass) &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    Set::class.java.isAssignableFrom(method.parameterTypes[2])
            } ?: error("BaseProfileEpoxyController.buildModels(MediaEntity,Boolean,Set) unavailable")
            buildMethod.isAccessible = true
            runtime.hookRegistrar.installHook(buildMethod, before = { chain ->
                val controller = chain.thisObject ?: return@installHook
                val entity = chain.args.firstOrNull() ?: return@installHook
                registerGenericProfileEntities(controller, entity)
            })
            ProviderLogger.info(
                "Apple Music GenericProfile 歌曲排行预取 Hook 已安装: " +
                    "builder=${buildMethod.name}"
            )
            // In Apple Music 6.5.2 GenericProfile calls populateViews(key,
            // relationship, sectionIndex) and creates h1 directly from the
            // relationship. This is the actual source of Top Songs rows.
            val relationshipClass = runCatching {
                runtime.classLoader.loadClass(
                    "com.apple.android.music.mediaapi.models.internals.Relationship",
                )
            }.getOrNull()
            val populateMethod = relationshipClass?.let { relation ->
                baseClass.declaredMethods.firstOrNull { method ->
                    method.name == "populateViews" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[1].isAssignableFrom(relation) &&
                        method.parameterTypes[2] == Int::class.javaPrimitiveType
                }
            }
            if (populateMethod != null) {
                populateMethod.isAccessible = true
                runtime.hookRegistrar.installHook(populateMethod, before = { chain ->
                    val key = chain.args.getOrNull(0)?.toString() ?: return@installHook
                    if (key != "top-songs") return@installHook
                    val controller = chain.thisObject ?: return@installHook
                    val relationship = chain.args.getOrNull(1) ?: return@installHook
                    registerGenericProfileRelationship(controller, relationship)
                })
                ProviderLogger.info("Apple Music GenericProfile top-songs relationship Hook 已安装: method=${populateMethod.name}")
            }
        }.onFailure {
            ProviderLogger.error("Apple Music GenericProfile 歌曲排行预取 Hook 安装失败", it)
        }
    }

    private fun registerGenericProfileEntities(controller: Any, profileEntity: Any) {
        val profileAttributes = host.mediaApiEntityAttributes(profileEntity) ?: return
        val profileId = host.mediaApiEntityCatalogId(profileEntity, profileAttributes) ?: return
        profileMediaIds[controller] = profileId
        latestProfileMediaId = profileId
        host.registerLibraryEntity(
            mediaId = profileId,
            entity = profileEntity,
            kind = InAppLibraryEntityKind.ARTIST,
            knownAttributes = profileAttributes,
        )
        host.enrichLibraryEntity(
            mediaId = profileId,
            entity = profileEntity,
            kind = InAppLibraryEntityKind.ARTIST,
            attributes = profileAttributes,
        )
        librarySurfaceHooks.registerController(profileId, controller)

        val songIds = host.mediaApiEntityRelationshipEntities(profileEntity, "top-songs")
            .mapNotNull { songEntity ->
                val attributes = host.mediaApiEntityAttributes(songEntity) ?: return@mapNotNull null
                val mediaId = host.mediaApiEntityCatalogId(songEntity, attributes)
                    ?: return@mapNotNull null
                host.registerLibraryEntity(
                    mediaId = mediaId,
                    entity = songEntity,
                    kind = InAppLibraryEntityKind.SONG,
                    knownAttributes = attributes,
                )
                host.enrichLibraryEntity(
                    mediaId = mediaId,
                    entity = songEntity,
                    kind = InAppLibraryEntityKind.SONG,
                    attributes = attributes,
                )
                librarySurfaceHooks.registerController(mediaId, controller)
                associateTopSongWithProfileArtist(controller, mediaId)
                mediaId
            }
            .distinct()
        genericProfileTopSongIds[controller] = songIds
        latestProfileController = controller
        if (songIds.isNotEmpty()) {
            host.markMetadataVisible(songIds)
            host.enrichLibraryEntitiesForResolution(songIds)
            requestPageOriginalMetadata(songIds)
        }
    }

    private fun registerGenericProfileRelationship(controller: Any, relationship: Any) {
        val raw = runCatching { AppleReflection.call(relationship, "getEntities") }
            .getOrNull() ?: runCatching { AppleReflection.call(relationship, "getData") }.getOrNull()
        val entities = when (raw) {
            is Iterable<*> -> raw.filterNotNull()
            is Array<*> -> raw.filterNotNull()
            else -> emptyList()
        }
        val texts = LinkedHashMap<String, Pair<String?, String?>>()
        val ids = entities.mapNotNull { entity ->
            val attrs = host.mediaApiEntityAttributes(entity)
            val id = host.mediaApiEntityCatalogId(entity, attrs) ?: return@mapNotNull null
            host.registerLibraryEntity(id, entity, InAppLibraryEntityKind.SONG, attrs)
            host.enrichLibraryEntity(id, entity, InAppLibraryEntityKind.SONG, attrs ?: return@mapNotNull null)
            librarySurfaceHooks.registerController(id, controller)
            associateTopSongWithProfileArtist(controller, id)
            val title = attrs?.let { host.mediaApiAttribute(it, AppleMediaApiTextAttribute.NAME) }
            val artist = attrs?.let { host.mediaApiAttribute(it, AppleMediaApiTextAttribute.ARTIST_NAME) }
            texts[id] = title to artist
            id
        }.distinct()
        genericProfileTopSongIds[controller] = ids
        genericProfileTopSongTexts[controller] = texts
        latestProfileController = controller
        if (ids.isNotEmpty()) {
            host.markMetadataVisible(ids)
            host.enrichLibraryEntitiesForResolution(ids)
            requestPageOriginalMetadata(ids)
        }
    }

    private fun installPageLifecycle(
        controller: ResolvedAppleMusicHookClass,
        recyclerClass: Class<*>,
    ) {
        runCatching {
            val attached = AppleReflection.findMethod(
                controller.clazz,
                controller.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_ATTACH_METHOD
                ),
                parameterTypes = listOf(recyclerClass),
            )
            val detached = AppleReflection.findMethod(
                controller.clazz,
                controller.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_DETACH_METHOD
                ),
                parameterTypes = listOf(recyclerClass),
            )
            runtime.hookRegistrar.installHook(attached, after = { chain, _ ->
                val owner = chain.thisObject ?: return@installHook
                val recycler = chain.args.firstOrNull() as? RecyclerView ?: return@installHook
                host.onMetadataPageAttached(owner, recycler)
            })
            runtime.hookRegistrar.installHook(detached, before = { chain ->
                val owner = chain.thisObject ?: return@installHook
                clearController(owner)
                host.onMetadataPageDetached(owner)
            })
            ProviderLogger.info("Apple Music 歌手页元数据页面边界 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页元数据页面边界 Hook 安装失败", it)
        }
    }

    private fun bindTopSongModel(model: Any?, holder: Any?, beginModelBind: Boolean): Any? {
        model ?: return null
        val snapshot = topSongModels[model] ?: resolveGenericTopSongSnapshot(model) ?: return null
        val binding = dataBindingHooks.bindingFromHolder(holder) ?: return null
        if (beginModelBind) dataBindingHooks.beginModelBind(binding)
        topSongBindings[binding] = snapshot
        dataBindingHooks.capture(binding)
        dataBindingHooks.register(
            mediaId = snapshot.mediaId,
            binding = binding,
            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )
        return binding
    }

    private fun resolveGenericTopSongSnapshot(model: Any): ArtistTopSongModelSnapshot? {
        val resolved = artistClasses()?.get("top_song_model") ?: return null
        if (!resolved.clazz.isInstance(model)) return null
        val controller = host.activeMetadataPageOwner()?.takeIf { genericProfileTopSongIds.containsKey(it) }
            ?: latestProfileController
            ?: return null
        val ids = genericProfileTopSongIds[controller].orEmpty()
        if (ids.isEmpty()) return null
        val titleField = resolved.target.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD
        )
        val subtitleField = resolved.target.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD
        )
        val title = reflectiveField(model, titleField)?.toString()?.trim().orEmpty()
        val subtitle = reflectiveField(model, subtitleField)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && subtitle.isEmpty()) return null
        val titleKey = AppleInternalCatalogResolver.normalizedArtistNameKey(title)
        val subtitleKey = AppleInternalCatalogResolver.normalizedArtistNameKey(subtitle)
        val mediaId = ids.firstOrNull { id ->
            val account = metadataStore.accountMetadata(id)
            val alias = host.effectiveAlias(id)
            val captured = genericProfileTopSongTexts[controller]?.get(id)
            val knownTitles = listOf(account?.title, alias?.title, captured?.first)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            val knownArtists = listOf(account?.artist, alias?.artist, captured?.second)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            knownTitles.any { AppleInternalCatalogResolver.normalizedArtistNameKey(it) == titleKey } ||
                (subtitleKey.isNotEmpty() && knownArtists.any {
                    AppleInternalCatalogResolver.normalizedArtistNameKey(it) == subtitleKey
                })
        } ?: return null
        return ArtistTopSongModelSnapshot(
            mediaId = mediaId,
            originalTitle = title.takeIf(String::isNotEmpty),
            originalSubtitle = subtitle.takeIf(String::isNotEmpty),
            originalArtist = metadataStore.accountMetadata(mediaId)?.artist,
        ).also { snapshot ->
            topSongModels[model] = snapshot
            librarySurfaceHooks.registerController(mediaId, controller)
            associateTopSongWithProfileArtist(controller, mediaId)
            requestPageOriginalMetadata(listOf(mediaId))
        }
    }

    private fun bindHeaderModel(
        model: Any?,
        holder: Any?,
        beginModelBind: Boolean,
        headerTitleField: String,
    ): Any? {
        model ?: return null
        val mediaId = headerMediaId(model, headerTitleField) ?: return null
        headerModelIds[model] = mediaId
        val binding = dataBindingHooks.bindingFromHolder(holder) ?: return null
        if (beginModelBind) dataBindingHooks.beginModelBind(binding)
        headerBindingIds[binding] = mediaId
        dataBindingHooks.capture(binding)
        dataBindingHooks.register(mediaId, binding)
        return binding
    }

    private fun headerMediaId(model: Any, headerTitleField: String): String? {
        headerModelIds[model]?.let { return it }
        host.activeMetadataPageOwner()
            ?.let { owner -> profileMediaIds[owner] }
            ?.let { return it }
        val latestMediaId = latestProfileMediaId ?: return null
        val modelTitle = reflectiveField(model, headerTitleField)?.toString().orEmpty()
        val accountTitle = metadataStore.accountMetadata(latestMediaId)?.title.orEmpty()
        val modelKey = AppleInternalCatalogResolver.normalizedArtistNameKey(modelTitle)
        val accountKey = AppleInternalCatalogResolver.normalizedArtistNameKey(accountTitle)
        return latestMediaId.takeIf { modelKey.isNotEmpty() && modelKey == accountKey }
    }

    private fun bindingFromFinalHolder(holder: Any?): Any? {
        val methodName = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.EPOXY_FINAL_BIND)
            .target
            .runtimeMemberName(
                AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD
            )
        val modelHolder = holder?.let {
            runCatching { AppleReflection.call(it, methodName) }.getOrNull()
        }
        return dataBindingHooks.bindingFromHolder(modelHolder)
            ?: dataBindingHooks.bindingFromHolder(holder)
    }

    private fun onFinalBound(
        model: Any,
        mediaId: String,
        kind: MetadataPageFinalBindingKind,
        position: Int?,
        binding: Any?,
    ) {
        val shouldResolve = finalBoundResolutionIds[model] != mediaId
        finalBoundResolutionIds[model] = mediaId
        val finalBindWork = {
            host.markMetadataVisible(listOf(mediaId))
            host.enrichLibraryEntitiesForResolution(listOf(mediaId))
            val alias = host.effectiveAlias(mediaId)
            val shouldRequest = shouldResolve && host.shouldRequestOverride(mediaId)
            if (alias != null) {
                host.applyAliasToMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    notifyModelChange = false,
                )
            }
            if (shouldRequest) {
                host.scheduleMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            requestPageOriginalMetadata(listOf(mediaId))
            if (BuildConfig.DEBUG) {
                val root = binding?.let(dataBindingHooks::root)
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=artist_profile_final_bound, contentId=$mediaId, kind=$kind, " +
                        "position=$position, model=${model.javaClass.name}@" +
                        "${System.identityHashCode(model)}, " +
                        "binding=${binding?.javaClass?.name}@" +
                        "${binding?.let(System::identityHashCode)}, " +
                        "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=$shouldRequest"
                )
            }
        }
        dispatchSurfaceWork(
            kind = AppleMetadataRefreshKind.ARTIST_BINDING,
            mediaId = mediaId,
            target = model,
            priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
            work = finalBindWork,
        )
    }

    private fun associateTopSongWithProfileArtist(controller: Any, mediaId: String) {
        val profileArtistId = profileMediaIds[controller] ?: return
        topSongCandidateArtistIds.computeIfAbsent(mediaId) {
            ConcurrentHashMap.newKeySet()
        }.add(profileArtistId)
    }

    private fun requestPageOriginalMetadata(mediaIds: Collection<String>) {
        val pageMediaIds = mediaIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
            .toList()
        if (pageMediaIds.isEmpty() || !topSongRetryIds.addAll(pageMediaIds)) return
        host.markMetadataVisible(pageMediaIds)
        host.enrichLibraryEntitiesForResolution(pageMediaIds)
        host.retryOriginalMetadata(
            mediaIds = pageMediaIds,
            priority = AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )
    }

    private fun artistClasses(): Map<String, ResolvedAppleMusicHookClass>? = runCatching {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES)
            .associateBy { resolved ->
                resolved.target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE)
            }
    }.onFailure {
        ProviderLogger.error("Apple Music 歌手页运行时类解析失败", it)
    }.getOrNull()

    private fun reflectiveField(instance: Any, name: String): Any? =
        runCatching { AppleReflection.field(instance, name) }.getOrNull()
}

internal fun shouldInvalidateArtistHeaderAppliedAlias(
    appliedAlias: AppliedMetadataAlias?,
    effectiveAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    expectedTitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias == null || appliedAlias != effectiveAlias) return false
    if (pendingAlias == effectiveAlias) return false
    val expected = expectedTitle?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val rendered = renderedTexts.map(String::trim).filter(String::isNotEmpty)
    return rendered.isNotEmpty() && expected !in rendered
}

internal fun artistProfileTopSongMediaId(
    relationshipKey: Any?,
    mediaId: String?,
): String? {
    if (relationshipKey != "top-songs") return null
    return mediaId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

internal fun artistProfileFallbackArtistId(
    profileArtistId: String?,
    existingArtistIds: Collection<String>,
    songArtistCredit: String?,
    profileArtistCredits: Collection<String>,
): String? {
    if (existingArtistIds.isNotEmpty()) return null
    val artistId = profileArtistId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val credit = songArtistCredit?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (AppleInternalCatalogResolver.isCollaborationArtistName(credit)) return null
    val creditKey = AppleInternalCatalogResolver.normalizedArtistNameKey(credit)
        .takeIf(String::isNotEmpty)
        ?: return null
    val knownCreditKeys = profileArtistCredits.asSequence()
        .map(AppleInternalCatalogResolver::normalizedArtistNameKey)
        .filter(String::isNotEmpty)
        .toSet()
    return artistId.takeIf { creditKey in knownCreditKeys }
}

internal fun artistProfileSubtitleWithArtist(
    originalSubtitle: String?,
    originalArtist: String?,
    replacementArtist: String?,
): String? {
    val subtitle = originalSubtitle?.takeIf(String::isNotBlank) ?: return null
    val original = originalArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    val replacement = replacementArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    if (original == replacement) return subtitle
    if (subtitle == original) return replacement

    if (subtitle.startsWith(original)) {
        val suffix = subtitle.substring(original.length)
        val boundary = suffix.firstOrNull()
        if (
            boundary == null ||
            boundary.isWhitespace() ||
            boundary in setOf('·', '•', '—', '–', '-', '|', '/', '（', '(')
        ) {
            return replacement + suffix
        }
    }

    val separators = listOf(" · ", " • ", " — ", " – ")
    separators.forEach { separator ->
        val separatorIndex = subtitle.indexOf(separator)
        if (separatorIndex <= 0) return@forEach
        val credit = subtitle.substring(0, separatorIndex)
        if (
            AppleInternalCatalogResolver.normalizedArtistNameKey(credit) ==
            AppleInternalCatalogResolver.normalizedArtistNameKey(original)
        ) {
            return replacement + subtitle.substring(separatorIndex)
        }
    }
    return subtitle
}
