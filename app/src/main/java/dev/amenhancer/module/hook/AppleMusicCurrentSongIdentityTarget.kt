package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.CurrentSongIdentityProtocol
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class TargetCurrentSong(
    val item: Any,
    val details: CurrentSongDetails,
)

/** Shared target-process state from Apple's player-level metadata funnel. */
internal class CurrentSongIdentityCache(
    private val invalidIdentityDebounceMs: Long = DEFAULT_INVALID_IDENTITY_DEBOUNCE_MS,
    @Suppress("UNUSED_PARAMETER")
    private val validIdentityDebounceMs: Long = if (invalidIdentityDebounceMs > 0L) {
        DEFAULT_VALID_IDENTITY_DEBOUNCE_MS
    } else {
        0L
    },
) {
    private val current = AtomicReference<TargetCurrentSong?>(null)
    private val listeners = CopyOnWriteArraySet<(TargetCurrentSong?) -> Unit>()
    private val recentIds = ArrayDeque<Long>()
    private val recentIdsLock = Any()
    private val invalidationLock = Any()
    private val invalidationGeneration = AtomicLong(0L)
    private val invalidationExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ampp-current-song-identity").apply { isDaemon = true }
    }
    private var pendingInvalidation: ScheduledFuture<*>? = null
    private var pendingValidIdentityChange: ScheduledFuture<*>? = null
    private var pendingValidIdentity: TargetCurrentSong? = null

    fun publish(item: Any?, details: CurrentSongDetails?) {
        val published = if (item != null && details != null && details.appleMusicId > 0L) {
            TargetCurrentSong(item, details)
        } else {
            null
        }
        if (published == null && current.get() != null && invalidIdentityDebounceMs > 0L) {
            scheduleInvalidation()
            return
        }
        val currentId = current.get()?.details?.appleMusicId
        if (
            published != null &&
            currentId != null &&
            currentId != published.details.appleMusicId &&
            validIdentityDebounceMs > 0L
        ) {
            scheduleValidIdentityChange(published)
            return
        }
        cancelPendingIdentityChanges()
        commit(published)
    }

    private fun commit(published: TargetCurrentSong?) {
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

    private fun scheduleInvalidation() {
        synchronized(invalidationLock) {
            pendingValidIdentityChange?.cancel(false)
            pendingValidIdentityChange = null
            pendingValidIdentity = null
            pendingInvalidation?.cancel(false)
            val generation = invalidationGeneration.incrementAndGet()
            pendingInvalidation = invalidationExecutor.schedule({
                val shouldClear = synchronized(invalidationLock) {
                    generation == invalidationGeneration.get() && current.get() != null
                }
                if (!shouldClear) return@schedule
                commit(null)
            }, invalidIdentityDebounceMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Apple Music can transiently publish a stale, but otherwise valid, queue
     * item while a song is changing. Confirm a different ID briefly before
     * letting it invalidate the active automatic-lyrics generation.
     */
    private fun scheduleValidIdentityChange(candidate: TargetCurrentSong) {
        synchronized(invalidationLock) {
            val pending = pendingValidIdentity
            if (pending?.details?.appleMusicId == candidate.details.appleMusicId) {
                pendingValidIdentity = candidate
                return
            }
            pendingInvalidation?.cancel(false)
            pendingInvalidation = null
            pendingValidIdentityChange?.cancel(false)
            val generation = invalidationGeneration.incrementAndGet()
            pendingValidIdentity = candidate
            pendingValidIdentityChange = invalidationExecutor.schedule({
                val confirmed = synchronized(invalidationLock) {
                    if (generation != invalidationGeneration.get()) return@synchronized null
                    pendingValidIdentity.also {
                        pendingValidIdentity = null
                        pendingValidIdentityChange = null
                    }
                } ?: return@schedule
                if (current.get()?.details?.appleMusicId == confirmed.details.appleMusicId) return@schedule
                commit(confirmed)
            }, validIdentityDebounceMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelPendingIdentityChanges() {
        synchronized(invalidationLock) {
            invalidationGeneration.incrementAndGet()
            pendingInvalidation?.cancel(false)
            pendingInvalidation = null
            pendingValidIdentityChange?.cancel(false)
            pendingValidIdentityChange = null
            pendingValidIdentity = null
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
        // The Apple-ID and metadata fallback stages may legitimately run for
        // several seconds. Do not clear a live identity while that chain is
        // still in flight; a persistently different next-song identity is
        // still confirmed within the shorter valid-ID window below.
        const val DEFAULT_INVALID_IDENTITY_DEBOUNCE_MS = 30_000L
        const val DEFAULT_VALID_IDENTITY_DEBOUNCE_MS = 750L
    }
}

/** Responds only to the module's signature-protected, user-initiated requests. */
internal class CurrentSongIdentityRequestResponder(
    private val application: Application,
    private val cache: CurrentSongIdentityCache,
    private val logger: (String) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CurrentSongIdentityProtocol.REQUEST_ACTION) return
            val token = intent.getStringExtra(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN)
                ?.takeIf(String::isNotBlank)
                ?: return
            val resultReceiver = intent.resultReceiver() ?: return
            val details = cache.current()?.details
            resultReceiver.send(
                if (details == null) {
                    CurrentSongIdentityProtocol.RESULT_UNAVAILABLE
                } else {
                    CurrentSongIdentityProtocol.RESULT_AVAILABLE
                },
                Bundle().apply {
                    putString(CurrentSongIdentityProtocol.EXTRA_REQUEST_TOKEN, token)
                    details?.let {
                        putLong(CurrentSongIdentityProtocol.EXTRA_APPLE_MUSIC_ID, it.appleMusicId)
                        it.title?.let { title ->
                            putString(CurrentSongIdentityProtocol.EXTRA_SONG_TITLE, title)
                        }
                        it.artist?.let { artist ->
                            putString(CurrentSongIdentityProtocol.EXTRA_SONG_ARTIST, artist)
                        }
                        it.album?.let { album ->
                            putString(CurrentSongIdentityProtocol.EXTRA_SONG_ALBUM, album)
                        }
                        it.durationMs?.takeIf { duration -> duration > 0L }?.let { duration ->
                            putLong(CurrentSongIdentityProtocol.EXTRA_SONG_DURATION_MS, duration)
                        }
                    }
                },
            )
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(): Boolean = runCatching {
        val filter = IntentFilter(CurrentSongIdentityProtocol.REQUEST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                receiver,
                filter,
                CurrentSongIdentityProtocol.REQUEST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(
                receiver,
                filter,
                CurrentSongIdentityProtocol.REQUEST_PERMISSION,
                null,
            )
        }
        true
    }.onFailure { error ->
        logger("current song identity request receiver failed: $error")
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(
                CurrentSongIdentityProtocol.EXTRA_RESULT_RECEIVER,
                ResultReceiver::class.java,
            )
        } else {
            getParcelableExtra(CurrentSongIdentityProtocol.EXTRA_RESULT_RECEIVER)
        }
}

/**
 * Publishes the verified current-item identity for SettingsActivity requests.
 * Reuses [CurrentItemIdentitySeam] and never falls back to title or metadata
 * matching; the capability reports missing or ambiguous independently.
 */
internal class AppleMusicCurrentSongIdentityTarget(
    private val application: Application,
    private val symbols: TargetSymbolResolver,
    private val cache: CurrentSongIdentityCache,
    private val registerRequestResponder: Boolean = true,
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
        if (registerRequestResponder && !CurrentSongIdentityRequestResponder(
                application = application,
                cache = cache,
                logger = ModernXposedRuntime::log,
            ).register()
        ) {
            return TargetCapabilityInstall.Degraded(
                "Current song identity request receiver could not be registered; " +
                    installMethodResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            if (registerRequestResponder) {
                "Current song identity request responder installed; "
            } else {
                "Current song identity cache installed for embedded settings; "
            } +
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
