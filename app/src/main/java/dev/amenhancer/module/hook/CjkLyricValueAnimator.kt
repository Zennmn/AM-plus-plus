package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Small, host-independent timing policy for the CJK lyric animator.
 *
 * Keeping this policy separate from [CjkLyricValueAnimator] makes the safety
 * rules testable on the local JVM, where Android's animation clock is not
 * available.  A caller may still provide an explicit stagger when it has the
 * host's word timing available.
 */
object CjkLyricAnimationPolicy {
    const val DEFAULT_DURATION_MS = 420L
    const val MIN_DURATION_MS = 120L
    const val MAX_DURATION_MS = 1_600L
    const val MAX_STAGGER_MS = 180L
    const val MAX_START_DELAY_MS = 8_000L

    /** Return a finite duration suitable for ValueAnimator. */
    fun clampDurationMs(durationMs: Long): Long = durationMs.coerceIn(
        MIN_DURATION_MS,
        MAX_DURATION_MS,
    )

    /** Alias with the shorter name used by callers that do not need the unit suffix. */
    fun clampDuration(durationMs: Long): Long = clampDurationMs(durationMs)

    /**
     * Derive a modest word stagger.  More words make each offset smaller so a
     * complete line does not spend several seconds catching up.
     */
    fun staggerMs(durationMs: Long, targetCount: Int): Long {
        val count = targetCount.coerceAtLeast(1)
        if (count <= 1) return 0L
        val duration = clampDurationMs(durationMs)
        return (duration / (count.toLong() * 2L)).coerceIn(0L, MAX_STAGGER_MS)
    }

    /** Alias useful in tests and integrations that prefer a verb-style name. */
    fun calculateStaggerMs(durationMs: Long, targetCount: Int): Long =
        staggerMs(durationMs, targetCount)

    /**
     * Compute the delay for a word in a line. Negative offsets and indices are
     * treated as zero; arithmetic is saturated and capped to keep malformed
     * host callbacks fail-open.
     */
    fun startDelayMs(
        wordIndex: Int,
        staggerMs: Long,
        startOffsetMs: Long = 0L,
    ): Long {
        val index = wordIndex.coerceAtLeast(0).toLong()
        val stagger = staggerMs.coerceIn(0L, MAX_STAGGER_MS)
        val offset = startOffsetMs.coerceAtLeast(0L)
        val indexed = if (stagger == 0L || index == 0L) {
            0L
        } else if (index > Long.MAX_VALUE / stagger) {
            Long.MAX_VALUE
        } else {
            index * stagger
        }
        val sum = if (Long.MAX_VALUE - offset < indexed) {
            Long.MAX_VALUE
        } else {
            offset + indexed
        }
        return sum.coerceAtMost(MAX_START_DELAY_MS)
    }

    /** Alias useful when the call site describes the value as a delay. */
    fun delayFor(
        wordIndex: Int,
        staggerMs: Long,
        startOffsetMs: Long = 0L,
    ): Long = startDelayMs(wordIndex, staggerMs, startOffsetMs)
}

/**
 * Default script matcher for lyric text. It deliberately lives in this
 * host-independent file: callers can pass [containsCjkKaraokeScript] (the
 * existing host adapter predicate) or any other matcher instead.
 */
fun defaultCjkLyricScriptPredicate(text: CharSequence): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        // Numeric ranges avoid newer UnicodeBlock fields, which keeps this
        // helper safe on the module's minSdk 26 devices as well.
        if (isCjkLyricCodePoint(codePoint)) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

private fun isCjkLyricCodePoint(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF || // CJK Unified Ideographs Extension A
        codePoint in 0x4E00..0x9FFF || // Unified Ideographs
        codePoint in 0x20000..0x323AF || // supplementary CJK extensions
        codePoint in 0x3040..0x309F || // Hiragana
        codePoint in 0x30A0..0x30FF || // Katakana
        codePoint in 0x31F0..0x31FF || // Katakana phonetic extensions
        codePoint in 0xAC00..0xD7AF || // Hangul syllables
        codePoint in 0x1100..0x11FF || // Hangul Jamo
        codePoint in 0x3130..0x318F // Hangul compatibility Jamo

/**
 * Reusable, fail-open animator for lyric lines.
 *
 * A line is normally a ViewGroup whose word views are TextViews. TextViews
 * matching [scriptPredicate] are preferred; when no matching TextView exists,
 * leaf views with a matching contentDescription are accepted as a safe generic
 * fallback. For custom host adapters, [animateViews] can supply the exact
 * target views directly.
 *
 * The controller owns no host references. It only keeps weak root keys and
 * restores every captured alpha/translation/scale value when cancelled,
 * cleared, detached, or recycled. All Android calls are guarded so an
 * unexpected host view cannot break the original lyric path.
 */
class CjkLyricValueAnimator(
    private val startAlphaFraction: Float = DEFAULT_START_ALPHA_FRACTION,
    private val startTranslationY: Float = DEFAULT_START_TRANSLATION_Y_PX,
    private val startScaleFraction: Float = DEFAULT_START_SCALE_FRACTION,
) {
    private val lock = Any()
    private val sessions = WeakHashMap<View, LineSession>()

    /**
     * Animate matching TextView words in depth-first child order.
     *
     * [wordIndex], [wordCount], and [startOffsetMs] are optional host timing
     * metadata. They let an adapter map a callback's word id into the same
     * stagger timeline; if omitted, the current line's child order is used.
     * Returns the number of views for which an animator was started.
     */
    fun animateLine(
        root: View?,
        durationMs: Long = CjkLyricAnimationPolicy.DEFAULT_DURATION_MS,
        scriptPredicate: (CharSequence) -> Boolean = ::defaultCjkLyricScriptPredicate,
        wordIndex: Int = 0,
        wordCount: Int? = null,
        startOffsetMs: Long = 0L,
        staggerMs: Long? = null,
    ): Int {
        if (root == null) return 0
        return runCatching {
            val targets = collectTargets(root, scriptPredicate)
            animateViews(
                root = root,
                targets = targets,
                durationMs = durationMs,
                wordIndex = wordIndex,
                wordCount = wordCount,
                startOffsetMs = startOffsetMs,
                staggerMs = staggerMs,
            )
        }.getOrElse { 0 }
    }

    /**
     * Animate explicitly selected views. This is useful when a host exposes
     * word views that are not TextViews or when its callback already resolves
     * the exact word order.
     */
    fun animateViews(
        root: View?,
        targets: Iterable<View?>,
        durationMs: Long = CjkLyricAnimationPolicy.DEFAULT_DURATION_MS,
        wordIndex: Int = 0,
        wordCount: Int? = null,
        startOffsetMs: Long = 0L,
        staggerMs: Long? = null,
    ): Int {
        if (root == null) return 0
        return runCatching {
            clear(root)
            val seenTargets = IdentityHashMap<View, Boolean>()
            val safeTargets = targets.mapNotNull { view ->
                view?.takeIf(::isAnimatableTarget)?.takeIf { seenTargets.put(it, true) == null }
            }
            if (safeTargets.isEmpty()) return@runCatching 0

            val duration = CjkLyricAnimationPolicy.clampDurationMs(durationMs)
            val count = (wordCount ?: safeTargets.size).coerceAtLeast(1)
            val lineStagger = CjkLyricAnimationPolicy.staggerMs(duration, count)
            val explicitStagger = staggerMs?.coerceIn(0L, CjkLyricAnimationPolicy.MAX_STAGGER_MS)
            val effectiveStagger = explicitStagger ?: lineStagger
            val session = LineSession(WeakReference(root))
            synchronized(lock) { sessions[root] = session }
            installDetachListener(root, session)

            var started = 0
            safeTargets.forEachIndexed { childIndex, target ->
                val state = OriginalViewState.capture(target) ?: return@forEachIndexed
                session.states[target] = state
                installTargetDetachListener(root, session, target)
                val effectiveIndex = safeAdd(wordIndex.coerceAtLeast(0), childIndex)
                val delay = CjkLyricAnimationPolicy.startDelayMs(
                    wordIndex = effectiveIndex,
                    staggerMs = effectiveStagger,
                    startOffsetMs = startOffsetMs,
                )
                if (startAnimator(root, session, target, state, duration, delay)) {
                    started += 1
                } else {
                    session.states.remove(target)
                }
            }

            if (started == 0) {
                finishSession(root, session)
            }
            started
        }.getOrElse { error ->
            // If collecting or starting one target unexpectedly fails, restore
            // anything that was already touched and leave the host untouched.
            runCatching { clear(root) }
            0
        }
    }

    /** Cancel the line and restore all captured properties. */
    fun clear(root: View?) {
        if (root == null) return
        val session = synchronized(lock) { sessions.remove(root) } ?: return
        restoreAndCancel(root, session)
    }

    /** Alias for recycler/lifecycle callbacks. */
    fun onViewRecycled(root: View?) = clear(root)

    /** Explicit cancellation spelling for callers that do not use lifecycle terms. */
    fun cancel(root: View?) = clear(root)

    /**
     * Restore a line when its root or one of its word views is no longer
     * visible/attached. This is a cheap hook for adapters that receive a
     * visibility or recycler callback instead of a View detach callback.
     */
    fun clearInactive(root: View?) {
        if (root == null) return
        val session = synchronized(lock) { sessions[root] } ?: return
        val inactive = runCatching {
            !root.isShown || session.states.keys.any { !it.isShown }
        }.getOrDefault(true)
        if (inactive) clear(root)
    }

    /** Clear every currently live root tracked by this controller. */
    fun clearAll() {
        val roots = synchronized(lock) { sessions.keys.toList() }
        roots.forEach(::clear)
    }

    /** Cancel every currently live line. */
    fun cancelAll() = clearAll()

    /** Number of active line sessions; useful for diagnostics and tests. */
    fun activeLineCount(): Int = synchronized(lock) { sessions.size }

    private fun collectTargets(
        root: View,
        scriptPredicate: (CharSequence) -> Boolean,
    ): List<View> {
        val textTargets = ArrayList<View>()
        val descriptionTargets = ArrayList<View>()

        fun visit(view: View) {
            if (!isAnimatableTarget(view)) return
            if (view is TextView) {
                val text = runCatching { view.text }.getOrNull()
                if (text != null && runCatching { scriptPredicate(text) }.getOrDefault(false)) {
                    textTargets += view
                }
                // A TextView normally has no children; visiting them anyway
                // keeps the traversal safe for custom compound widgets.
            } else {
                val description = runCatching { view.contentDescription }.getOrNull()
                if (
                    description != null &&
                    runCatching { scriptPredicate(description) }.getOrDefault(false)
                ) {
                    descriptionTargets += view
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    runCatching { view.getChildAt(index) }.getOrNull()?.let(::visit)
                }
            }
        }

        visit(root)
        return if (textTargets.isNotEmpty()) textTargets else descriptionTargets
    }

    private fun startAnimator(
        root: View,
        session: LineSession,
        target: View,
        state: OriginalViewState,
        duration: Long,
        startDelay: Long,
    ): Boolean {
        var animator: ValueAnimator? = null
        return runCatching {
            val initial = state.initial(startAlphaFraction, startTranslationY, startScaleFraction)
            apply(target, initial, 0f)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                this.startDelay = startDelay
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { valueAnimator ->
                    if (!isCurrent(root, session) || session.states[target] !== state) return@addUpdateListener
                    val fraction = (valueAnimator.animatedValue as? Float)?.coerceIn(0f, 1f)
                        ?: return@addUpdateListener
                    runCatching { apply(target, initial, state, fraction) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isCurrent(root, session)) return
                        runCatching { state.restore(target) }
                        session.animators.remove(target)
                        if (session.animators.isEmpty()) {
                            finishSession(root, session)
                        }
                    }
                })
            }
            session.animators[target] = checkNotNull(animator)
            checkNotNull(animator).start()
            true
        }.getOrElse {
            session.animators.remove(target)
            runCatching { state.restore(target) }
            false
        }
    }

    private fun installDetachListener(root: View, session: LineSession) {
        runCatching {
            val controller = WeakReference(this)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    if (view === session.root.get()) controller.get()?.clear(view)
                }
            }
            session.detachListener = listener
            root.addOnAttachStateChangeListener(listener)
        }
    }

    private fun installTargetDetachListener(root: View, session: LineSession, target: View) {
        runCatching {
            val controller = WeakReference(this)
            val rootReference = WeakReference(root)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    if (view === target && session.active) {
                        controller.get()?.clear(rootReference.get())
                    }
                }
            }
            session.targetDetachListeners[target] = listener
            target.addOnAttachStateChangeListener(listener)
        }
    }

    private fun finishSession(root: View, session: LineSession) {
        val removed = synchronized(lock) {
            if (sessions[root] === session) {
                sessions.remove(root)
                true
            } else {
                false
            }
        }
        if (!removed) return
        session.active = false
        runCatching {
            session.detachListener?.let(root::removeOnAttachStateChangeListener)
        }
        session.detachListener = null
        removeTargetDetachListeners(session)
        session.animators.clear()
        session.states.clear()
    }

    private fun restoreAndCancel(root: View, session: LineSession) {
        session.active = false
        runCatching {
            session.detachListener?.let(root::removeOnAttachStateChangeListener)
        }
        session.detachListener = null
        removeTargetDetachListeners(session)
        session.animators.values.toList().forEach { animator ->
            runCatching { animator.cancel() }
        }
        session.animators.clear()
        session.states.forEach { (view, state) ->
            runCatching { state.restore(view) }
        }
        session.states.clear()
    }

    private fun removeTargetDetachListeners(session: LineSession) {
        session.targetDetachListeners.forEach { (view, listener) ->
            runCatching { view.removeOnAttachStateChangeListener(listener) }
        }
        session.targetDetachListeners.clear()
    }

    private fun isCurrent(root: View, session: LineSession): Boolean =
        session.active && synchronized(lock) { sessions[root] === session }

    private fun isAnimatableTarget(view: View): Boolean = runCatching {
        view.visibility == View.VISIBLE
    }.getOrDefault(false)

    private fun apply(
        view: View,
        initial: OriginalViewState,
        fraction: Float,
    ) {
        apply(view, initial, initial, fraction)
    }

    private fun apply(
        view: View,
        initial: OriginalViewState,
        state: OriginalViewState,
        fraction: Float,
    ) {
        view.alpha = lerp(initial.alpha, state.alpha, fraction)
        view.translationX = lerp(initial.translationX, state.translationX, fraction)
        view.translationY = lerp(initial.translationY, state.translationY, fraction)
        view.scaleX = lerp(initial.scaleX, state.scaleX, fraction)
        view.scaleY = lerp(initial.scaleY, state.scaleY, fraction)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun safeAdd(first: Int, second: Int): Int =
        if (Int.MAX_VALUE - first < second) Int.MAX_VALUE else first + second

    private data class OriginalViewState(
        val alpha: Float,
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
    ) {
        fun initial(alphaFraction: Float, translationOffset: Float, scaleFraction: Float): OriginalViewState {
            val safeAlphaFraction = alphaFraction.coerceIn(0f, 1f)
            val safeScaleFraction = scaleFraction.coerceIn(0f, 1f)
            return copy(
                alpha = (alpha * safeAlphaFraction).coerceIn(0f, 1f),
                translationY = translationY + translationOffset,
                scaleX = scaleX * safeScaleFraction,
                scaleY = scaleY * safeScaleFraction,
            )
        }

        fun restore(view: View) {
            view.alpha = alpha
            view.translationX = translationX
            view.translationY = translationY
            view.scaleX = scaleX
            view.scaleY = scaleY
        }

        companion object {
            fun capture(view: View): OriginalViewState? = runCatching {
                OriginalViewState(
                    alpha = view.alpha,
                    translationX = view.translationX,
                    translationY = view.translationY,
                    scaleX = view.scaleX,
                    scaleY = view.scaleY,
                )
            }.getOrNull()
        }
    }

    private class LineSession(val root: WeakReference<View>) {
        var active: Boolean = true
        var detachListener: View.OnAttachStateChangeListener? = null
        val states = IdentityHashMap<View, OriginalViewState>()
        val animators = IdentityHashMap<View, ValueAnimator>()
        val targetDetachListeners = IdentityHashMap<View, View.OnAttachStateChangeListener>()
    }

    private companion object {
        const val DEFAULT_START_ALPHA_FRACTION = 0.58f
        const val DEFAULT_START_TRANSLATION_Y_PX = 4f
        const val DEFAULT_START_SCALE_FRACTION = 0.985f
    }
}
