package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

data class TargetCurrentSong @JvmOverloads constructor(
    val item: Any,
    val details: CurrentSongDetails,
    val persistentId: Long? = null,
)

/** Shared target-process state from Apple's player-level metadata funnel. */
class CurrentSongIdentityCache {
    private val current = AtomicReference<TargetCurrentSong?>(null)
    private val listeners = CopyOnWriteArraySet<(TargetCurrentSong?) -> Unit>()
    private val recentIds = ArrayDeque<Long>()
    private val recentIdsLock = Any()

    @JvmOverloads
    fun publish(item: Any?, details: CurrentSongDetails?, persistentId: Long? = null) {
        val published = if (item != null && details != null && details.appleMusicId > 0L) {
            TargetCurrentSong(item, details, persistentId?.takeIf { it != 0L })
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

    fun addListener(listener: (TargetCurrentSong?) -> Unit): HostSubscription {
        listeners += listener
        current.get()?.let { published ->
            runCatching { listener(published) }
        }
        return HostSubscription { listeners -= listener }
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

