package dev.amenhancer.module.hook

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Phase 152 natural-transition lyrics refresh seam. Apple's
 * `PlayerLyricsViewFragment.o2` receives every playback metadata publish with
 * a context item and a per-version flags holder whose `a` field is Apple's own
 * item-changed signal. On 6.5.1 and 6.5.2 the context item is a Playlist (`pl.*` id), not
 * the track Adam ID; after Apple's body returns, the fragment's verified
 * current-item field is the authoritative track identity. Apple's native tail
 * only calls I2 while the adapter still has no rows, so a natural A→B
 * transition with stale rows never installs B's pointer. This coordinator
 * re-enters the exact I2 path after Apple has updated that field — with the
 * ready replacement when one exists, or through the ready-late ledger when B
 * is still preparing — and otherwise leaves Apple's behavior untouched.
 *
 * The hook path is pure coordination: no IO, no network, no native parse, no
 * lyric-state mutation. [decideLyricsItemUpdate] rejects same-item metadata
 * refreshes, o2 calls whose tail already invoked I2 (Apple's own install),
 * untracked songs, dead fragments and invalid ids; every other gate fails
 * open. The handled-id ledger is keyed by fragment identity, never equals,
 * holds fragments weakly and is bounded, so a replacement fragment with equal
 * state can never be confused with a handled one and repeated same-item
 * metadata publishes cannot loop I2.
 */
internal class LyricsItemUpdateCoordinator(
    private val installMethod: Method,
    private val flags: ItemUpdateFlags,
    private val seam: CurrentItemIdentitySeam,
    private val readyReplacementFor: (Long) -> Any?,
    private val isTracking: (Long) -> Boolean,
    private val isFragmentUsable: (Any) -> Boolean,
    private val readyReapply: CustomLyricsReadyReapply,
    private val logger: (String) -> Unit,
) {
    private val handled = mutableListOf<HandledItem>()

    /**
     * o2 after-hook entry: reads Apple's verified item-changed flag from the
     * flags holder, then reads the exact current item from the fragment field
     * after Apple's body has updated it. It either re-enters I2 with the ready
     * replacement or records a ready-late miss. All failures fail open,
     * leaving the native pointer path untouched.
     */
    fun onItemUpdate(fragment: Any, flagsHolder: Any?, appleInvokedI2: Boolean) {
        val itemChanged = flags.isItemChanged(flagsHolder)
        val appleMusicId = if (itemChanged) seam.currentItemAdamIdOf(fragment) else null
        val replacement = if (appleMusicId == null) null else readyReplacementFor(appleMusicId)
        when (
            decideLyricsItemUpdate(
                itemChanged = itemChanged,
                appleInvokedI2 = appleInvokedI2,
                fragmentUsable = isFragmentUsable(fragment),
                itemAdamId = appleMusicId,
                previouslyHandledAdamId = handledAdamIdOf(fragment),
                tracked = appleMusicId != null && isTracking(appleMusicId),
                replacementReady = replacement != null,
            )
        ) {
            LyricsItemUpdateAction.IGNORE -> Unit
            LyricsItemUpdateAction.RECORD_MISS -> {
                val appleMusicId = appleMusicId ?: return
                recordHandled(fragment, appleMusicId)
                readyReapply.recordMiss(fragment, appleMusicId)
            }
            LyricsItemUpdateAction.REENTER -> {
                val appleMusicId = appleMusicId ?: return
                recordHandled(fragment, appleMusicId)
                readyReapply.dismiss(fragment)
                if (replacement == null) {
                    readyReapply.recordMiss(fragment, appleMusicId)
                } else {
                    try {
                        installMethod.invoke(fragment, replacement)
                    } catch (error: Throwable) {
                        logger("custom lyrics item update re-entry failed: $error")
                    }
                }
            }
        }
    }

    private fun handledAdamIdOf(fragment: Any): Long? = synchronized(handled) {
        sweepCleared()
        handled.firstOrNull { it.key.get() === fragment }?.appleMusicId
    }

    private fun recordHandled(fragment: Any, appleMusicId: Long) {
        synchronized(handled) {
            sweepCleared()
            handled.firstOrNull { it.key.get() === fragment }?.let { existing ->
                existing.appleMusicId = appleMusicId
                return@synchronized
            }
            if (handled.size >= MAX_HANDLED) handled.removeAt(0)
            handled += HandledItem(FragmentKey(fragment), appleMusicId)
        }
    }

    private fun sweepCleared() {
        val iterator = handled.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.get() == null) {
                iterator.remove()
            }
        }
    }

    private data class HandledItem(
        val key: FragmentKey,
        var appleMusicId: Long,
    )

    /**
     * The list owns this wrapper, while the wrapper owns only a weak reference
     * to the Fragment — the same identity-keying pattern as the ready-late
     * ledger, so a dead fragment cannot keep an entry alive.
     */
    private class FragmentKey(referent: Any) : WeakReference<Any>(referent)

    private companion object {
        const val MAX_HANDLED = 16
    }
}

/**
 * Reads the verified item-changed flag (`a`) from Apple's per-version o2
 * flags holder (`e$c` on 6.5.0, `d$c` on 6.5.1, `e$c` on 6.5.2). The field is public,
 * non-static, boolean and declared on the exact flags type carried by the
 * resolved o2 method, so a holder of any other shape is rejected.
 */
internal class ItemUpdateFlags(flagsType: Class<*>) {
    private val changedField: Field = flagsType.getField("a").apply {
        require(!Modifier.isStatic(modifiers) && type == java.lang.Boolean.TYPE)
        isAccessible = true
    }

    fun isItemChanged(flagsHolder: Any?): Boolean =
        flagsHolder != null && runCatching { changedField.getBoolean(flagsHolder) }
            .getOrDefault(false)
}

internal enum class LyricsItemUpdateAction {
    /** Leave Apple's own behavior untouched. */
    IGNORE,

    /** Re-enter the exact I2 path with the ready replacement now. */
    REENTER,

    /** The replacement is still preparing; hand the fragment to the ready-late ledger. */
    RECORD_MISS,
}

/**
 * Pure item-update decision. The verified flags `a` field is Apple's own
 * item-changed signal; the previously-handled id prevents repeated I2 on
 * same-item metadata updates; an o2 call whose tail already invoked I2 needs
 * no module re-entry; untracked songs, unusable fragments and invalid ids
 * keep the native path untouched.
 */
internal fun decideLyricsItemUpdate(
    itemChanged: Boolean,
    appleInvokedI2: Boolean,
    fragmentUsable: Boolean,
    itemAdamId: Long?,
    previouslyHandledAdamId: Long?,
    tracked: Boolean,
    replacementReady: Boolean,
): LyricsItemUpdateAction {
    if (!itemChanged || appleInvokedI2 || !fragmentUsable) return LyricsItemUpdateAction.IGNORE
    val id = itemAdamId ?: return LyricsItemUpdateAction.IGNORE
    if (id <= 0L) return LyricsItemUpdateAction.IGNORE
    if (id == previouslyHandledAdamId) return LyricsItemUpdateAction.IGNORE
    if (!tracked) return LyricsItemUpdateAction.IGNORE
    return if (replacementReady) {
        LyricsItemUpdateAction.REENTER
    } else {
        LyricsItemUpdateAction.RECORD_MISS
    }
}

/**
 * Minimal thread-local o2 update context shared by the I2 and o2 hooks. Apple
 * calls I2 from inside the o2 body on the same thread; the marker set by the
 * I2 hook is consumed by the o2 after-hook so a re-entry is never added on
 * top of Apple's own install. Module re-entries run under [reentering] so
 * they never mark the context, and the marker is cleared on every o2 entry so
 * an I2 invocation outside an o2 call can never leak into the next one.
 */
internal class LyricsItemUpdateContext {
    private val threadState: ThreadLocal<O2State> = ThreadLocal.withInitial { O2State() }

    fun enterO2() {
        val state = currentState()
        state.insideO2 = true
        state.appleInvokedI2 = false
    }

    fun exitO2() {
        val state = currentState()
        state.insideO2 = false
        state.appleInvokedI2 = false
    }

    fun markAppleInvokedI2() {
        val state = currentState()
        if (state.insideO2 && !state.reentering) state.appleInvokedI2 = true
    }

    /** Reads and consumes the marker, so it is reported for at most one o2 call. */
    fun appleInvokedI2DuringO2(): Boolean {
        val state = currentState()
        val reported = state.appleInvokedI2
        state.appleInvokedI2 = false
        return reported
    }

    fun <T> reentering(block: () -> T): T {
        val state = currentState()
        state.reentering = true
        try {
            return block()
        } finally {
            state.reentering = false
        }
    }

    private fun currentState(): O2State = threadState.get() ?: O2State().also {
        threadState.set(it)
    }

    private class O2State {
        var insideO2 = false
        var appleInvokedI2 = false
        var reentering = false
    }
}
