package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
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
    private val splitWordGlowAnimation = ThreadLocal<SplitWordGlowAnimation>()

    /** Lets the host build and animate its own glyph views without changing the timed spans. */
    @Synchronized fun installSplitWordGlow(names: JSONObject?): TargetCapabilityInstall {
        if (names == null) return TargetCapabilityInstall.Unsupported("Split-word glow has no exact layout contract")
        if (splitWordGlowInstalled) return TargetCapabilityInstall.Active("Split-word glow already installed")
        val resolution = symbols.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod)
        val transition = resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("Split-word glow: ${resolution.summary}")
        val scope = HookRegistrationScope()
        return runCatching {
            val layout = SplitWordGlowLayout(names, transition)
            val builds = ThreadLocal<SplitWordGlowBuild>()
            check(ensureGlowTimingHook(transition)) { "Split-word glow rise hook was rejected" }
            check(ModernXposedRuntime.activeModule()?.deoptimize(layout.bind) == true) {
                "Split-word glyph builder could not be deoptimized"
            }
            check(ModernXposedRuntime.hookMethod(layout.bind, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras[SPLIT_WORD_GLOW_BUILD] = builds.get()
                    builds.remove()
                    if (param.args.getOrNull(3) != layout.foregroundLayout) return
                    val vector = param.args.firstOrNull() ?: return
                    runCatching {
                        val candidates = layout.candidates(vector)
                        if (candidates.isNotEmpty()) builds.set(SplitWordGlowBuild(candidates))
                    }.onFailure { ModernXposedRuntime.log("Split-word glyph binding kept native behavior", it) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val build = builds.get() ?: return
                        if (build.admitted.isEmpty() || param.throwable != null) return
                        val words = param.result as? kotlin.collections.Map<*, *> ?: return
                        runCatching { restoreSplitWordLengths(words, build, layout.wordLength) }
                            .onFailure { ModernXposedRuntime.log("Split-word glyph length restore failed", it) }
                    } finally {
                        val previous = param.extras.remove(SPLIT_WORD_GLOW_BUILD) as? SplitWordGlowBuild
                        if (previous == null) builds.remove() else builds.set(previous)
                    }
                }
            }, scope)) { "Split-word glyph builder hook was rejected" }
            check(ModernXposedRuntime.hookMethod(layout.trim, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val build = builds.get() ?: return
                    val text = (param.result as? CharSequence)?.toString() ?: return
                    if (text !in build.candidates || !layout.isAggregateTrim()) return
                    // This caller uses the trim result only to compute the aggregate length.
                    // It draws the original spans. Restore their length before publishing the map.
                    build.admitted += text
                    param.result = text.take(SPLIT_WORD_GLOW_LIMIT)
                }
            }, scope)) { "Split-word aggregate length hook was rejected" }
            check(ModernXposedRuntime.hookMethod(transition, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras[SPLIT_WORD_GLOW_PREVIOUS_ANIMATION] = splitWordGlowAnimation.get()
                    splitWordGlowAnimation.remove()
                    runCatching {
                        val animation = splitWordGlowOverride(param, layout) ?: return
                        param.extras[SPLIT_WORD_GLOW_ANIMATION] = animation
                        layout.wordLength.setInt(animation.entry, SPLIT_WORD_GLOW_LIMIT)
                        splitWordGlowAnimation.set(animation)
                    }.onFailure { ModernXposedRuntime.log("Split-word glow gate kept native behavior", it) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val animation = param.extras.remove(SPLIT_WORD_GLOW_ANIMATION) as? SplitWordGlowAnimation
                            ?: return
                        runCatching { layout.wordLength.setInt(animation.entry, animation.length) }
                            .onFailure { ModernXposedRuntime.log("Split-word glow length restore failed", it) }
                    } finally {
                        val previous = param.extras.remove(SPLIT_WORD_GLOW_PREVIOUS_ANIMATION) as? SplitWordGlowAnimation
                        if (previous == null) splitWordGlowAnimation.remove() else splitWordGlowAnimation.set(previous)
                    }
                }
            }, scope)) { "Split-word glow hook was rejected" }
            scope.activate()
            splitWordGlowInstalled = true
            TargetCapabilityInstall.Active("Installed native glyph glow for split Latin words")
        }.getOrElse { error ->
            scope.close()
            ModernXposedRuntime.log("Split-word glow hook unavailable", error)
            TargetCapabilityInstall.Degraded("Split-word glow: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun restoreSplitWordLengths(words: kotlin.collections.Map<*, *>, build: SplitWordGlowBuild, length: Field) {
        for (text in build.admitted) {
            for (id in build.candidates.getValue(text)) {
                val entry = words[id] ?: continue
                if (length.getInt(entry) == SPLIT_WORD_GLOW_LIMIT) length.setInt(entry, text.length)
            }
        }
    }

    private fun splitWordGroup(entry: Any): Pair<Int, Int>? {
        val line = readNamedField(entry, "a") as? Int ?: return null
        val group = readNamedField(entry, "l") as? Int ?: return null
        return line to group
    }

    private fun splitWordGlowOverride(
        param: ModernMethodHook.MethodHookParam,
        layout: SplitWordGlowLayout,
    ): SplitWordGlowAnimation? {
        if (param.args.getOrNull(4) != false) return null
        val holder = param.args.getOrNull(0) ?: return null
        val wordId = param.args.getOrNull(2) as? Int ?: return null
        val words = readNamedField(holder, "G") as? kotlin.collections.Map<*, *> ?: return null
        val entry = words[wordId] ?: return null
        val wordLength = layout.wordLength
        if (!wordLength.declaringClass.isInstance(entry)) return null
        val length = wordLength.getInt(entry)
        val duration = readNamedField(entry, "f") as? Int ?: return null
        if (length <= SPLIT_WORD_GLOW_LIMIT || duration < 1_000) return null
        val groupKey = splitWordGroup(entry) ?: return null
        val group = words.values.filterNotNull().filter { splitWordGroup(it) == groupKey }
        if (group.size < 2) return null
        val spans = group.map {
            val text = (readNamedField(it, "c") as? CharSequence)?.toString()?.trim() ?: return null
            val spanDuration = readNamedField(it, "e") as? Int ?: return null
            text to spanDuration
        }
        val text = spans.joinToString("") { it.first }
        if (text.length != length || !eligibleSplitWordGlow(text, spans) ||
            spans.sumOf { it.second.toLong() } != duration.toLong()) return null
        val views = group.flatMapIndexed { index, member ->
            if (readNamedField(member, "i") != null) return null
            val glyphs = foregroundEntryViews(member).filterIsInstance<View>()
            // v0 emits one view per letter/digit; adjacent punctuation shares its view.
            if (glyphs.size != spans[index].first.count(Char::isLetterOrDigit).coerceAtLeast(1)) return null
            glyphs
        }
        if (views.distinct().size != views.size || views.any { it.width <= 0 || it.height <= 0 }) return null
        val parent = generateSequence(views.first().parent) { it.parent }
            .filterIsInstance<ViewGroup>().firstOrNull { ancestor ->
                views.all { view -> generateSequence(view.parent) { it.parent }.any { it === ancestor } }
            } ?: return null
        val tops = views.map { view ->
            Rect(0, 0, view.width, view.height).also { parent.offsetDescendantRectToMyCoords(view, it) }.top
        }
        if (tops.max() - tops.min() > views.maxOf { it.height } / 2) return null
        return SplitWordGlowAnimation(entry, length, views, layout)
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
        val timingInstalled = ensureGlowTimingHook(a0)

        val hooksInstalled = a0Installed && helperInstalled && glowEndHookInstalled &&
            glowViewEndHookInstalled && timingInstalled
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

    @Synchronized
    private fun ensureGlowTimingHook(transition: Executable): Boolean {
        if (!glowTimingHookInstalled) glowTimingHookInstalled = installGlowTimingHook(transition)
        return glowTimingHookInstalled
    }

    /**
     * Share the native child-registration seam between the Latin rise and CJK return.
     * The host's special glow uses a rise child followed by a return child.
     * For a single CJK binding the return child is otherwise delayed by
     * 2*duration, leaving the raised/glowing frame held for a long envelope.
     * Shorten only that exact z$f child to begin after the rise duration.
     */
    private fun installGlowTimingHook(a0: Executable): Boolean = runCatching {
        val returnType = Class.forName("${a0.declaringClass.name}\$f", false, a0.declaringClass.classLoader)
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
                    val splitWord = splitWordGlowAnimation.get()
                    if (splitWord != null) {
                        if (param.throwable == null) {
                            runCatching { splitWord.layout.boostRise(animator, listener, splitWord.views) }
                                .onFailure { ModernXposedRuntime.log("Split-word glow rise kept native progress", it) }
                        }
                        return
                    }
                    if (!isEligibleWordScope() || a0Stack().lastOrNull()?.singleWord != true) return
                    if (listener.javaClass != returnType) return
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

    private class SplitWordGlowBuild(val candidates: kotlin.collections.Map<String, List<Int>>) {
        val admitted = HashSet<String>()
    }

    private class SplitWordGlowAnimation(
        val entry: Any,
        val length: Int,
        val views: List<View>,
        val layout: SplitWordGlowLayout,
    )

    private class SplitWordGlowLayout(names: JSONObject, transition: Executable) {
        private val members = FragmentProfileMembers(
            checkNotNull(transition.declaringClass.classLoader), names, "cjk.splitWordGlow",
            FragmentMemberRequirements(
                methods = setOf("bind", "trim"),
                fields = setOf("wordLength", "riseFunction", "riseView"),
            ),
        )
        val bind = members.method("bind")
        val trim = members.method("trim")
        val wordLength = members.field("wordLength")
        private val riseFunction = members.field("riseFunction")
        private val riseView = members.field("riseView")
        val foregroundLayout = names.getInt("foregroundLayout")
        private val aggregateTrimLine = names.getInt("aggregateTrimLine")
        private val vector = bind.parameterTypes.first()
        private val size = vector.getMethod("size")
        private val wordAt = vector.getMethod("get", java.lang.Long.TYPE)
        private val dereference = wordAt.returnType.getMethod("get")
        private val nativeWord = dereference.returnType
        private val wordId = nativeWord.getMethod("getWordId")
        private val wordText = nativeWord.getMethod("getHtmlLineText")
        private val wordWhitespace = nativeWord.getMethod("isWhitespace")
        private val wordDuration = nativeWord.getMethod("getDuration")

        init {
            check(names.getInt("contractVersion") == 1)
            check(bind.declaringClass == transition.declaringClass && bind.parameterCount == 7 &&
                bind.parameterTypes[3] == Integer.TYPE && Map::class.java.isAssignableFrom(bind.returnType))
            check(trim.parameterTypes.toList() == listOf(CharSequence::class.java) &&
                trim.returnType == CharSequence::class.java && Modifier.isStatic(trim.modifiers))
            check(foregroundLayout != 0 && aggregateTrimLine > 0)
            check(wordLength.type == Integer.TYPE && !Modifier.isFinal(wordLength.modifiers))
            check(ValueAnimator.AnimatorUpdateListener::class.java.isAssignableFrom(riseFunction.declaringClass) &&
                riseFunction.type == riseView.declaringClass && View::class.java.isAssignableFrom(riseView.type))
            check(size.returnType == java.lang.Long.TYPE && wordText.returnType == String::class.java &&
                wordWhitespace.returnType == java.lang.Boolean.TYPE && wordDuration.returnType == Integer.TYPE &&
                wordId.returnType == Integer.TYPE)
        }

        fun boostRise(animator: ValueAnimator, listener: Any, views: List<View>) {
            if (listener.javaClass != riseFunction.declaringClass) return
            val function = riseFunction.get(listener) ?: return
            val view = riseView.get(function) as? View ?: return
            if (views.none { it === view }) return
            val nativeCurve = animator.interpolator ?: return
            // Reach more of the native rise before its scheduled return cancels it.
            animator.interpolator = TimeInterpolator { elapsed ->
                nativeCurve.getInterpolation((elapsed * SPLIT_WORD_GLOW_RISE_SPEED).coerceAtMost(1f))
            }
        }

        fun candidates(words: Any): kotlin.collections.Map<String, List<Int>> {
            val count = size.invoke(words) as Long
            if (count !in 2L..Int.MAX_VALUE.toLong()) return emptyMap()
            val eligible = HashMap<String, List<Int>>()
            val ambiguous = HashSet<String>()
            val pending = ArrayList<Triple<Int, String, Int>>()
            fun finishGroup() {
                if (pending.isEmpty()) return
                val text = pending.joinToString("") { it.second }.trim()
                if (text.length > SPLIT_WORD_GLOW_LIMIT && text.none(Char::isWhitespace)) {
                    val spans = pending.map { (_, span, duration) -> span.trim() to duration }
                    if (!eligibleSplitWordGlow(text, spans) || text in eligible) {
                        ambiguous += text
                    } else {
                        eligible[text] = pending.map { it.first }
                    }
                }
                pending.clear()
            }
            for (index in 0L until count) {
                val pointer = wordAt.invoke(words, index) ?: return emptyMap()
                val word = dereference.invoke(pointer) ?: return emptyMap()
                val text = wordText.invoke(word) as? String ?: return emptyMap()
                val whitespace = wordWhitespace.invoke(word) as Boolean
                if (whitespace && text.isBlank()) {
                    finishGroup()
                } else {
                    val trimmed = text.trim()
                    // A single span with the same text is indistinguishable at the trim call.
                    if (trimmed.length > SPLIT_WORD_GLOW_LIMIT) ambiguous += trimmed
                    val duration = if (whitespace) 0 else wordDuration.invoke(word) as Int
                    pending += Triple(wordId.invoke(word) as Int, text, duration)
                }
            }
            finishGroup()
            return eligible - ambiguous
        }

        // The same trim helper also returns display text elsewhere in this method.
        fun isAggregateTrim(): Boolean = Throwable().stackTrace.firstOrNull {
            it.className == bind.declaringClass.name && it.methodName == bind.name
        }?.lineNumber == aggregateTrimLine
    }

    private companion object {
        const val SPLIT_WORD_GLOW_LIMIT = 7
        const val SPLIT_WORD_GLOW_RISE_SPEED = 1.5f
        const val SPLIT_WORD_GLOW_ANIMATION = "splitWordGlow.animation"
        const val SPLIT_WORD_GLOW_PREVIOUS_ANIMATION = "splitWordGlow.previousAnimation"
        const val SPLIT_WORD_GLOW_BUILD = "splitWordGlow.previousBuild"
        const val MAX_REWRITE_LOGS = 3
        const val MAX_TIMING_REWRITE_LOGS = 3

        fun eligibleSplitWordGlow(text: String, spans: List<Pair<String, Int>>): Boolean =
            text.length > SPLIT_WORD_GLOW_LIMIT && spans.size >= 2 &&
                spans.all { (span, duration) -> span.isNotEmpty() && span.none(Char::isWhitespace) && duration > 0 } &&
                spans.sumOf { it.first.length } == text.length &&
                spans.any { (span, duration) -> duration >= 1_000 && span.length in 2..SPLIT_WORD_GLOW_LIMIT } &&
                text.any(Char::isLetter) && text.none {
                    it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
                }
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
