package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

internal data class TargetCurrentSong(
    val item: Any,
    val details: CurrentSongDetails,
)

/** Shared target-process state from Apple's player-level metadata funnel. */
internal class CurrentSongIdentityCache {
    private val current = AtomicReference<TargetCurrentSong?>(null)
    private val listeners = CopyOnWriteArraySet<(TargetCurrentSong?) -> Unit>()
    private val recentIds = ArrayDeque<Long>()
    private val recentIdsLock = Any()

    fun publish(item: Any?, details: CurrentSongDetails?) {
        val published = if (item != null && details != null && details.appleMusicId > 0L) {
            TargetCurrentSong(item, details)
        } else {
            null
        }
        current.set(published)
        synchronized(recentIdsLock) {
            if (published == null) {
                recentIds.clear()
            } else {
                recentIds.remove(published.details.appleMusicId)
                recentIds.addLast(published.details.appleMusicId)
                while (recentIds.size > MAX_RECENT_IDS) recentIds.removeFirst()
            }
        }
        listeners.forEach { listener ->
            runCatching { listener(published) }
        }
    }

    fun addListener(listener: (TargetCurrentSong?) -> Unit) {
        listeners += listener
        current.get()?.let { published ->
            runCatching { listener(published) }
        }
    }

    fun current(): TargetCurrentSong? = current.get()

    /** Allows a stale fragment ID only when it was recently observed as current. */
    fun canRebind(fragmentAdamId: Long?, publishedAdamId: Long?): Boolean {
        if (publishedAdamId == null || publishedAdamId <= 0L) return false
        if (fragmentAdamId == null) return true
        return synchronized(recentIdsLock) { fragmentAdamId in recentIds }
    }

    private companion object {
        const val MAX_RECENT_IDS = 8
    }
}

/**
 * Publishes the verified current-item identity for custom-lyrics hooks and
 * Apple Music's embedded AM++ settings. Reuses [CurrentItemIdentitySeam] and
 * never falls back to title or metadata matching.
 */
internal class AppleMusicCurrentSongIdentityTarget(
    private val symbols: TargetSymbolResolver,
    private val cache: CurrentSongIdentityCache,
) : CurrentSongIdentityTarget {
    override fun install(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        val metadataPublishResolution = symbols.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val metadataPublishMethod = metadataPublishResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(metadataPublishResolution.summary)
        val converterResolution = symbols.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)
        val converterMethod = converterResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(converterResolution.summary)
        if (!runCatching {
                metadataPublishMethod.isAccessible = true
                converterMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "Player metadata identity surface could not be made accessible; " +
                    listOf(metadataPublishResolution.summary, converterResolution.summary)
                        .joinToString("; "),
            )
        }
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val hooked = runCatching {
            ModernXposedRuntime.hookMethod(metadataPublishMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val item = converterMethod.invoke(null, param.args.getOrNull(0))
                        cache.publish(item, seam.detailsOfItem(item))
                    }.onFailure { error ->
                        ModernXposedRuntime.log("current song identity publish failed: $error")
                    }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "Player metadata publish method could not be hooked; ${metadataPublishResolution.summary}",
            )
        }
        return TargetCapabilityInstall.Active(
            "Current song identity cache installed for embedded settings; " +
                listOfNotNull(
                    installMethodResolution.summary,
                    metadataPublishResolution.summary,
                    converterResolution.summary,
                    seam.fieldSummary.orEmpty(),
                    seam.metadataSummary,
                ).joinToString("; "),
        )
    }
}
