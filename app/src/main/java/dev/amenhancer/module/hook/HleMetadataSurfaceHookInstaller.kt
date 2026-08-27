package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember

/**
 * Installs the remaining HLE metadata surface points from the exact Apple
 * Music hook profiles. Specialized HLE playback/content-item hooks do the
 * identity-aware work; this small adapter keeps the library/search/listen-now
 * profile seams alive and applies a conservative string alias when a profiled
 * zero-argument getter exposes the active song.
 */
internal class HleMetadataSurfaceHookInstaller(
    private val runtime: AppleMusicProviderRuntime,
    private val effectiveAlias: (String) -> AppleInternalCatalogResolver.Alias?,
    private val currentMediaId: () -> String?,
) {
    private val points = listOf(
        AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
        AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER,
        AppleMusicHookPoint.IN_APP_QUEUE_UPDATE,
        AppleMusicHookPoint.IN_APP_HISTORY_UPDATE,
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND,
        AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
        AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER,
        AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND,
        AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY,
        AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES,
        AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES,
        AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES,
        AppleMusicHookPoint.ARTIST_SURFACE_CLASSES,
        AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER,
        AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
        AppleMusicHookPoint.LISTEN_NOW_MODEL,
        AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
        AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
        AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
        AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
        AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW,
        AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
    )

    fun install() {
        points.forEach { point ->
            installMethodPoint(point)
            installClassPoint(point)
        }
        ProviderLogger.info("HLE metadata surface profile hooks installed: points=${points.size}")
    }

    private fun installMethodPoint(point: AppleMusicHookPoint) {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(point)
            runtime.hookRegistrar.installHook(resolved.method)
        }.onFailure { error ->
            ProviderLogger.debug("HLE metadata method point unavailable: $point (${error.message})")
        }
    }

    private fun installClassPoint(point: AppleMusicHookPoint) {
        val resolvedClasses = runCatching {
            runtime.hookResolver.resolveClasses(point)
        }.getOrDefault(emptyList())
        resolvedClasses.forEach { resolved ->
            resolved.target.runtimeMemberNames.forEach { (member, name) ->
                if (!isTextMember(member)) return@forEach
                val method = runCatching {
                    AppleReflection.findMethod(resolved.clazz, name, parameterCount = 0)
                }.getOrNull() ?: return@forEach
                if (method.returnType != String::class.java) return@forEach
                runtime.hookRegistrar.installResultOverrideHook(method) { _, original ->
                    val id = currentMediaId() ?: return@installResultOverrideHook original
                    val alias = effectiveAlias(id) ?: return@installResultOverrideHook original
                    when {
                        member.name.contains("ARTIST", ignoreCase = true) -> alias.artist
                        member.name.contains("ALBUM", ignoreCase = true) ||
                            member.name.contains("COLLECTION", ignoreCase = true) -> alias.album
                        else -> alias.title
                    }.takeIf(String::isNotBlank) ?: original
                }
            }
        }
    }

    private fun isTextMember(member: AppleMusicRuntimeMember): Boolean {
        val name = member.name
        return name.contains("TITLE", ignoreCase = true) ||
            name.contains("ARTIST", ignoreCase = true) ||
            name.contains("ALBUM", ignoreCase = true) ||
            name.contains("COLLECTION", ignoreCase = true) ||
            name.contains("NAME", ignoreCase = true)
    }
}
