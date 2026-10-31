package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Map as JavaMap
import java.util.WeakHashMap

/**
 * Adapts native glow for profile-approved CJK spans and split Latin words.
 * Apple retains ownership of TTML grouping and per-fragment sweep timing.
 */
internal class AppleMusicCjkKaraokeAnimationTarget(
    private val symbols: TargetSymbolResolver,
    private val foregroundTextField: String = "U",
    private val maxSpanGlowCharacters: Int = 1,
) : CjkKaraokeAnimationTarget {
    private val a0Depth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }
    private val a0EntryStack: ThreadLocal<MutableList<CjkEntryState?>> =
        ThreadLocal.withInitial { mutableListOf() }
    private val fieldCache = Collections.synchronizedMap(
        WeakHashMap<Class<*>, kotlin.collections.Map<String, Field>>(),
    )
    private val glowAnimators = Collections.synchronizedMap(
        WeakHashMap<Animator, WeakReference<Any>>(),
    )
    /** z$f is called once per frame; cache its view lookup after the first frame. */
    private val inspectedGlowListeners = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<Any>?>(),
    )
    private val activeGlowByView = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<Animator>>(),
    )
    private val trackedGlowViews = Collections.synchronizedMap(
        WeakHashMap<Any, CjkGlowBaseline>(),
    )
    private val timingRewriteLogCount = AtomicInteger()
    private val rewriteLogCount = AtomicInteger()
    private var glowEndHookInstalled = false
    private var glowViewEndHookInstalled = false
    private var glowTimingHookInstalled = false
    @Volatile
    private var hooksReady = false

    private var splitWordGlowInstalled = false
    private val splitWordGlowPivots = ThreadLocal<kotlin.collections.Map<View, Float>>()

    /** Keeps TTML sweeps intact and gives eligible split Latin words one visual glow envelope. */
    fun installSplitWordGlow(enabled: Boolean): TargetCapabilityInstall {
        if (!enabled) return TargetCapabilityInstall.Unsupported("Split-word glow has no exact profile opt-in")
        if (splitWordGlowInstalled) return TargetCapabilityInstall.Active("Split-word glow already installed")
        val resolution = symbols.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod)
        val transition = resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("Split-word glow: ${resolution.summary}")
        return runCatching {
            glowTimingHookInstalled = glowTimingHookInstalled || installGlowTimingHook(transition)
            check(glowTimingHookInstalled) { "Split-word glow timing hook was rejected" }
            check(ModernXposedRuntime.hookMethod(
                TimeAnimator::class.java.getDeclaredMethod("setTimeListener", TimeAnimator.TimeListener::class.java),
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // This listener spreads fragments apart; one word has no internal spreading.
                        if (splitWordGlowPivots.get() != null) param.result = null
                    }
                },
            )) { "Split-word glow spreading hook was rejected" }
            check(ModernXposedRuntime.hookMethod(transition, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras[SPLIT_WORD_GLOW_PREVIOUS] = splitWordGlowPivots.get()
                    splitWordGlowPivots.remove()
                    runCatching {
                        val original = splitWordGlowOverride(param) ?: return
                        param.extras[SPLIT_WORD_GLOW_LENGTH] = original
                        splitWordGlowPivots.set(original.third)
                        // Only this transition sees an admitted length. Text and duration stay native.
                        cachedFields(original.first.javaClass).getValue("g").setInt(original.first, 7)
                    }.onFailure { ModernXposedRuntime.log("Split-word glow gate kept native behavior", it) }
                }

                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val original = param.extras.remove(SPLIT_WORD_GLOW_LENGTH) as? Triple<*, *, *> ?: return
                        val entry = original.first ?: return
                        val length = original.second as? Int ?: return
                        // Also restore when the native transition throws or returns early.
                        runCatching { cachedFields(entry.javaClass).getValue("g").setInt(entry, length) }
                            .onFailure { ModernXposedRuntime.log("Split-word glow length restore failed", it) }
                    } finally {
                        val previous = param.extras.remove(SPLIT_WORD_GLOW_PREVIOUS)
                            as? kotlin.collections.Map<View, Float>
                        if (previous == null) splitWordGlowPivots.remove() else splitWordGlowPivots.set(previous)
                    }
                }
            })) { "Split-word glow hook was rejected" }
            splitWordGlowInstalled = true
            TargetCapabilityInstall.Active("Installed split-word Latin glow eligibility")
        }.getOrElse { error ->
            ModernXposedRuntime.log("Split-word glow hook unavailable", error)
            TargetCapabilityInstall.Degraded("Split-word glow: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun splitWordGlowOverride(
        param: ModernMethodHook.MethodHookParam,
    ): Triple<Any, Int, kotlin.collections.Map<View, Float>>? {
        if (param.args.getOrNull(4) != false) return null
        val holder = param.args.getOrNull(0) ?: return null
        val wordId = param.args.getOrNull(2) as? Int ?: return null
        val words = readNamedField(holder, "G") as? kotlin.collections.Map<*, *> ?: return null
        val entry = words[wordId] ?: return null
        val length = readNamedField(entry, "g") as? Int ?: return null
        val duration = readNamedField(entry, "f") as? Int ?: return null
        if (length <= 7 || duration < 1_000) return null
        val groupId = readNamedField(entry, "l") as? Int ?: return null
        val lineId = readNamedField(entry, "a") as? Int ?: return null
        val group = words.values.filterNotNull().filter {
            readNamedField(it, "l") == groupId && readNamedField(it, "a") == lineId
        }
        if (group.size < 2) return null
        val spans = group.map {
            val text = (readNamedField(it, "c") as? CharSequence)?.toString()?.trim() ?: return null
            val spanDuration = readNamedField(it, "e") as? Int ?: return null
            if (text.isEmpty() || text.any(Char::isWhitespace) || spanDuration <= 0) return null
            text to spanDuration
        }
        if (spans.sumOf { it.first.length } != length ||
            spans.sumOf { it.second.toLong() } != duration.toLong()) return null
        val text = spans.joinToString("") { it.first }
        if (!text.any(Char::isLetter) || text.any {
                it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
            }) return null
        if (spans.none { (span, spanDuration) -> spanDuration >= 1_000 && span.length in 2..7 }) return null
        val views = group.flatMap(::foregroundEntryViews).filterIsInstance<View>().distinct()
        if (views.isEmpty() || views.any { it.width <= 0 || it.height <= 0 }) return null
        val parent = generateSequence(views.first().parent) { it.parent }
            .filterIsInstance<ViewGroup>().firstOrNull { ancestor ->
                views.all { view -> generateSequence(view.parent) { it.parent }.any { it === ancestor } }
            } ?: return null
        val bounds = views.associateWith { view ->
            Rect(0, 0, view.width, view.height).also { parent.offsetDescendantRectToMyCoords(view, it) }
        }
        // A wrapped word cannot share a single visual origin across different rows.
        if (bounds.values.maxOf { it.top } - bounds.values.minOf { it.top } > views.maxOf { it.height } / 2) return null
        val center = (bounds.values.minOf { it.left } + bounds.values.maxOf { it.right }) / 2f
        return Triple(entry, length, bounds.mapValues { (_, rect) -> center - rect.left })
    }

    override fun install(): TargetCapabilityInstall {
        hooksReady = false
        glowEndHookInstalled = false
        glowViewEndHookInstalled = false
        val a0Resolution = symbols.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod)
        val helperResolution = symbols.resolve(AppleMusicSymbols.CjkUnicodeBlockPredicateMethod)
        val a0 = a0Resolution.valueOrNull()
        val helper = helperResolution.valueOrNull()
        if (a0 == null || helper == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(a0Resolution, helperResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
        }

        val failures = mutableListOf<String>()
        val a0Installed = try {
            ModernXposedRuntime.hookMethod(a0, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    enterA0Scope(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // The host callback runs even when z.a0 throws; always
                    // release this thread's scope so a later classifier call cannot
                    // inherit a stale override.
                    completeA0Scope(param)
                    leaveA0Scope()
                }
            }).also { installed ->
                if (!installed) failures += "z.a0 hook was rejected"
            }
        } catch (error: Throwable) {
            failures += "z.a0 hook failed: ${error.cjkShortMessage()}"
            ModernXposedRuntime.log("CJK karaoke z.a0 hook failed", error)
            false
        }

        val helperInstalled = try {
            ModernXposedRuntime.hookMethod(helper, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        rewriteCjkClassifierResult(param)
                    } catch (error: Throwable) {
                        // A malformed host call must never break its original
                        // helper result.  This is deliberately fail-open.
                        ModernXposedRuntime.log("CJK karaoke UnicodeBlock helper failed open", error)
                    }
                }
            }).also { installed ->
                if (!installed) failures += "I0\$a.a hook was rejected"
            }
        } catch (error: Throwable) {
            failures += "I0\$a.a hook failed: ${error.cjkShortMessage()}"
            ModernXposedRuntime.log("CJK karaoke I0\$a.a hook failed", error)
            false
        }

        // Observe only the host's own glow child callback.  Do not add
        // listeners to every Animator in e.p: that changes Apple's ordering
        // and was shown to disturb all lyric animations on device.
        glowEndHookInstalled = installGlowAnimatorEndHook(a0)
        glowViewEndHookInstalled = installGlowViewEndHook(a0)
        glowTimingHookInstalled = glowTimingHookInstalled || installGlowTimingHook(a0)

        val hooksInstalled = a0Installed && helperInstalled && glowEndHookInstalled &&
            glowViewEndHookInstalled && glowTimingHookInstalled
        hooksReady = hooksInstalled
        if (!hooksInstalled) {
            // Hooks cannot be removed reliably through the modern runtime.  Keep
            // any partially registered callbacks inert until every seam is ready.
            return TargetCapabilityInstall.Degraded(
                failures.joinToString("; ").ifBlank { "CJK karaoke animation hooks were not installed" },
            )
        }

        val glowMessage = "Installed unmerged-CJK glow end cleanup " +
            "(max span characters=${maxSpanGlowCharacters.coerceIn(1, 3)}): ${a0.toGenericString()}"
        return TargetCapabilityInstall.Active(glowMessage)
    }

    /**
     * z$f only implements AnimatorUpdateListener, so Apple has no end/cancel
     * callback for the ValueAnimator that writes scale and shadow. Hook that
     * exact host listener and attach a listener to its own animator. No e.p
     * membership or animator cancellation is changed here.
     */
    private fun installGlowAnimatorEndHook(a0: Executable): Boolean = runCatching {
        val owner = a0.declaringClass
        val updateType = Class.forName(
            "${owner.name}\$f",
            false,
            owner.classLoader,
        )
        val method = updateType.declaredMethods
            .filter { candidate ->
                candidate.name == "onAnimationUpdate" &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0].name == "android.animation.ValueAnimator"
            }
            .singleOrNull()
            ?: return@runCatching false
        ModernXposedRuntime.hookMethod(
            method,
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val animator = param.args.getOrNull(0) as? Animator
                    attachGlowEndListener(param.thisObject, animator)
                }
            },
        )
    }.onFailure { error ->
        ModernXposedRuntime.log("CJK karaoke z\$f end hook unavailable", error)
    }.getOrDefault(false)

    /**
     * The host's special glow uses a rise child followed by a return child.
     * For a single CJK binding the return child is otherwise delayed by
     * 2*duration, leaving the raised/glowing frame held for a long envelope.
     * Shorten only that exact z$f child to begin after the rise duration.
     */
    private fun installGlowTimingHook(a0: Executable): Boolean = runCatching {
        val returnType = Class.forName("${a0.declaringClass.name}\$f", false, a0.declaringClass.classLoader)
        val glowFunctionType = runCatching { returnType.getDeclaredField("d").type }.getOrNull()
        val addUpdateListener = ValueAnimator::class.java.getDeclaredMethod(
            "addUpdateListener",
            ValueAnimator.AnimatorUpdateListener::class.java,
        )
        ModernXposedRuntime.hookMethod(
            addUpdateListener,
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val animator = param.thisObject as? ValueAnimator ?: return
                    val listener = param.args.getOrNull(0) ?: return
                    val pivots = splitWordGlowPivots.get()
                    if (pivots != null) {
                        val returning = returnType.isInstance(listener)
                        val glowFunction = if (returning) null else readNamedField(listener, "a")
                        val view = when {
                            returning -> readNamedField(listener, "c") as? View
                            glowFunctionType?.isInstance(glowFunction) == true ->
                                readNamedField(checkNotNull(glowFunction), "a") as? View
                            else -> null
                        }
                        val pivot = view?.let(pivots::get)
                        if (view != null && pivot != null) {
                            // A common origin and envelope make the native fragments one visual word.
                            view.pivotX = pivot
                            animator.startDelay = if (returning) animator.duration else 0L
                            return
                        }
                    }
                    if (!isEligibleWordScope() || a0Stack().lastOrNull()?.singleWord != true) return
                    if (listener.javaClass.name != "${a0.declaringClass.name}\$f") return
                    runCatching {
                        val duration = animator.duration
                        val originalDelay = animator.startDelay
                        if (duration > 0L && originalDelay > duration) {
                            animator.startDelay = duration
                            if (timingRewriteLogCount.getAndIncrement() < MAX_TIMING_REWRITE_LOGS) {
                                ModernXposedRuntime.log(
                                    "CJK karaoke glow timing: z\$f delay " +
                                        "$originalDelay -> $duration (duration=$duration)",
                                )
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("CJK karaoke glow timing failed open", error)
                    }
                }
            },
        )
    }.onFailure { error ->
        ModernXposedRuntime.log("CJK karaoke glow timing hook unavailable", error)
    }.getOrDefault(false)

    private fun attachGlowEndListener(updateListener: Any?, animator: Animator?) {
        if (!hooksReady || updateListener == null || animator == null) return
        val alreadyTracked = synchronized(glowAnimators) { glowAnimators.containsKey(animator) }
        if (alreadyTracked) return
        val view = inspectedGlowView(updateListener) ?: return
        if (!isTrackedCjkGlowView(view)) return
        val shouldAttach = synchronized(glowAnimators) {
            if (glowAnimators.containsKey(animator)) {
                false
            } else {
                glowAnimators[animator] = WeakReference(view)
                synchronized(activeGlowByView) {
                    activeGlowByView[view] = WeakReference(animator)
                }
                true
            }
        }
        if (!shouldAttach) return

        val animatorRef = WeakReference(animator)
        val viewRef = WeakReference(view)
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cleaned = false

            private fun cleanup() {
                if (cleaned) return
                cleaned = true
                cleanupGlowAnimator(animatorRef.get(), viewRef.get())
            }

            override fun onAnimationCancel(animation: Animator) = cleanup()

            override fun onAnimationEnd(animation: Animator) = cleanup()
        })
    }

    private fun inspectedGlowView(listener: Any): Any? = synchronized(inspectedGlowListeners) {
        if (inspectedGlowListeners.containsKey(listener)) {
            return@synchronized inspectedGlowListeners[listener]?.get()
        }
        val view = readNamedField(listener, "c")
        inspectedGlowListeners[listener] = view?.let(::WeakReference)
        view
    }

    /**
     * z$g is the host's own end listener for the ValueAnimator that runs the
     * glow's return-to-normal phase.  Hooking that exact seam is more reliable
     * than waiting for a later outer AnimatorSet callback: CJK may be rendered
     * through a binding whose text is not a single code point, while the
     * recorded View identity still identifies the glow target precisely.
     */
    private fun installGlowViewEndHook(a0: Executable): Boolean = runCatching {
        val owner = a0.declaringClass
        val endType = Class.forName(
            "${owner.name}\$g",
            false,
            owner.classLoader,
        )
        val method = endType.declaredMethods
            .filter { candidate ->
                candidate.name == "onAnimationEnd" &&
                    candidate.parameterTypes.size == 1 &&
                    Animator::class.java.isAssignableFrom(candidate.parameterTypes[0])
            }
            .singleOrNull()
            ?: return@runCatching false
        ModernXposedRuntime.hookMethod(
            method,
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val animator = param.args.getOrNull(0) as? Animator
                    val fallbackView = param.thisObject
                        ?.let { readNamedField(it, "b") }
                    cleanupGlowAnimator(animator, fallbackView)
                }
            },
        )
    }.onFailure { error ->
        ModernXposedRuntime.log("CJK karaoke z\$g view end hook unavailable", error)
    }.getOrDefault(false)

    private fun cleanupGlowAnimator(animator: Animator?, fallbackView: Any?) {
        if (animator == null) return
        val view = synchronized(glowAnimators) {
            glowAnimators.remove(animator)?.get()
        } ?: fallbackView ?: return
        val activeForView = synchronized(activeGlowByView) {
            activeGlowByView[view]?.get()
        }
        if (activeForView != null && activeForView !== animator) return
        synchronized(trackedGlowViews) {
            trackedGlowViews[view]?.let { baseline ->
                resetCjkGlowView(view, baseline)
            }
        }
        synchronized(activeGlowByView) { activeGlowByView.remove(view) }
        synchronized(trackedGlowViews) { trackedGlowViews.remove(view) }
    }

    private fun isTrackedCjkGlowView(view: Any): Boolean =
        synchronized(trackedGlowViews) { trackedGlowViews.containsKey(view) }

    private fun resetCjkGlowView(view: Any, baseline: CjkGlowBaseline) {
        // translationY belongs to Apple's lyric layout/rebind state.  The glow
        // listener never owns it, so restoring a captured value here races a
        // later host layout pass and can make a word jump to a lower baseline.
        invokeMethod(view, "setScaleX", baseline.scaleX)
        invokeMethod(view, "setScaleY", baseline.scaleY)
        invokeNoArg(view, "resetPivot")
        invokeMethod(view, "setShadowLayer", 0f, 0f, 0f, 0)
        invokeNoArg(view, "invalidate")
    }

    private fun captureCjkGlowBaseline(view: Any): CjkGlowBaseline? = runCatching {
        CjkGlowBaseline(
            scaleX = (invokeNoArg(view, "getScaleX") as? Number)?.toFloat() ?: return@runCatching null,
            scaleY = (invokeNoArg(view, "getScaleY") as? Number)?.toFloat() ?: return@runCatching null,
        )
    }.getOrNull()

    private fun foregroundEntryViews(entry: Any): List<Any> {
        // z.m0(e, foreground=false) returns the primary e.i binding when it
        // exists and only falls back to e.k otherwise.  Mirror that choice so
        // a recycled split binding cannot be mistaken for this glow target.
        val primary = readNamedField(entry, "i")
        val bindings: List<Any?> = if (primary != null) {
            listOf(primary)
        } else {
            (readNamedField(entry, "k") as? Collection<*>)?.toList().orEmpty()
        }
        val views = mutableListOf<Any>()
        bindings.forEach { binding ->
            val view = binding?.let { readNamedField(it, foregroundTextField) } ?: return@forEach
            if (views.none { it === view }) views += view
        }
        return views
    }

    private fun rewriteCjkClassifierResult(param: ModernMethodHook.MethodHookParam) {
        val text = param.args.getOrNull(0) as? CharSequence ?: return
        val languageSet = param.args.getOrNull(1) as? Set<*> ?: return
        if (isEligibleWordScope()) {
            rewriteCjkAnimationResult(param, text, languageSet)
        }
    }

    private fun rewriteCjkAnimationResult(
        param: ModernMethodHook.MethodHookParam,
        text: CharSequence,
        languageSet: Set<*>,
    ) {
        val normalized = text.toString().trim()
        val cjkText = cjkKaraokeTextWithoutPunctuation(normalized)
        if (!containsCjkKaraokeScript(cjkText)) return
        val hasAttachedPunctuation = cjkText.length != normalized.length &&
            cjkText.all { containsCjkKaraokeScript(it.toString()) }
        when {
            isK0Set(languageSet) && param.result == true -> {
                // CJK is normally classified into k0, which blocks the
                // rush branch.  Make that one result look like a default
                // script only while a0 is running.
                param.result = false
                logRewrite(text, "k0 true -> false")
            }
            isJ0Set(languageSet) && (containsHangul(cjkText) || hasAttachedPunctuation) && param.result != true -> {
                // i0 receives the j0 hit as its split/rush eligibility bit.
                // Admit Hangul and punctuation-bearing CJK bodies only inside
                // the verified a0 scope; native text, timings and g0 stay untouched.
                param.result = true
                logRewrite(text, "j0 false -> true")
            }
            else -> return
        }
    }

    private fun logRewrite(text: CharSequence, change: String) {
        if (rewriteLogCount.getAndIncrement() < MAX_REWRITE_LOGS) {
            ModernXposedRuntime.log(
                "CJK karaoke classifier: I0\$a.a(${text.length} chars, $change)",
            )
        }
    }


    private fun isK0Set(languageSet: Set<*>): Boolean = runCatching {
        // z.k0 always contains HANGUL_SYLLABLES.  This marker distinguishes
        // it from the host's j0/l0 sets without touching either set globally.
        languageSet.contains(Character.UnicodeBlock.HANGUL_SYLLABLES)
    }.getOrElse { error ->
        ModernXposedRuntime.log("CJK karaoke k0 marker check failed open", error)
        false
    }

    private fun isJ0Set(languageSet: Set<*>): Boolean = runCatching {
        languageSet.contains(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) &&
            languageSet.contains(Character.UnicodeBlock.HIRAGANA) &&
            languageSet.contains(Character.UnicodeBlock.KATAKANA) &&
            !languageSet.contains(Character.UnicodeBlock.HANGUL_SYLLABLES) &&
            !languageSet.contains(Character.UnicodeBlock.THAI)
    }.getOrElse { error ->
        ModernXposedRuntime.log("CJK karaoke j0 marker check failed open", error)
        false
    }

    private fun enterA0Scope(param: ModernMethodHook.MethodHookParam) {
        if (!hooksReady) return
        runCatching {
            a0Depth.set((a0Depth.get() ?: 0) + 1)
            val candidate = readGlowGateEntry(param)
            a0Stack().add(candidate)
        }
            .onFailure { error -> ModernXposedRuntime.log("CJK karaoke a0 depth enter failed open", error) }
    }

    /**
     * Commit a baseline only after the host has added a new special-path
     * Animator to the entry.  An eligible candidate can still be rejected by
     * Apple's duration/length gates, in which case its recycled View must not
     * remain globally classified as a CJK glow target.
     */
    private fun completeA0Scope(param: ModernMethodHook.MethodHookParam) {
        if (!hooksReady || param.throwable != null) return
        runCatching {
            val state = a0Stack().lastOrNull() ?: return@runCatching
            val after = specialAnimatorSnapshot(state.entry)
            if (!hasNewCjkGlowAnimator(state.specialAnimatorsBefore, after)) return@runCatching
            synchronized(trackedGlowViews) {
                state.views.forEach { view ->
                    if (!trackedGlowViews.containsKey(view)) {
                        captureCjkGlowBaseline(view)?.let { baseline ->
                            trackedGlowViews[view] = baseline
                        }
                    }
                }
            }
        }.onFailure { error ->
            ModernXposedRuntime.log("CJK karaoke glow baseline commit failed open", error)
        }
    }

    private fun leaveA0Scope() {
        runCatching {
            val depth = a0Depth.get() ?: 0
            if (depth <= 1) {
                a0Depth.remove()
            } else {
                a0Depth.set(depth - 1)
            }
            val stack = a0Stack()
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) a0EntryStack.remove()
        }.onFailure { error -> ModernXposedRuntime.log("CJK karaoke a0 depth cleanup failed open", error) }
    }

    private fun isEligibleWordScope(): Boolean = runCatching {
        hooksReady && (a0Depth.get() ?: 0) > 0 && a0Stack().lastOrNull() != null
    }
        .getOrElse { error ->
            ModernXposedRuntime.log("CJK karaoke glow gate read failed open", error)
            false
        }

    private fun a0Stack(): MutableList<CjkEntryState?> =
        a0EntryStack.get() ?: mutableListOf<CjkEntryState?>().also(a0EntryStack::set)

    /** Reads grouping metadata without changing Apple's native timings or lengths. */
    private fun readGlowGateEntry(param: ModernMethodHook.MethodHookParam): CjkEntryState? =
        runCatching {
            val holder = param.args.getOrNull(0) ?: return@runCatching null
            val wordId = (param.args.getOrNull(2) as? Number)?.toInt()
                ?: return@runCatching null
            val nativeDuration = (param.args.getOrNull(3) as? Number)?.toInt()
                ?: return@runCatching null
            val background = param.args.getOrNull(4) as? Boolean
                ?: return@runCatching null
            val mapName = if (background) "H" else "G"
            val map = cachedFields(holder.javaClass)[mapName]
                ?.let { field -> readField(field, holder) as? JavaMap<*, *> }
                ?: return@runCatching null
            val entry = map.get(Integer.valueOf(wordId)) ?: map.get(wordId)
                ?: return@runCatching null
            val text = cachedFields(entry.javaClass)["c"]
                ?.let { field -> readField(field, entry) as? CharSequence }
                ?: return@runCatching null
            val cumulativeDuration = cachedFields(entry.javaClass)["f"]
                ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
                ?: return@runCatching null
            val cumulativeLength = cachedFields(entry.javaClass)["g"]
                ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
                ?: return@runCatching null
            val splitValue = cachedFields(entry.javaClass)["k"]
                ?.let { field -> readField(field, entry) }
            val timing = CjkKaraokeWordTiming(
                text = text,
                nativeDurationMs = nativeDuration,
                cumulativeDurationMs = cumulativeDuration,
                cumulativeTextLength = cumulativeLength,
                splitBindingCount = when (splitValue) {
                    null -> 0
                    is Collection<*> -> splitValue.size
                    else -> -1
                },
                isBackground = background,
            )
            val singleWord = isSingleUnmergedCjkWord(timing)
            if (!singleWord && !isUnmergedCjkSpan(timing, maxSpanGlowCharacters)) return@runCatching null
            CjkEntryState(
                entry = entry,
                views = foregroundEntryViews(entry),
                specialAnimatorsBefore = specialAnimatorSnapshot(entry),
                singleWord = singleWord,
            )
        }.getOrElse { error ->
            ModernXposedRuntime.log("CJK glow gate failed closed: ${error.cjkShortMessage()}")
            null
        }

    private fun cachedFields(type: Class<*>): kotlin.collections.Map<String, Field> =
        synchronized(fieldCache) {
            fieldCache.get(type) ?: HashMap<String, Field>().also { fields ->
                generateSequence(type) { it.superclass }
                    .flatMap { current -> current.declaredFields.asSequence() }
                    .filterNot { field -> Modifier.isStatic(field.modifiers) }
                    .forEach { field ->
                        if (!fields.containsKey(field.name)) {
                            runCatching { field.isAccessible = true }
                            fields[field.name] = field
                        }
                    }
                fieldCache[type] = fields
            }
        }

    private fun readField(field: Field, receiver: Any): Any? = runCatching {
        field.get(receiver)
    }.getOrNull()

    private fun readNamedField(receiver: Any, name: String): Any? =
        cachedFields(receiver.javaClass)[name]?.let { field -> readField(field, receiver) }

    private fun specialAnimatorSnapshot(entry: Any): List<Any> = when (
        val value = readNamedField(entry, "p")
    ) {
        is Collection<*> -> value.filterIsInstance<Animator>().map { it as Any }
        else -> emptyList()
    }

    private fun invokeNoArg(receiver: Any, methodName: String): Any? =
        invokeMethod(receiver, methodName)

    private fun invokeMethod(receiver: Any, methodName: String, vararg args: Any?): Any? = runCatching {
        receiver.javaClass.methods
            .firstOrNull { method ->
                method.name == methodName && method.parameterTypes.size == args.size
            }
            ?.invoke(receiver, *args)
    }.getOrNull()

    private data class CjkEntryState(
        val entry: Any,
        val views: List<Any>,
        val specialAnimatorsBefore: List<Any>,
        val singleWord: Boolean,
    )

    private data class CjkGlowBaseline(
        val scaleX: Float,
        val scaleY: Float,
    )

    private companion object {
        const val SPLIT_WORD_GLOW_LENGTH = "splitWordGlow.originalLength"
        const val SPLIT_WORD_GLOW_PREVIOUS = "splitWordGlow.previousPivots"
        const val MAX_REWRITE_LOGS = 3
        const val MAX_TIMING_REWRITE_LOGS = 3
    }
}

internal fun hasNewCjkGlowAnimator(
    before: Collection<Any>,
    after: Collection<Any>,
): Boolean = after.any { candidate -> before.none { previous -> previous === candidate } }

private fun containsHangul(text: CharSequence): Boolean {
    for (index in text.indices) {
        when (Character.UnicodeBlock.of(text[index])) {
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> return true
            else -> Unit
        }
    }
    return false
}

private fun Throwable.cjkShortMessage(): String = buildString {
    append(javaClass.simpleName.ifBlank { javaClass.name })
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}
