/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import dev.amenhancer.module.hook.FragmentMemberRequirements
import dev.amenhancer.module.hook.FragmentProfileMembers
import org.json.JSONObject
import java.lang.reflect.Modifier

/** Complete, version-owned invocation inventory, resolved before the Compose hook is registered. */
internal class ArtistComposeProfileContract private constructor(
    names: JSONObject,
    members: FragmentProfileMembers,
    classes: Map<String, ResolvedAppleMusicHookClass>,
) {
    private val viewModelClass = checkNotNull(classes[VIEW_MODEL_ROLE]) {
        "Declared Compose artist ViewModel is unavailable"
    }.clazz
    val successStateClass: Class<*> = checkNotNull(classes[SUCCESS_ROLE]) {
        "Declared Compose artist Success state is unavailable"
    }.clazz
    private val mediaEntityClass = checkNotNull(classes["media_entity"]) {
        "Compose artist media entity is unavailable"
    }.clazz

    val setUiState = members.method("setUiState")
    private val headerData = members.method("headerData")
    private val shelves = members.method("shelves")
    private val entityType = members.method("entityType")
    private val entityTitle = members.method("entityTitle")
    private val entityViews = members.method("entityViews")
    private val viewEntities = members.method("viewEntities")
    private val id = members.field("id")
    private val title = members.field("title")
    private val header = members.field("header")
    private val shelfData = members.field("shelves")
    private val entity = members.field("entity")
    private val revision = members.field("revision")
    private val constructor = members.constructor("success")
    val topSongsView: String = names.getString("topSongsView")
    private val artistType = names.getString("artistType")

    init {
        check(topSongsView.isNotBlank() && artistType.isNotBlank()) { "Compose artist view/type keys missing" }
        check(listOf(setUiState, headerData, shelves, entityType, entityTitle, entityViews, viewEntities).all {
            !Modifier.isStatic(it.modifiers)
        }) { "Compose artist members must be instance methods" }
        check(setUiState.declaringClass == viewModelClass && setUiState.returnType == Void.TYPE &&
            setUiState.parameterTypes.size == 1 &&
            setUiState.parameterTypes[0].isAssignableFrom(successStateClass)) { "Compose state publisher mismatch" }
        check(listOf(headerData, shelves).all {
            it.declaringClass == viewModelClass && it.parameterTypes.contentEquals(arrayOf(mediaEntityClass))
        }) { "Compose artist builders must consume the declared media entity" }
        val fields = listOf(id, title, header, shelfData, entity, revision)
        check(fields.distinct().size == fields.size && fields.all { it.declaringClass == successStateClass }) {
            "Compose Success field roles must be distinct instance members of the declared state"
        }
        check(id.type == String::class.java && title.type == String::class.java &&
            revision.type == java.lang.Long.TYPE && entity.type == mediaEntityClass &&
            shelfData.type == List::class.java && shelves.returnType == shelfData.type &&
            headerData.returnType == header.type) { "Compose Success field/builder types disagree" }
        check(constructor.declaringClass == successStateClass && constructor.parameterTypes.contentEquals(
            arrayOf(id.type, title.type, header.type, shelfData.type, entity.type, revision.type),
        )) { "Compose Success constructor does not match contract version 1" }
        check(listOf(entityType, entityTitle, entityViews).all {
            it.declaringClass == mediaEntityClass && it.parameterTypes.isEmpty()
        } && entityType.returnType == String::class.java && entityTitle.returnType == String::class.java &&
            entityViews.returnType == Map::class.java) { "Compose entity getters mismatch" }
        check(viewEntities.parameterTypes.isEmpty() && viewEntities.returnType.isArray &&
            viewEntities.returnType.componentType == mediaEntityClass) { "Compose view entity getter mismatch" }
    }

    fun stateEntity(state: Any): Any? = entity.get(state)
    fun stateTitle(state: Any): Any? = title.get(state)
    fun isArtistEntity(value: Any): Boolean =
        mediaEntityClass.isInstance(value) && entityType.invoke(value) == artistType

    fun topSongEntities(value: Any): List<Any> {
        val views = entityViews.invoke(value) as? Map<*, *> ?: return emptyList()
        val view = views[topSongsView] ?: return emptyList()
        check(viewEntities.declaringClass.isInstance(view)) { "Compose artist view has an unexpected model: ${view.javaClass.name}" }
        val values = viewEntities.invoke(view) as? Array<*> ?: return emptyList()
        return values.filterNotNull().onEach {
            check(mediaEntityClass.isInstance(it)) { "Compose artist view contains an unexpected entity" }
        }
    }

    /**
     * Every entity rendered by the native shelves (top songs, latest release, albums, ...).
     * extractShelves reads the same views map, so these are the objects the shelf items show.
     */
    fun shelfEntities(value: Any): List<Pair<String, Any>> {
        val views = entityViews.invoke(value) as? Map<*, *> ?: return emptyList()
        return views.entries.flatMap { (key, view) ->
            if (key !is String || view == null || !viewEntities.declaringClass.isInstance(view)) {
                return@flatMap emptyList()
            }
            val values = viewEntities.invoke(view) as? Array<*> ?: return@flatMap emptyList()
            values.filterNotNull().filter(mediaEntityClass::isInstance).map { key to it }
        }
    }

    fun entityTypeOf(value: Any): String? =
        if (mediaEntityClass.isInstance(value)) entityType.invoke(value) as? String else null

    /**
     * Contract v1 preserves native identity and rebuilds immutable presentation data.
     * Shelf entities are mutated in place, so a republish must change the revision: Success is
     * a data class and the host StateFlow drops equal values, which hid shelf title rewrites.
     */
    fun rebuildState(owner: Any, state: Any, value: Any, bumpRevision: Boolean = false): Any =
        constructor.newInstance(
            id.get(state),
            entityTitle.invoke(value) ?: title.get(state),
            headerData.invoke(owner, value) ?: header.get(state),
            shelves.invoke(owner, value) ?: shelfData.get(state),
            value,
            composeProfileRevision(revision.getLong(state), bumpRevision),
        )

    companion object {
        const val SECTION = "artistComposeProfile"
        const val VIEW_MODEL_ROLE = "compose_profile_view_model"
        const val SUCCESS_ROLE = "compose_profile_success_state"
        val SURFACE_ROLES = setOf(VIEW_MODEL_ROLE, SUCCESS_ROLE)
        val REQUIRED = FragmentMemberRequirements(
            methods = setOf("setUiState", "headerData", "shelves", "entityType", "entityTitle", "entityViews", "viewEntities"),
            fields = setOf("id", "title", "header", "shelves", "entity", "revision"),
            constructors = setOf("success"),
        )

        private fun document(version: AppleMusicVersion): JSONObject? {
            val name = version.versionName ?: return null
            val code = version.versionCode ?: return null
            return AppleMusicHostProfiles.find("com.apple.android.music", name, code)?.document
        }

        /** A partial declaration is an applicable, broken contract; resolution success is not eligibility. */
        fun isDeclared(version: AppleMusicVersion): Boolean = document(version)?.has(SECTION) == true ||
            AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ARTIST_SURFACE_CLASSES).any {
                it.runtimeMemberNameOrNull(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE) in SURFACE_ROLES
            }

        fun resolve(version: AppleMusicVersion, loader: ClassLoader,
            classes: Map<String, ResolvedAppleMusicHookClass>): ArtistComposeProfileContract {
            val roles = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ARTIST_SURFACE_CLASSES)
                .mapNotNull { it.runtimeMemberNameOrNull(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE) }
            check((SURFACE_ROLES + "media_entity").all { role -> roles.count { it == role } == 1 }) {
                "Compose artist surface roles are missing or ambiguous"
            }
            val names = checkNotNull(document(version)?.optJSONObject(SECTION)) { "Declared Compose artist contract missing" }
            check(names.getInt("contractVersion") == 1) { "Unsupported Compose artist contract version" }
            return ArtistComposeProfileContract(names, FragmentProfileMembers(loader, names, SECTION, REQUIRED), classes)
        }
    }
}

/** Next Success revision; a republish must differ from the state it replaces. */
internal fun composeProfileRevision(current: Long, bump: Boolean): Long =
    if (bump) current + 1 else current

/** Maps a shelf MediaEntity type to the alias kind used by the existing entity pipeline. */
internal fun composeShelfEntityKind(type: String?): InAppLibraryEntityKind? = when (type) {
    "songs", "library-songs" -> InAppLibraryEntityKind.SONG
    "albums", "library-albums" -> InAppLibraryEntityKind.ALBUM
    else -> null
}
